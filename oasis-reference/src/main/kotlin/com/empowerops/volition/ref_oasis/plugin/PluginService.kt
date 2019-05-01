package com.empowerops.volition.ref_oasis.plugin

import com.empowerops.volition.dto.NodeStatusCommandOrResponseDTO
import com.empowerops.volition.dto.RequestQueryDTO
import com.empowerops.volition.ref_oasis.front_end.ConsoleOutput
import com.empowerops.volition.ref_oasis.model.*
import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.funktionale.either.Either
import java.util.*

class PluginService(
        private val modelService : ModelService,
        private val logger: ConsoleOutput,
        private val eventBus: EventBus
){
    init {
        eventBus.register(this)
    }

    @Subscribe
    fun onUpdateNodeRequested(event : SimulationUpdateRequestedEvent) = GlobalScope.launch{
        val sim = modelService.simulations.getValue(event.name)
        val message = RequestQueryDTO.newBuilder().setNodeStatusRequest(
                RequestQueryDTO.NodeStatusUpdateRequest.newBuilder().setName(event.name)
        ).build()
        var result: Either<Simulation, Message>
        try {
            sim.input.onNext(message)

            result = select {
                sim.update.onReceive { Either.Left(updateFromResponse(it)) }
                sim.error.onReceive {
                    Either.Right(Message(it.name, "Error update simulation ${event.name} due to ${it.message} :\n${it.exception}"))
                }
                this.onTimeout(modelService.updateTimeout) {
                    Either.Right(Message("Optimizer", "Update simulation timeout. Please check simulation is registered and responsive."))
                }
            }
        } catch (exception: Exception) {
            result = Either.Right(Message("Optimizer", "Unexpected error happened when update simulation ${event.name} failed. Please check simulation is registered and responsive. Cause:\n$exception"))
        }
        if (result.isLeft()) {
            modelService.updateSimAndConfiguration(result.left().get())
        }
        else {
            val resultMessage = result.right().get()
            logger.log(resultMessage.message, resultMessage.sender)
        }
    }

    /**
     * Notify each simulation a new run has started with RunID and they should record the runID
     * if they need retrieve this run result.
     *
     * Simulation can also ignore this message
     */
    fun notifyStart(runId : UUID) {
        for(proxy in modelService.proxies){
            val runStartQueryDTO = RequestQueryDTO.newBuilder().setStartRequest(
                    RequestQueryDTO.SimulationStartedRequest.newBuilder().setRunID(runId.toString())
            ).build()

            try{
                modelService.simulations.getValue(proxy.name).input.onNext(runStartQueryDTO)
            }
            catch (exception: Exception) {
                logger.log("Error sending start request to ${proxy.name}", "Optimizer")
            }
        }
    }

    /**
     * Notify each simulation the current run has stopped and they should record the runID
     * if they need retrieve that run result later.
     *
     * Simulation can also ignore this message
     */
    fun notifyStop(runId : UUID) {
        for(proxy in modelService.proxies){
            val runStopQueryDTO = RequestQueryDTO.newBuilder().setStopRequest(
                    RequestQueryDTO.SimulationStoppedRequest.newBuilder().setRunID(runId.toString())
            ).build()

            try{
                modelService.simulations.getValue(proxy.name).input.onNext(runStopQueryDTO)
            }
            catch (exception : Exception){
                logger.log("Error sending stop request to ${proxy.name}", "Optimizer")
            }
        }
    }

    suspend fun evaluateAsync(
            proxy: Proxy,
            inputVector: Map<String, Double>,
            forceStopSignal: ForceStopSignal
    ): Deferred<EvaluationResult> = GlobalScope.async {
        val simulation = modelService.simulations.getValue(proxy.name)
        val message = RequestQueryDTO.newBuilder().setEvaluationRequest(
                RequestQueryDTO.SimulationEvaluationRequest
                        .newBuilder()
                        .setName(simulation.name)
                        .putAllInputVector(inputVector)
        ).build()

        return@async try {
            simulation.input.onNext(message)
            select<EvaluationResult> {
                simulation.output.onReceive { EvaluationResult.Success(it.name, inputVector, it.outputVectorMap) }
                simulation.error.onReceive { EvaluationResult.Failed(it.name, inputVector, it.exception) }
                if (proxy.timeOut != null) {
                    onTimeout(proxy.timeOut.toMillis()) {
                        EvaluationResult.TimeOut(simulation.name, inputVector)
                    }
                }
                forceStopSignal.completableDeferred.onAwait {
                    modelService.closeSim(simulation.name)
                    EvaluationResult.Terminated(simulation.name, inputVector, "Evaluation is terminated during evaluation")
                }
            }
        } catch (exception: Exception) {
            EvaluationResult.Error(
                    simulation.name,
                    inputVector,
                    "Unexpected error happened when try to evaluate $inputVector though simulation ${simulation.name}. Cause: $exception"
            )
        }
    }

    suspend fun cancelCurrentEvaluationAsync(proxy: Proxy, forceStopSignal: ForceStopSignal) = GlobalScope.async {
        val simulation = modelService.simulations.getValue(proxy.name)
        val message = RequestQueryDTO.newBuilder().setCancelRequest(RequestQueryDTO.SimulationCancelRequest.newBuilder().setName(simulation.name)).build()

        simulation.input.onNext(message)
        return@async select<CancelResult> {
            simulation.output.onReceive { CancelResult.Canceled(it.name) }
            simulation.error.onReceive { CancelResult.CancelFailed(it.name, it.exception) }
            forceStopSignal.completableDeferred.onAwait {
                modelService.closeSim(simulation.name)
                CancelResult.CancelTerminated(simulation.name, "Cancellation is terminated")
            }
        }
    }

    private fun updateFromResponse(request: NodeStatusCommandOrResponseDTO): Simulation {
        return modelService.simulations.single { it.name == request.name }.copy(
                name = request.name,
                inputs = request.inputsList.map { Input(it.name, it.lowerBound, it.upperBound, it.currentValue) },
                outputs = request.outputsList.map { Output(it.name) },
                description = request.description
        )
    }
}