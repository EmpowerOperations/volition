package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.dto.*
import com.empowerops.volition.ref_oasis.model.*
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CompletableDeferred
import java.time.Duration
import java.time.Duration.*
import java.util.*

class ApiService(
        private val modelService: ModelService,
        private val stateMachine: RunStateMachine,
) {

    suspend fun start(request: StartOptimizationCommandDTO) : StartOptimizationConfirmDTO {
        val result = CompletableDeferred<RunStateMachine.StartResult>()
        stateMachine.start(result)
        val results: RunStateMachine.StartResult = result.await()
        return StartOptimizationConfirmDTO.newBuilder().apply {
            when(results){
                is RunStateMachine.StartResult.Success ->  runID = results.runID.toString()
                is RunStateMachine.StartResult.Failed -> issues =  buildStartIssuesMessage(results.issues)
            }
        }.build()
    }

    suspend fun stop(request: StopOptimizationCommandDTO) : StopOptimizationConfirmDTO{
//        return if(stateMachine.stop()){
//            StopOptimizationResponseDTO.newBuilder().setRunID(request.id).build()
//        }
//        else{
//            StopOptimizationResponseDTO.newBuilder().setMessage(buildStopMessage()).build()
//        }
        TODO()
    }

//    fun register(request: RequestRegistrationCommandDTO, responseObserver: StreamObserver<RequestQueryDTO>) {
//        modelService.addSim(Simulation(request.name, responseObserver)).let { added ->
//            if (!added) responseObserver.onError(StatusException(Status.ALREADY_EXISTS))
//        }
//    }
//
//    suspend fun offerResult(request: SimulationResponseDTO): SimulationResultConfirmDTO {
//        require(modelService.simulations.hasName(request.name)){"Simulation/output channel doesn't exit"}
//        modelService.simulations.getValue(request.name).output.send(request)
//        return SimulationResultConfirmDTO.newBuilder().build()
//    }
//
//    suspend fun offerConfig(request: NodeStatusCommandOrResponseDTO): NodeChangeConfirmDTO {
//        require(modelService.simulations.hasName(request.name)){"Simulation/update channel doesn't exit"}
//        modelService.simulations.getValue(request.name).update.send(request)
//        return NodeChangeConfirmDTO.newBuilder().build()
//    }
//
//    suspend fun offerError(request: ErrorResponseDTO): ErrorConfirmDTO {
//        require(modelService.simulations.hasName(request.name)){"Simulation/error channel doesn't exit"}
//        modelService.simulations.getValue(request.name).error.send(request)
//        return ErrorConfirmDTO.newBuilder().build()
//    }
//
//    fun unregister(request: UnregistrationCommandDTO): UnregistrationConfirmDTO {
//        val unregistered = modelService.closeSim(request.name)
//        return UnregistrationConfirmDTO.newBuilder().setMessage(buildUnregisterMessage(unregistered)).build()
//    }
//
//    fun sendMessage(request: MessageCommandDTO): MessageResponseDTO {
//        modelService.addNewMessage(Message(request.name, request.message))
//        return MessageResponseDTO.newBuilder().build()
//    }
//
//    fun updateNode(request: NodeStatusCommandOrResponseDTO): NodeChangeConfirmDTO {
//        val (result, simulation) = modelService.updateSimAndConfiguration(request)
//        return NodeChangeConfirmDTO.newBuilder().setMessage(buildSimulationUpdateMessage(simulation, result)).build()
//    }
//
//    fun runResults(request: ResultRequestDTO): ResultResponseDTO {
//        return ResultResponseDTO.newBuilder().apply {
//            when (val list = modelService.resultList[UUID.fromString(request.runID)]) {
//                null -> message = buildRunNotFoundMessage(request.runID)
//                else -> runResult = runResultBuilder.apply {
//                    addAllPoint(list.map {
//                        Design.newBuilder().apply {
//                            putAllInput(it.inputs)
//                            putAllOutput(it.result)
//                        }.build()
//                    })
//                }.build()
//            }
//        }.build()
//    }

//    override fun updateConfiguration(request: ConfigurationCommandDTO): ConfigurationResponseDTO =
//            modelService.setTimeout(request.name, ofSeconds(request.config.timeout.seconds)).let { setupResult ->
//                ConfigurationResponseDTO.newBuilder().apply {
//                    updated = setupResult
//                    message = buildUpdateMessage(request.name, ofSeconds(request.config.timeout.seconds), setupResult)
//                }
//            }.build()
//
//    override fun autoConfigure(request: NodeStatusCommandOrResponseDTO): NodeChangeConfirmDTO =
//            modelService.autoSetup(request).let { (result, simulation) ->
//                NodeChangeConfirmDTO.newBuilder().apply {
//                    message = buildAutoSetupMessage(simulation, result)
//                }
//            }.build()
//
}

internal fun buildNameChangeMessage(changed: Boolean, oldName: String, newName: String)
        = "Name change request from $oldName to $newName ${if (changed) "succeed" else "failed"}"
internal fun buildUnregisterMessage(result: Boolean)
        = "Unregister ${if (result) "success" else "failed"}"
internal fun buildStartIssuesMessage(issues: List<Issue>)
        = if(issues.isEmpty()) "Optimization start issued" else "Optimization cannot start: ${issues.joinToString(", ")}"
internal fun buildRunNotFoundMessage(runID: String)
        = "Requested run ID $runID is not available"
internal fun buildAutoSetupMessage(newNode: Simulation, updated: Boolean)
        = "Auto setup ${if (updated) "Succeed" else "Failed"} with inputs: ${newNode.inputs} outputs: ${newNode.outputs}"
internal fun buildUpdateMessage(name: String, timeOut: Duration?, updated: Boolean)
        = "Configuration update ${if (updated) "succeed with timeout: ${timeOut}ms" else "failed, there is no existing setup named $name."}"
internal fun buildSimulationUpdateMessage(newNode: Simulation, updated: Boolean)
        = "Simulation updated ${if (updated) "succeed" else "failed"} with inputs: ${newNode.inputs} outputs: ${newNode.outputs}"
internal fun buildStopMessage()
        = "Optimization stop order rejected"