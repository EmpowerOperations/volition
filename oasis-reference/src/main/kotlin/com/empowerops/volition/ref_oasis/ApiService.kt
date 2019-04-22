package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Duration.*
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
                 private val optimizerService: OptimizationService) : IApiService {

    override fun register(request: RequestRegistrationCommandDTO, responseObserver: StreamObserver<RequestQueryDTO>) {
        modelService.addSim(Simulation(request.name, responseObserver)).let { added ->
            if (!added) responseObserver.onError(StatusException(Status.ALREADY_EXISTS))
        }
    }

    override suspend fun offerResult(request: SimulationResponseDTO): SimulationResultConfirmDTO {
        modelService.simulations.getNamed(request.name)?.output?.send(request)?:TODO("Simulation/output channel doesn't exit")
        return SimulationResultConfirmDTO.newBuilder().build()
    }

    override suspend fun offerConfig(request: NodeStatusCommandOrResponseDTO): NodeChangeConfirmDTO {
        modelService.simulations.getNamed(request.name)?.update?.send(request)?:TODO("Simulation/update channel doesn't exit")
        return NodeChangeConfirmDTO.newBuilder().build()
    }

    override suspend fun offerError(request: ErrorResponseDTO): ErrorConfirmDTO {
        modelService.simulations.getNamed(request.name)?.error?.send(request)?:TODO("Simulation/error channel doesn't exit")
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
        val (result, simulation) = modelService.updateSimAndConfiguration(request)
        return NodeChangeConfirmDTO.newBuilder().setMessage(buildSimulationUpdateMessage(simulation, result)).build()
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
        optimizerService.stop()
    }

    override fun resultRequest(request: ResultRequestDTO): ResultResponseDTO = ResultResponseDTO.newBuilder().apply {
        when (val list = modelService.resultList[UUID.fromString(request.runID)]) {
            null -> message = buildRunNotFoundMessage(request.runID)
            else -> runResult = runResultBuilder.apply {
                addAllPoint(list.map {
                    Design.newBuilder().apply {
                        putAllInput(it.inputs)
                        putAllOutput(it.result)
                    }.build()
                })
            }.build()
        }
    }.build()

    override fun issueStartOptimization(
            request: StartOptimizationCommandDTO
    ): StartOptimizationResponseDTO = StartOptimizationResponseDTO.newBuilder().apply {
        val canStart = optimizerService.canStart()
        var issues = modelService.findIssues()
        if (!canStart) issues += Issue("Optimization are not able to start")
        message = buildStartIssuesMessage(issues)
        acknowledged = issues.isEmpty()
    }.build()

    override fun startAsync() {
        GlobalScope.launch {
            optimizerService.start()
        }
    }

    override fun updateConfiguration(request: ConfigurationCommandDTO): ConfigurationResponseDTO =
            modelService.setDuration(request.name, ofMillis(request.config.timeout)).let { setupResult ->
                ConfigurationResponseDTO.newBuilder().apply {
                    message = buildUpdateMessage(request.name, ofMillis(request.config.timeout), setupResult)
                }
            }.build()

    override fun autoConfigure(request: NodeStatusCommandOrResponseDTO): NodeChangeConfirmDTO =
            modelService.autoSetup(request).let { (result, simulation) ->
                NodeChangeConfirmDTO.newBuilder().apply {
                    message = buildAutoSetupMessage(simulation, result)
                }
            }.build()


    override fun changeNodeName(request: NodeNameChangeCommandDTO): NodeNameChangeResponseDTO =
            modelService.renameSim(newName = request.newName, oldName = request.oldName).let { renamed ->
                NodeNameChangeResponseDTO.newBuilder().apply {
                    changed = renamed
                    message = buildNameChangeMessage(renamed, request.oldName, request.newName)
                }
            }.build()
}

internal fun buildNameChangeMessage(changed: Boolean, oldName: String, newName: String)
        = "Name change request from $oldName to $newName ${if (changed) "succeed" else "failed"}"
internal fun buildUnregisterMessage(result: Boolean)
        = "Unregister ${if (result) "success" else "failed"}"
internal fun buildStartIssuesMessage(issues: List<Issue>)
        = "Optimization cannot start: ${issues.joinToString(", ")}"
internal fun buildRunNotFoundMessage(runID: String)
        = "Requested run ID $runID is not available"

internal fun buildAutoSetupMessage(newNode: Simulation, updated: Boolean)
        = "Auto setup ${if (updated) "Succeed" else "Failed"} with inputs: ${newNode.inputs} outputs: ${newNode.outputs}"
internal fun buildUpdateMessage(name: String, timeOut: Duration, updated: Boolean)
        = "Configuration update ${if (updated) "succeed with timeout: ${timeOut}ms" else "failed, there is no existing setup named $name."}"
internal fun buildSimulationUpdateMessage(newNode: Simulation, updated: Boolean)
        = "Simulation updated ${if (updated) "succeed" else "failed"} with inputs: ${newNode.inputs} outputs: ${newNode.outputs}"

internal fun buildStopMessage()
        = "Optimization stop order rejected"