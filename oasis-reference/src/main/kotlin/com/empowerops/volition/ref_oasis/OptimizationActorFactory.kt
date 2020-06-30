package com.empowerops.volition.ref_oasis

import com.google.common.eventbus.EventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.isActive
import java.util.*
import java.util.logging.Logger

sealed class OptimizerRequestMessage {

    data class NodeStatusUpdateRequest(val name: String): OptimizerRequestMessage()
    data class SimulationEvaluationRequest(val name: String, val inputVector: Map<String, Double>): OptimizerRequestMessage()
    data class SimulationCancelRequest(val name: String): OptimizerRequestMessage()
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

    fun make(output: SendChannel<OptimizerRequestMessage>): OptimizationActor = scope.actor<SimulationProvidedMessage>() {

        val sim = model.simulations.single()

        output.send(OptimizerRequestMessage.NodeStatusUpdateRequest(sim.name))
        val config = channel.receive() as? SimulationProvidedMessage.SimulationConfiguration

        require(config is SimulationProvidedMessage.SimulationConfiguration) { "expected SimulationConfiguration, but received $config" }
        require(config.sim.name == sim.name) { "at optimization start, config=$config, expected config=$sim" }
        require(config.sim.inputs == sim.inputs) { "at optimization start, config=$config, expected config=$sim" }
        require(config.sim.outputs == sim.outputs) { "at optimization start, config=$config, expected config=$sim" }

        val runID = UUID.randomUUID()
        try {
            eventBus.post(RunStartedEvent(runID))

            val completedDesigns = ArrayList<ExpensivePointRow>()
            val frontier = ArrayList<ExpensivePointRow>()
            var result: CompletableDeferred<UUID>? = null

            optimizing@while (isActive) { //stopOptimization maps to cancellation, to stop an optimization, cancel this actor

                val inputVector = optimizer.generateInputs(sim.inputs)

                try {
                    output.send(OptimizerRequestMessage.SimulationEvaluationRequest(sim.name, inputVector))

                    var response = channel.receive()

                    //read out any status messages
                    while (response is SimulationProvidedMessage.Message) {
                        eventBus.post(NewMessageEvent(Message(response.name, response.message)))
                        response = channel.receive()
                    }

                    //read the response
                    when (response) {
                        is SimulationProvidedMessage.EvaluationResult -> {
                            eventBus.post(NewResultEvent(EvaluationResult.Success(sim.name, inputVector, response.outputVector)))

                            val newPoint = ExpensivePointRow(
                                    model.inputs.map { inputVector.getValue(it.name) },
                                    model.outputs.map { response.outputVector.getValue(it.name) },
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
                        is SimulationProvidedMessage.SimulationConfiguration -> {
                            TODO("$response")
                        }
                        is SimulationProvidedMessage.Message -> TODO("$response")
                        is SimulationProvidedMessage.StopOptimization -> {
                            result = response.runID
                            break@optimizing
                        }
                    } as Any
                } catch (ex: CancellationException) {
                    if (!isActive) output.send(OptimizerRequestMessage.SimulationCancelRequest(sim.name))
                    else logger.warning("$this is cancelled but still active?")

                    throw ex
                }
            }

            val runResult = RunResult(
                    runID,
                    sim.inputs.map { it.name },
                    sim.outputs.map { it.name },
                    "OK",
                    completedDesigns,
                    frontier
            )

            model.setResult(runID, runResult)
            result!!.complete(runID)

        }
        finally {
            eventBus.post(RunStoppedEvent(runID))
        }
    }
}