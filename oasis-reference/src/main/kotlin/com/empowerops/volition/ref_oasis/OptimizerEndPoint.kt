package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import java.time.Duration
import java.util.*

/**
 * All the method in endpoint are handing inbound call.
 * This also does not need any logging since all of api call are logged.
 */
class OptimizerEndpoint(
        private val modelService: DataModelService,
        private val optimizerService : OptimizerService
) : OptimizerGrpc.OptimizerImplBase() {

    override fun changeNodeName(request: NodeNameChangeCommandDTO, responseObserver: StreamObserver<NodeNameChangeResponseDTO>) = responseObserver.consume {
        val changed = modelService.renameSim(newName = request.newName, oldName = request.oldName)

        NodeNameChangeResponseDTO
                .newBuilder()
                .setChanged(changed)
                .setMessage(buildNameChangeMessage(changed, request.oldName, request.newName))
                .build()
    }

    override fun autoConfigure(request: NodeStatusCommandOrResponseDTO, responseObserver: StreamObserver<NodeChangeConfirmDTO>) = responseObserver.consume {
        val autoSetupResult = modelService.autoSetup(request.generateUpdatedSimulation())
        NodeChangeConfirmDTO
                .newBuilder()
                .setMessage(buildAutoSetupMessage(autoSetupResult))
                .build()
    }



    override fun updateConfiguration(request: ConfigurationCommandDTO, responseObserver: StreamObserver<ConfigurationResponseDTO>)  = responseObserver.consume {
        val durationSet = modelService.setDuration(request.name, Duration.ofMillis(request.config.timeout))

        ConfigurationResponseDTO
                .newBuilder()
                .setMessage(buildUpdateMessage(durationSet))
                .build()
    }

    /**
     * Rethink how result works evaluation one input, for one and for multiple tool
     */
    override fun requestRunResult(request: ResultRequestDTO, responseObserver: StreamObserver<ResultResponseDTO>) = responseObserver.consume {
        val responseBuilder = ResultResponseDTO.newBuilder()
        val list = modelService.resultList[UUID.fromString(request.runID)]
        if (list == null) {
            responseBuilder.message = buildRunNotFoundMessage(request)
        } else {
            val runResultBuilder = RunResult.newBuilder()
            runResultBuilder.addAllPoint(list.map { Design.newBuilder().putAllInput(it.inputs).putAllOutput(it.outputs).build() })
            runResultBuilder.addAllOptimum(emptyList())//TODO update optimum
            responseBuilder.runResult = runResultBuilder.build()

        }
        responseBuilder.build()
    }


    override fun register(request: RegistrationCommandDTO, responseObserver: StreamObserver<OASISQueryDTO>) {
        val added = modelService.addSim(
                Simulation(request.name, emptyList(), emptyList(), "", responseObserver, Channel(RENDEZVOUS), Channel(RENDEZVOUS), Channel(RENDEZVOUS))
        )

        if ( ! added) {
            responseObserver.onError(StatusException(Status.ALREADY_EXISTS))
        }
    }

    override fun startOptimization(request: StartOptimizationCommandDTO, responseObserver: StreamObserver<StartOptimizationResponseDTO>) = responseObserver.consume {
        val responseBuilder = StartOptimizationResponseDTO.newBuilder()
        val reply = optimizerService.startOptimization()
        if (reply.isLeft()) {
            responseBuilder.message = buildStartIssuesMessage(reply.left().get())
        }
        else {
            responseBuilder.runID = reply.right().get().toString()
        }
        responseBuilder.build()
    }

    private fun buildStartFailedMessage() = "Start order rejected"

    override fun stopOptimization(request: StopOptimizationCommandDTO, responseObserver: StreamObserver<StopOptimizationResponseDTO>) = responseObserver.consume {
        val stopped = optimizerService.stopOptimization()

        StopOptimizationResponseDTO
                .newBuilder()
                .setRunID(request.id)
                .setMessage(buildStopMessage(stopped))
                .build()
    }

    override fun updateNode(request: NodeStatusCommandOrResponseDTO, responseObserver: StreamObserver<NodeChangeConfirmDTO>) = responseObserver.consume {
        val newSimulationConfig = request.generateUpdatedSimulation()
        val updated = modelService.updateSimAndConfiguration(newSimulationConfig)

        NodeChangeConfirmDTO
                .newBuilder()
                .setMessage(buildSimulationUpdateMessage(newSimulationConfig, updated))
                .build()
    }

    override fun sendMessage(request: MessageCommandDTO, responseObserver: StreamObserver<MessageResponseDTO>) = responseObserver.consume {
        modelService.addNewMessage(Message(request.name, request.message))
        MessageResponseDTO
                .newBuilder()
                .build()
    }

    override fun unregister(request: UnRegistrationRequestDTO, responseObserver: StreamObserver<UnRegistrationResponseDTO>) = responseObserver.consume {
        val unregistered = modelService.closeSim(request.name)

        UnRegistrationResponseDTO
                .newBuilder()
                .setMessage(buildUnregisterMessage(unregistered))
                .build()
    }

    override fun offerSimulationResult(request: SimulationResponseDTO, responseObserver: StreamObserver<SimulationResultConfirmDTO>) = responseObserver.consume {
        modelService.simulations.getNamed(request.name)?.output.sendAndRespond(request) { SimulationResultConfirmDTO.newBuilder().build() }
    }

    override fun offerErrorResult(request: ErrorResponseDTO, responseObserver: StreamObserver<ErrorConfirmDTO>) = responseObserver.consume {
        modelService.simulations.getNamed(request.name)?.error.sendAndRespond(request) { ErrorConfirmDTO.newBuilder().build() }
    }

    override fun offerSimulationConfig(request: NodeStatusCommandOrResponseDTO, responseObserver: StreamObserver<NodeChangeConfirmDTO>) = responseObserver.consume {
        modelService.simulations.getNamed(request.name)?.update.sendAndRespond(request) { NodeChangeConfirmDTO.newBuilder().build() }
    }

    private suspend fun <T, U> Channel<T>?.sendAndRespond(request : T, responseBuilder : () -> U) : U {
        if(this == null)throw IllegalStateException("no outChannel available for '$request' or buffer full")
        send(request)
        return responseBuilder.invoke()
    }

    private fun NodeStatusCommandOrResponseDTO.generateUpdatedSimulation(): Simulation {
        return modelService.simulations.single { it.name == name }.copy(
                name = name,
                inputs = inputsList.map { Input(it.name, it.lowerBound, it.upperBound, it.currentValue) },
                outputs = outputsList.map { Output(it.name) },
                description = description
        )
    }

    private fun buildNameChangeMessage(changed: Boolean, oldName: String, newName: String) = "Name change request from $oldName to $newName ${if (changed) "succeed" else "failed"}"
    private fun buildUnregisterMessage(result : Boolean) = "Unregister ${if(result) "success" else "failed"}"
    private fun buildUpdateMessage(updated: Boolean) = "Configuration update request ${if (updated) "succeed" else "failed, there is no existing setup."}"
    private fun buildSimulationUpdateMessage(newNode: Simulation, updated: Boolean) = "Simulation updated with inputs: ${newNode.inputs} outputs: ${newNode.outputs} ${if(updated)"succeed" else "failed"}"
    private fun buildStartIssuesMessage(issues: List<String>) = "Optimization cannot start: ${issues.joinToString(", ")}"
    private fun buildRunNotFoundMessage(request: ResultRequestDTO) = "Requested run ID ${request.runID} is not available"
    private fun buildAutoSetupMessage(autoSetupResult: Boolean) = "Auto setup ${if (autoSetupResult) "succeed" else "failed"}"
    private fun buildStopMessage(stopped: Boolean) = "Optimization stop order ${if (stopped) "accepted" else "rejected"}"
}
