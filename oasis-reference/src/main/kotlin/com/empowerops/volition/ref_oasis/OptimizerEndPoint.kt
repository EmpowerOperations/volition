package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import com.google.common.eventbus.EventBus
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import org.funktionale.either.Either
import java.time.Duration

sealed class EvaluationResult {
    data class Success(val name: String, val result: Map<String, Double>) : EvaluationResult()
    data class TimeOut(val name: String) : EvaluationResult()
    data class Failed(val name: String, val exception: String) : EvaluationResult()
    data class Error(val name: String, val exception: String) : EvaluationResult()
}

sealed class CancelResult {
    data class Canceled(val name: String) : CancelResult()
    data class CancelFailed(val name: String, val exception: String) : CancelResult()
}

class OptimizerEndpoint(
        private val modelService: DataModelService,
        private val eventBus: EventBus
) : OptimizerGrpc.OptimizerImplBase() {
    private var state = State.Idle

    enum class State {
        Idle,
        Running,
        Pause
    }

    override fun changeNodeName(request: NodeNameChangeCommandDTO, responseObserver: StreamObserver<NodeNameChangeResponseDTO>) = responseObserver.consume {
        val changed = modelService.renameSim(request.newName, request.oldName)
        NodeNameChangeResponseDTO.newBuilder().setChanged(changed).build()
    }

    override fun register(request: RegistrationCommandDTO, responseObserver: StreamObserver<OASISQueryDTO>) {
        if (modelService.simulations.hasName(request.name)) {
            responseObserver.onCompleted()
            return
        }
        modelService.addNewSim(Simulation(request.name, emptyList(), emptyList(), "", responseObserver, Channel(RENDEZVOUS), Channel(RENDEZVOUS), Channel(RENDEZVOUS)))
        eventBus.post(StatusUpdateEvent("${request.name} registered"))
    }

    override fun startOptimization(request: StartOptimizationCommandDTO, responseObserver: StreamObserver<StartOptimizationResponseDTO>) = responseObserver.consume {
        val issues = modelService.findIssue()
        val message: String

        if (issues.isNotEmpty()) {
            message = "Optimization cannot start: ${issues.joinToString(", ")}"
        } else {
            message = "Optimization started"
            GlobalScope.async { startOptimization(RandomNumberOptimizer()) }
        }

        StartOptimizationResponseDTO.newBuilder().setMessage(message).setStarted(issues.isEmpty()).build()
    }

    suspend fun startOptimization(optimizer: RandomNumberOptimizer) {
        state = State.Running
        eventBus.post(StatusUpdateEvent("Evaluating..."))
        while (state == State.Running) {
            var pluginNumber = 1
            for (proxy in modelService.proxies) {
                eventBus.post(StatusUpdateEvent("Evaluating: ${proxy.name} ($pluginNumber/${modelService.simulations.size})"))
                val inputVector = optimizer.generateInputs(proxy.inputs)
                val simResult = evaluate(proxy.name, inputVector)
                eventBus.post(NewResultEvent(makeResult(simResult, inputVector)))
                eventBus.post(StatusUpdateEvent("Evaluation finished."))
                if (simResult is EvaluationResult.TimeOut) {
                    eventBus.post(StatusUpdateEvent("Timed out, Canceling..."))
                    cancel(proxy.name)
                    eventBus.post(StatusUpdateEvent("Cancel finished."))
                }
                pluginNumber++
            }
            if (state == State.Pause) {
                eventBus.post(StatusUpdateEvent("Paused"))
                while (state == State.Pause && state != State.Idle) {
                    delay(500)
                }
            }
        }
        eventBus.post(StatusUpdateEvent("Idle"))
    }

    private fun makeResult(evaluationResult: EvaluationResult, inputVector: Map<String, Double>): Result = when (evaluationResult) {
        is EvaluationResult.Success -> {
            Result(evaluationResult.name, "Success", inputVector.toString(), evaluationResult.result.toString())
        }
        is EvaluationResult.Failed -> {
            Result(evaluationResult.name, "Failed", inputVector.toString(), "Evaluation Failed: \n${evaluationResult.exception}")
        }
        is EvaluationResult.TimeOut -> {
            Result(evaluationResult.name, "Timeout", inputVector.toString(), "N/A")
        }
        is EvaluationResult.Error -> {
            Result(evaluationResult.name, "Error", inputVector.toString(), "Error:\n${evaluationResult.exception}")
        }
    }

    private suspend fun evaluate(name: String, inputVector: Map<String, Double>): EvaluationResult {
        val simulation = modelService.simulations.getValue(name)
        val proxy = modelService.proxies.getValue(name)
        val message = OASISQueryDTO.newBuilder().setEvaluationRequest(
                OASISQueryDTO.SimulationEvaluationRequest
                        .newBuilder()
                        .setName(name)
                        .putAllInputVector(inputVector)
        ).build()

        return try {
            simulation.input.onNext(message)
            select {
                simulation.output.onReceive { EvaluationResult.Success(it.name, it.outputVectorMap) }
                simulation.error.onReceive { EvaluationResult.Failed(it.name, it.exception) }
                if (proxy.timeOut != null) {
                    onTimeout(proxy.timeOut.toMillis()) {
                        EvaluationResult.TimeOut(name)
                    }
                }
            }
        } catch (exception: Exception) {
            EvaluationResult.Error("Optimizer", "Unexpected error happened when try to evaluate $inputVector though simulation $name. Cause: $exception")
        }
    }

    fun stopOptimization(): Boolean {
        state = State.Idle
        return true
    }

    fun pauseOptimization(): Boolean = when (state) {
        State.Running -> {
            state = State.Pause
            true
        }
        State.Pause -> {
            state = State.Running
            false
        }
        else -> false
    }

    suspend fun updateNode(simName: String) {
        syncConfigFor(simName)
    }

    suspend fun cancelAll() {
        modelService.simulations.forEach { cancel(it.name) }
    }

    /**
     * Cancel is NOT running in async mode because we are not managing state for plugin and we always assume plugin is in ready state
     * whenever it returns a result
     */
    private suspend fun cancel(name: String) {
        val message = OASISQueryDTO.newBuilder().setCancelRequest(OASISQueryDTO.SimulationCancelRequest.newBuilder().setName(name)).build()
        val simulation = modelService.simulations.getValue(name)

        simulation.input.onNext(message)
        val cancelResult = select<CancelResult> {
            simulation.output.onReceive { CancelResult.Canceled(it.name) }
            simulation.error.onReceive { CancelResult.CancelFailed(it.name, it.exception) }
        }
        val cancelMessage = when (cancelResult) {
            is CancelResult.Canceled -> {
                Message("Optimizer", "Evaluation Canceled")
            }
            is CancelResult.CancelFailed -> {
                Message("Optimizer", "Cancellation Failed, Cause:\n${cancelResult.exception}")
            }
        }
        eventBus.post(NewMessageEvent(cancelMessage))
    }

    suspend fun cancelAndStop() {
        stopOptimization()
        cancelAll()
    }

    private suspend fun syncConfigFor(simName: String) {
        val sim = modelService.simulations.getValue(simName)
        val message = OASISQueryDTO.newBuilder().setNodeStatusRequest(
                OASISQueryDTO.NodeStatusUpdateRequest.newBuilder().setName(simName)
        ).build()
        var result: Either<Simulation, Message>
        try {
            sim.input.onNext(message)

            result = select {
                sim.update.onReceive { Either.Left(updateFromResponse(it)) }
                sim.error.onReceive {
                    Either.Right(Message(it.name, "Error update simulation $simName due to ${it.message} :\n${it.exception}"))
                }
                onTimeout(Duration.ofSeconds(5).toMillis()) {
                    Either.Right(Message("Optimizer", "Update simulation timeout. Please check simulation is registered and responsive."))
                }
            }
        } catch (exception: Exception) {
            result = Either.Right(Message("Optimizer", "Unexpected error happened when update simulation $simName failed. Please check simulation is registered and responsive. Cause:\n$exception"))
        }

        if (result.isLeft()) {
            modelService.updateSim(result.left().get())
        } else {
            eventBus.post(NewMessageEvent(result.right().get()))
        }
    }

    override fun stopOptimization(request: StopOptimizationCommandDTO, responseObserver: StreamObserver<StopOptimizationResponseDTO>) = responseObserver.consume {
        GlobalScope.async { stopOptimization() }
        StopOptimizationResponseDTO.newBuilder().setMessage("Stop acknowledged").setStopped(true).build()
    }

    override fun offerSimulationResult(request: SimulationResponseDTO, responseObserver: StreamObserver<SimulationResultConfirmDTO>) = responseObserver.consume {
        val output = modelService.simulations.getNamed(request.name)?.output
        if (output != null) {
            output.send(request)
        } else {
            throw IllegalStateException("no simulation '${request.name}' or buffer full")
        }
        SimulationResultConfirmDTO.newBuilder().build()
    }

    override fun offerErrorResult(request: ErrorResponseDTO, responseObserver: StreamObserver<ErrorConfirmDTO>) = responseObserver.consume {
        val error = modelService.simulations.getNamed(request.name)?.error
        if (error != null) {
            error.send(request)
        } else {
            throw IllegalStateException("no simulation '${request.name}' or buffer full")
        }
        ErrorConfirmDTO.newBuilder().build()
    }

    override fun offerSimulationConfig(request: NodeStatusCommandOrResponseDTO, responseObserver: StreamObserver<NodeChangeConfirmDTO>) = responseObserver.consume {
        val update = modelService.simulations.getNamed(request.name)?.update

        if (update != null) {
            update.send(request)
        } else {
            throw IllegalStateException("no simulation '${request.name}' or buffer full")
        }
        NodeChangeConfirmDTO.newBuilder().build()
    }

    override fun updateNode(request: NodeStatusCommandOrResponseDTO, responseObserver: StreamObserver<NodeChangeConfirmDTO>) = responseObserver.consume {
        val newNode = updateFromResponse(request)
        modelService.updateSim(newNode)
        eventBus.post(StatusUpdateEvent("${request.name} updated"))
        NodeChangeConfirmDTO.newBuilder().setMessage("Simulation updated with inputs: ${newNode.inputs} outputs: ${newNode.outputs}").build()
    }


    override fun sendMessage(request: MessageCommandDTO, responseObserver: StreamObserver<MessageReponseDTO>) = responseObserver.consume {
        eventBus.post(NewMessageEvent(Message(request.name, request.message)))
        MessageReponseDTO.newBuilder().build()
    }

    override fun unregister(request: UnRegistrationRequestDTO, responseObserver: StreamObserver<UnRegistrationResponseDTO>) = responseObserver.consume {
        val unregistered = modelService.closeSim(request.name)
        UnRegistrationResponseDTO.newBuilder().setUnregistered(unregistered).build()
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
