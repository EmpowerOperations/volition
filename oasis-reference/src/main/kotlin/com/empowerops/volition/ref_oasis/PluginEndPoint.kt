package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.NodeStatusCommandOrResponseDTO
import com.empowerops.volition.dto.OASISQueryDTO
import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.funktionale.either.Either
import java.time.Duration
import java.util.*

class PluginEndPoint(
        private val modelService : DataModelService,
        private val logger: ConsoleOutput,
        private val eventBus: EventBus
){
    init {
        eventBus.register(this)
    }

    @Subscribe
    fun onUpdateNodeRequested(event : SimulationUpdateRequestedEvent) = GlobalScope.launch{
        val sim = modelService.simulations.getValue(event.name)
        val message = OASISQueryDTO.newBuilder().setNodeStatusRequest(
                OASISQueryDTO.NodeStatusUpdateRequest.newBuilder().setName(event.name)
        ).build()
        var result: Either<Simulation, Message>
        try {
            sim.input.onNext(message)

            result = select {
                sim.update.onReceive { Either.Left(updateFromResponse(it)) }
                sim.error.onReceive {
                    Either.Right(Message(it.name, "Error update simulation ${event.name} due to ${it.message} :\n${it.exception}"))
                }
                this.onTimeout(Duration.ofSeconds(5).toMillis()) {
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
    @Subscribe
    fun onRunStated(event : RunStartedEvent) = GlobalScope.launch{
        for(proxy in modelService.proxies){
            val runStartQueryDTO = OASISQueryDTO.newBuilder().setStartRequest(OASISQueryDTO.SimulationStartedRequest.newBuilder().setRunID(event.id.toString())).build()
            try{
                modelService.simulations.getValue(proxy.name).input.onNext(runStartQueryDTO)
            }
            catch (exception : Exception){
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
    @Subscribe
    fun onRunStopped(event : RunStoppedEvent) = GlobalScope.launch{
        for(proxy in modelService.proxies){
            val runStopQueryDTO = OASISQueryDTO.newBuilder().setStopRequest(OASISQueryDTO.SimulationStoppedRequest.newBuilder().setRunID(event.id.toString())).build()
            try{
                modelService.simulations.getValue(proxy.name).input.onNext(runStopQueryDTO)
            }
            catch (exception : Exception){
                logger.log("Error sending stop request to ${proxy.name}", "Optimizer")
            }
        }
    }

    var sessionForceStopSignals : List<ForceStopSignal> = emptyList()

    suspend fun evaluate(proxy: Proxy, inputVector: Map<String, Double>): EvaluationResult {
        val simulation = modelService.simulations.getValue(proxy.name)
        val message = OASISQueryDTO.newBuilder().setEvaluationRequest(
                OASISQueryDTO.SimulationEvaluationRequest
                        .newBuilder()
                        .setName(simulation.name)
                        .putAllInputVector(inputVector)
        ).build()
        val forceStopSignal = ForceStopSignal(proxy.name)
        sessionForceStopSignals += forceStopSignal
        return try {
            simulation.input.onNext(message)
            select {
                simulation.output.onReceive { EvaluationResult.Success(it.name, inputVector, it.outputVectorMap) }
                simulation.error.onReceive { EvaluationResult.Failed(it.name, inputVector, it.exception) }
                if (proxy.timeOut != null) {
                    onTimeout(proxy.timeOut.toMillis()) {
                        EvaluationResult.TimeOut(simulation.name, inputVector)
                    }
                }
                forceStopSignal.completableDeferred.onAwait{
                    EvaluationResult.Terminated(simulation.name, inputVector, "Evaluation is terminated")
                }
            }
        } catch (exception: Exception) {
            EvaluationResult.Error(
                    "Optimizer",
                    inputVector,
                    "Unexpected error happened when try to evaluate $inputVector though simulation ${simulation.name}. Cause: $exception"
            )
        } finally {
            sessionForceStopSignals -= forceStopSignal
        }
    }

    /**
     * Cancel is NOT running in async mode because we are not managing state for plugin and we always assume plugin is in ready state
     * whenever it returns a result
     */
    suspend fun cancelCurrentEvaluation(proxy: Proxy) {
        val simulation = modelService.simulations.getValue(proxy.name)
        val message = OASISQueryDTO.newBuilder().setCancelRequest(OASISQueryDTO.SimulationCancelRequest.newBuilder().setName(simulation.name)).build()

        simulation.input.onNext(message)
        val cancelResult = select<CancelResult> {
            simulation.output.onReceive { CancelResult.Canceled(it.name) }
            simulation.error.onReceive { CancelResult.CancelFailed(it.name, it.exception) }

        }
        val cancelMessage = when (cancelResult) {
            is CancelResult.Canceled -> {
                "Evaluation Canceled"
            }
            is CancelResult.CancelFailed -> {
                "Cancellation Failed, Cause:\n${cancelResult.exception}"
            }
        }
        logger.log(cancelMessage, "Optimizer")
    }

    @Subscribe
    fun forceStopEveryThingOnStop(event : StopRequestedEvent){
        sessionForceStopSignals.forEach{ it.completableDeferred.complete(Unit) }
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