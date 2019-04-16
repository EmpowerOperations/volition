package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.*

interface IApiService {
    suspend fun offerResult(request: SimulationResponseDTO): SimulationResultConfirmDTO
    suspend fun offerConfig(request: NodeStatusCommandOrResponseDTO): NodeChangeConfirmDTO
    suspend fun offerError(request: ErrorResponseDTO): ErrorConfirmDTO
    fun register(request: RequestRegistrationCommandDTO, responseObserver: StreamObserver<RequestQueryDTO>)
    fun unregister(request: RequestUnRegistrationRequestDTO): UnRegistrationResponseDTO
    fun sendMessage(request: MessageCommandDTO): MessageResponseDTO
    fun updateNode(request: NodeStatusCommandOrResponseDTO): NodeChangeConfirmDTO
    fun resultRequest(request: ResultRequestDTO): ResultResponseDTO
    fun issueStopOptimization(request: StopOptimizationCommandDTO): StopOptimizationResponseDTO
    fun issueStartOptimization(request: StartOptimizationCommandDTO): StartOptimizationResponseDTO
    fun updateConfiguration(request: ConfigurationCommandDTO): ConfigurationResponseDTO
    fun autoConfigure(request: NodeStatusCommandOrResponseDTO): NodeChangeConfirmDTO
    fun changeNodeName(request: NodeNameChangeCommandDTO): NodeNameChangeResponseDTO
    fun stop()
    fun startAsync()
}

class ApiService(private val modelService: DataModelService,
                 private val optimizerService: OptimizerService) : IApiService {

    override fun register(request: RequestRegistrationCommandDTO, responseObserver: StreamObserver<RequestQueryDTO>) {
        val added = modelService.addSim(createDefaultSimulationWithName(request.name, responseObserver))
        if (!added) {
            responseObserver.onError(StatusException(Status.ALREADY_EXISTS))
        }
    }

    override suspend fun offerResult(request: SimulationResponseDTO): SimulationResultConfirmDTO {
        modelService.simulations.getNamed(request.name)?.output.sendAndRespond(request)
        return SimulationResultConfirmDTO.newBuilder().build()
    }

    override suspend fun offerConfig(request: NodeStatusCommandOrResponseDTO): NodeChangeConfirmDTO {
        modelService.simulations.getNamed(request.name)?.update.sendAndRespond(request)
        return NodeChangeConfirmDTO.newBuilder().build()
    }

    override suspend fun offerError(request: ErrorResponseDTO): ErrorConfirmDTO {
        modelService.simulations.getNamed(request.name)?.error.sendAndRespond(request)
        return ErrorConfirmDTO.newBuilder().build()
    }

    override fun unregister(request: RequestUnRegistrationRequestDTO): UnRegistrationResponseDTO {
        val unregistered = modelService.closeSim(request.name)
        return UnRegistrationResponseDTO.newBuilder().setMessage(buildUnregisterMessage(unregistered)).build()
    }

    override fun sendMessage(request: MessageCommandDTO): MessageResponseDTO {
        modelService.addNewMessage(Message(request.name, request.message))
        return MessageResponseDTO.newBuilder().build()
    }

    override fun updateNode(request: NodeStatusCommandOrResponseDTO): NodeChangeConfirmDTO {
        val newSimulationConfig = request.generateUpdatedSimulation()
        val updated = modelService.updateSimAndConfiguration(newSimulationConfig)

        return NodeChangeConfirmDTO.newBuilder().setMessage(buildSimulationUpdateMessage(newSimulationConfig, updated)).build()
    }

    override fun issueStopOptimization(request: StopOptimizationCommandDTO): StopOptimizationResponseDTO {
        val canStop = optimizerService.canStop()
        val newBuilder = StopOptimizationResponseDTO.newBuilder()
        return if(canStop){
            newBuilder.setRunID(request.id).build()
        }
        else{
            newBuilder.setMessage(buildStopMessage()).build()
        }
    }

    override fun stop(){
        optimizerService.stopOptimization()
    }

    override fun resultRequest(request: ResultRequestDTO): ResultResponseDTO {
        val responseBuilder = ResultResponseDTO.newBuilder()
        val list = modelService.resultList[UUID.fromString(request.runID)]
        if (list == null) {
            responseBuilder.message = buildRunNotFoundMessage(request)
        } else {
            val runResultBuilder = RunResult.newBuilder()
            runResultBuilder.addAllPoint(list.map { Design.newBuilder().putAllInput(it.inputs).putAllOutput(it.result).build() })
            runResultBuilder.addAllOptimum(emptyList())//TODO update optimum
            responseBuilder.runResult = runResultBuilder.build()

        }
        return responseBuilder.build()
    }

    override fun issueStartOptimization(request: StartOptimizationCommandDTO): StartOptimizationResponseDTO {
        val responseBuilder = StartOptimizationResponseDTO.newBuilder()
        val reply = optimizerService.requestStart()
        if (reply.isLeft()) {
            responseBuilder.message = buildStartIssuesMessage(reply.left().get())
        } else {
            responseBuilder.runID = reply.right().get().toString()
        }

        return responseBuilder.build()
    }

    override fun startAsync() {
        GlobalScope.launch {
            optimizerService.startOptimization()
        }
    }


    override fun updateConfiguration(request: ConfigurationCommandDTO): ConfigurationResponseDTO {
        val durationSet = modelService.setDuration(request.name, Duration.ofMillis(request.config.timeout))
        return ConfigurationResponseDTO.newBuilder().setMessage(buildUpdateMessage(durationSet)).build()
    }

    override fun autoConfigure(request: NodeStatusCommandOrResponseDTO): NodeChangeConfirmDTO {
        val autoSetupResult = modelService.autoSetup(request.generateUpdatedSimulation())
        return NodeChangeConfirmDTO.newBuilder().setMessage(buildAutoSetupMessage(autoSetupResult)).build()
    }

    override fun changeNodeName(request: NodeNameChangeCommandDTO): NodeNameChangeResponseDTO {
        val changed = modelService.renameSim(newName = request.newName, oldName = request.oldName)
        return NodeNameChangeResponseDTO.newBuilder().setChanged(changed).setMessage(buildNameChangeMessage(changed, request.oldName, request.newName)).build()
    }

    private suspend fun <T> Channel<T>?.sendAndRespond(request: T) {
        if (this == null) throw IllegalStateException("no outChannel available for '$request' or buffer full")
        send(request)
    }

    private fun NodeStatusCommandOrResponseDTO.generateUpdatedSimulation(): Simulation {
        return modelService.simulations.single { it.name == name }.copy(
                name = name,
                inputs = inputsList.map { Input(it.name, it.lowerBound, it.upperBound, it.currentValue) },
                outputs = outputsList.map { Output(it.name) },
                description = description
        )
    }

    internal fun createDefaultSimulationWithName(name: String, input: StreamObserver<RequestQueryDTO>) =
            Simulation(name, emptyList(), emptyList(), "", input, Channel(Channel.RENDEZVOUS), Channel(Channel.RENDEZVOUS), Channel(Channel.RENDEZVOUS))

    internal fun buildNameChangeMessage(changed: Boolean, oldName: String, newName: String) = "Name change request from $oldName to $newName ${if (changed) "succeed" else "failed"}"
    internal fun buildUnregisterMessage(result: Boolean) = "Unregister ${if (result) "success" else "failed"}"
    internal fun buildUpdateMessage(updated: Boolean) = "Configuration update request ${if (updated) "succeed" else "failed, there is no existing setup."}"
    internal fun buildSimulationUpdateMessage(newNode: Simulation, updated: Boolean) = "Simulation updated with inputs: ${newNode.inputs} outputs: ${newNode.outputs} ${if (updated) "succeed" else "failed"}"
    internal fun buildStartIssuesMessage(issues: List<String>) = "Optimization cannot start: ${issues.joinToString(", ")}"
    internal fun buildRunNotFoundMessage(request: ResultRequestDTO) = "Requested run ID ${request.runID} is not available"
    internal fun buildAutoSetupMessage(autoSetupResult: Boolean) = "Auto setup ${if (autoSetupResult) "succeed" else "failed"}"
    internal fun buildStopMessage() = "Optimization stop order rejected"
}