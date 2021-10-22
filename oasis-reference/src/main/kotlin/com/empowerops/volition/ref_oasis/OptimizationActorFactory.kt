package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.DesignRowDTO
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger

sealed class OptimizerRequestMessage {

    data class SimulationEvaluationRequest(val name: String, val inputVector: Map<String, Double>): OptimizerRequestMessage()
    data class SimulationCancelRequest(val name: String): OptimizerRequestMessage()

    data class RunStartedNotification(val name: String, val runID: UUID): OptimizerRequestMessage()
    data class RunFinishedNotification(val name: String, val runID: UUID): OptimizerRequestMessage()

    data class RunNotStartedNotification(val issues: List<String>): OptimizerRequestMessage()
    data class ExpensivePointFoundNotification(val point: ExpensivePointRow): OptimizerRequestMessage()
}

sealed class SimulationProvidedMessage {
    data class EvaluationResult(
            val outputVector: Map<String, Double>
    ): SimulationProvidedMessage()

    data class ErrorResponse(
            val name: String,
            val message: String
    ) : SimulationProvidedMessage()

    data class Message(
            val name: String,
            val message: String
    ) : SimulationProvidedMessage()

    data class SimulationConfiguration(val sim: Simulation): SimulationProvidedMessage()

    data class StopOptimization(
            val runID: CompletableDeferred<UUID> = CompletableDeferred()
    ): SimulationProvidedMessage()

    data class StartOptimizationRequest(
        val inputs: List<Input>,
        val transforms: List<MathExpression>,
        val constraints: List<MathExpression>,
        val objectives: List<Output>,
        val nodes: List<Simulation>,
        val seedPoints: List<ExpensivePointRow>,
        val settings: OptimizationSettings
    ): SimulationProvidedMessage()
}

data class OptimizationSettings(
        val runtime: Duration?,
        val iterationCount: Int?,
        val targetObjectiveValue: Double?
)

typealias OptimizationActor = SendChannel<SimulationProvidedMessage>

class OptimizationActorFactory(
        val scope: CoroutineScope,
        val optimizer: Optimizer,
        val model: MutableMap<UUID, RunResult>,
        val eventBus: EventBus
) {

    private val logger = Logger.getLogger(OptimizationActorFactory::class.qualifiedName)

    //TODO: replace with
    // fun make(input: ReceiveChannel<SimulationProvidedMessage>): ReceieveChannel<OptimizerRequestMessage> = scope.produce { ...
    fun make(output: SendChannel<OptimizerRequestMessage>): OptimizationActor = scope.actor(start = CoroutineStart.LAZY) {
        val runID = UUID.randomUUID()
        var stopRequest: SimulationProvidedMessage.StopOptimization? = null
        val startMessage = channel.receive() as SimulationProvidedMessage.StartOptimizationRequest
        val sim = startMessage.nodes.single()

        val issues = ArrayList<String>()
        if(startMessage.objectives.isEmpty()){
            issues += "cannot optimize without at least one objective"
        }
        val badVars = startMessage.inputs.filter { it.lowerBound > it.upperBound }
        if(badVars.any()){
            issues += "the variables ${badVars.joinToString { it.name }} must have a lower bound less than the upper bound"
        }
        if(startMessage.seedPoints.any { it.inputs.size != startMessage.inputs.size }){
            issues += "some seed points dont match the number of input parameters"
        }
        if(startMessage.seedPoints.any { it.outputs.size != startMessage.run { transforms.size + objectives.size + constraints.size }}){
            issues += "some seed points dont match the number of output parameters"
        }
        //...etc
        // TODO: implement more validation
        if(issues.any()){
            output.send(OptimizerRequestMessage.RunNotStartedNotification(issues))
            return@actor
        }
        else try {
            output.send(OptimizerRequestMessage.RunStartedNotification(sim.name, runID))
            eventBus.post(RunStartedEvent(runID))

            val inputNames = startMessage.inputs.map { it.name }
            val completedDesigns = startMessage.seedPoints
                .map { seed ->
                    val inputVector = inputNames.zip(seed.inputs).toMap()
                    val isActuallyFeasible = startMessage.constraints.all { it.expression.evaluate(inputVector) <= 0.0 }
                    val isActuallyFrontier = ! startMessage.seedPoints.any { it.dominates(seed) }
                    ExpensivePointRow(seed.inputs, seed.outputs, isActuallyFeasible, isActuallyFrontier)
                }
                .toMutableList()

            val targetIterationCount = startMessage.settings.iterationCount ?: Int.MAX_VALUE
            var iterationCount = 0

            val endTime = Instant.now() + (startMessage.settings.runtime ?: Duration.ofSeconds(9_999_999))

            optimizing@while (isActive
                    && stopRequest == null
                    && iterationCount < targetIterationCount
                    && Instant.now() < endTime
            ) {

                var response: SimulationProvidedMessage? = null

                // before we start an iteration, poll (check without blocking) our message box
                // for a stop-optimization request.
                response = channel.poll()
                if(response is SimulationProvidedMessage.StopOptimization){
                    stopRequest = response
                    break@optimizing
                }

                try {
                    // otherwise start a simulation evaluation
                    val inputVector = optimizer.generateInputs(startMessage.inputs, startMessage.constraints.map { it.expression })

                    output.send(OptimizerRequestMessage.SimulationEvaluationRequest(sim.name, inputVector.filterKeys { it in sim.inputs }))

                    // read out any status messages
                    // these are messages that relate to but do not complete
                    // the simulation request we just made.
                    preamble@while(isActive) {
                        response = channel.receive()
                        when(response){
                            is SimulationProvidedMessage.Message -> {
                                eventBus.post(NewMessageEvent(Message(response.name, response.message)))
                            }
                            is SimulationProvidedMessage.StopOptimization -> {

                                // if we get a stop request now, cancel the existing simulation request
                                // **BUT DO NOT STOP OPTIMIZATION** until the simulation evaluation completes
                                // (below, with either an ErrorResponse or a EvaluationResult)
                                eventBus.post(StopRequestedEvent(runID))
                                output.send(OptimizerRequestMessage.SimulationCancelRequest(sim.name))
                                stopRequest = response
                            }
                            else -> {
                                break@preamble
                            }
                        }
                    }

                    check(response != null)

                    //read the response
                    decodeResponse@when (response) {
                        is SimulationProvidedMessage.EvaluationResult -> {
                            eventBus.post(NewResultEvent(EvaluationResult.Success(sim.name, inputVector, response.outputVector)))

                            if(stopRequest == null) {

                                val inputVector = startMessage.inputs
                                    .map { it.name }
                                    .associateWith { inputVector.getValue(it) }

                                val passesConstraints = startMessage.constraints
                                    .all { it.expression.evaluate(inputVector) <= 0.0 }

                                val newPoint = ExpensivePointRow(
                                        inputVector.values.toList(),
                                        startMessage.objectives.map { response.outputVector.getValue(it.name) },
                                        isFeasible = passesConstraints,
                                        isFrontier = null //compute this below
                                )

                                completedDesigns += newPoint

                                for(design in completedDesigns){
                                    design.isFrontier = completedDesigns.none { it.dominates(design) }
                                }

                                optimizer.addCompleteDesign(inputVector + response.outputVector)

                                output.send(OptimizerRequestMessage.ExpensivePointFoundNotification(newPoint))

                            }

                            Unit
                        }
                        is SimulationProvidedMessage.ErrorResponse -> {
                            eventBus.post(NewResultEvent(EvaluationResult.Error(sim.name, inputVector, response.message)))
                            // we dont update the optimizer state when the evaluation encounters an error
                            // -- this is approximately true for both this implementation and Empower commercial optimizers
                        }
                        is SimulationProvidedMessage.SimulationConfiguration,
                        is SimulationProvidedMessage.Message,
                        is SimulationProvidedMessage.StartOptimizationRequest,
                        is SimulationProvidedMessage.StopOptimization -> TODO("$response")
                    } as Any
                }
                catch (ex: CancellationException) {
                    if (!isActive) output.offer(OptimizerRequestMessage.SimulationCancelRequest(sim.name))
                    else logger.warning("$this is cancelled but still active?")

                    throw ex
                }

                iterationCount += 1
            }

            val runResult = RunResult(
                    runID,
                    inputNames,
                    startMessage.objectives.map { it.name },
                    completedDesigns,
            )

            model[runID] = runResult
            stopRequest?.runID?.complete(runID)
        }
        catch(ex: CancellationException){
            logger.warning("optimization actor was cancelled, and is now quitting.")
            return@actor
        }
        finally {
            eventBus.post(RunStoppedEvent(runID))
            try { output.send(OptimizerRequestMessage.RunFinishedNotification(sim.name, runID)) }
                    catch(ex: Exception) { logger.log(Level.WARNING, "failed to send run finished notification to client", ex) }
        }
    }
}