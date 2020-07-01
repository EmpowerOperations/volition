package com.empowerops.volition.ref_oasis

import com.empowerops.babel.BabelExpression
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.isActive
import java.util.*
import java.util.logging.Logger

data class StartOptimizationRequest(
        val inputs: List<Input>,
        val intermediates: List<MathExpression>,
        val constraints: List<MathExpression>,
        val objectives: List<Output>,
        val nodes: List<Simulation>
)

sealed class OptimizerRequestMessage {

    data class SimulationEvaluationRequest(val name: String, val inputVector: Map<String, Double>): OptimizerRequestMessage()
    data class SimulationCancelRequest(val name: String): OptimizerRequestMessage()

    data class RunStartedNotification(val name: String, val runID: UUID): OptimizerRequestMessage()
    data class RunFinishedNotification(val name: String, val runID: UUID): OptimizerRequestMessage()
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
}

typealias OptimizationActor = SendChannel<SimulationProvidedMessage>

class OptimizationActorFactory(
        val scope: CoroutineScope,
        val optimizer: Optimizer,
        val model: ModelService,
        val eventBus: EventBus
) {

    private val logger = Logger.getLogger(OptimizationActorFactory::class.qualifiedName)

    fun make(startOptimization: StartOptimizationRequest, output: SendChannel<OptimizerRequestMessage>): OptimizationActor = scope.actor {

        val runID = UUID.randomUUID()
        var stopRequest: SimulationProvidedMessage.StopOptimization? = null
        val sim = startOptimization.nodes.single()

        try {
            output.send(OptimizerRequestMessage.RunStartedNotification(sim.name, runID))
            eventBus.post(RunStartedEvent(runID))

            val completedDesigns = ArrayList<ExpensivePointRow>()
            val frontier = ArrayList<ExpensivePointRow>()

            optimizing@while (isActive && stopRequest == null) {

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
                    val inputVector = optimizer.generateInputs(startOptimization.inputs, startOptimization.constraints.map { it.expression })

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
                    when (response) {
                        is SimulationProvidedMessage.EvaluationResult -> {
                            eventBus.post(NewResultEvent(EvaluationResult.Success(sim.name, inputVector, response.outputVector)))

                            val newPoint = ExpensivePointRow(
                                    startOptimization.inputs.map { inputVector.getValue(it.name) },
                                    startOptimization.objectives.map { response.outputVector.getValue(it.name) },
                                    true
                            )
                            completedDesigns += newPoint

                            frontier += newPoint

                            frontier.removeIf { existingFrontierPoint ->
                                frontier.toTypedArray().any { it.dominates(existingFrontierPoint) }
                            }

                            optimizer.addCompleteDesign(inputVector + response.outputVector)
                        }
                        is SimulationProvidedMessage.ErrorResponse -> {
                            eventBus.post(NewResultEvent(EvaluationResult.Error(sim.name, inputVector, response.message)))
                            // we dont update the optimizer state when the evaluation encounters an error
                            // -- this is approximately true for both this implementation and Empower commercial optimizers
                        }
                        is SimulationProvidedMessage.SimulationConfiguration,
                        is SimulationProvidedMessage.Message,
                        is SimulationProvidedMessage.StopOptimization -> TODO("$response")
                    } as Any
                }
                catch (ex: CancellationException) {
                    if (!isActive) output.offer(OptimizerRequestMessage.SimulationCancelRequest(sim.name))
                    else logger.warning("$this is cancelled but still active?")

                    throw ex
                }
            }

            check(stopRequest != null)

            val runResult = RunResult(
                    runID,
                    startOptimization.inputs.map { it.name },
                    startOptimization.objectives.map { it.name },
                    "OK",
                    completedDesigns,
                    frontier
            )

            model.setResult(runID, runResult)
            stopRequest.runID.complete(runID)
        }
        finally {
            output.send(OptimizerRequestMessage.RunFinishedNotification(sim.name, runID))
            eventBus.post(RunStoppedEvent(runID))
        }
    }
}