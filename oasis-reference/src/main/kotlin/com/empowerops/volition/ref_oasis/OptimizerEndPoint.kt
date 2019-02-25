package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.selects.select
import org.funktionale.either.Either
import sun.reflect.generics.reflectiveObjects.NotImplementedException
import java.time.Duration

sealed class SimResult {
    data class Success(val name: String, val result: Map<String, Double>) : SimResult()
    data class TimeOut(val name: String) : SimResult()
    data class TimeOutFailure(val name: String, val exception: String) : SimResult()
    data class Failure(val name: String, val exception: String) : SimResult()
}

class OptimizerEndpoint(
        private val modelService: ModelService
) : OptimizerGrpc.OptimizerImplBase() {
    var state = State.Idle

    enum class State {
        Idle,
        Running
    }

    override fun changeNodeName(request: NodeNameChangeCommandDTO, responseObserver: StreamObserver<NodeNameChangeResponseDTO>) = responseObserver.consume {
        val newName = request.newName
        val newNameSimulation = modelService.simByName[newName]
        val oldName = request.oldName
        val target = modelService.simByName[oldName]
        val changed = if (newNameSimulation == null && target != null) {
            modelService.renameSim(target, newName, oldName)
            true
        } else {
            false
        }

        NodeNameChangeResponseDTO.newBuilder().setChanged(changed).build()
    }

    override fun register(request: RegistrationCommandDTO, responseObserver: StreamObserver<OASISQueryDTO>) {
        if (request.name in modelService.simByName.keys) {
            return
        }
        modelService.addNewSim(Simulation(request.name, emptyList(), emptyList(), "", responseObserver, Channel(RENDEZVOUS), Channel(RENDEZVOUS), Channel(RENDEZVOUS)))
        modelService.updateStatusMessage("${request.name} registered")
    }

    fun unregisterAll() {
        throw NotImplementedException()
    }

    override fun startOptimization(request: StartOptimizationCommandDTO, responseObserver: StreamObserver<StartOptimizationResponseDTO>) = responseObserver.consume {
        GlobalScope.async { startOptimization(RandomNumberOptimizer()) }
        StartOptimizationResponseDTO.newBuilder().setMessage("Started").setStarted(true).build()
    }


    suspend fun startOptimization(optimizer: RandomNumberOptimizer) {
        state = State.Running

        for (simName in modelService.simByName.keys) {
            syncConfigFor(simName)
        }

        modelService.updateStatusMessage("Evaluating...")

        while (state == State.Running) {
            var toolNumber = 1
            for ((simName, sim) in modelService.simByName) {
                modelService.updateStatusMessage("Evaluating: $simName ($toolNumber/${modelService.simByName.keys.size})")
                val inputVector = optimizer.generateInputs(sim.inputs)
                val message = OASISQueryDTO.newBuilder()
                        .setEvaluationRequest(OASISQueryDTO.SimulationEvaluationRequest.newBuilder()
                                .setName(simName)
                                .putAllInputVector(inputVector)
                        )
                        .build()

                sim.input.onNext(message)
                val result = select<SimResult> {
                    sim.output.onReceive { SimResult.Success(it.name, it.outputVectorMap) }
                    sim.error.onReceive { SimResult.Failure(it.name, it.exception) }
                    if (sim.timeOut != null) {
                        onTimeout(sim.timeOut.toMillis()) {
                            //Timed out
                            val message = OASISQueryDTO.newBuilder().setCancelRequest(
                                    OASISQueryDTO.SimulationCancelRequest.newBuilder().setName(simName)
                            ).build()
                            sim.input.onNext(message)
                            select {
                                sim.output.onReceive { SimResult.TimeOut(it.name) }
                                sim.error.onReceive { SimResult.TimeOutFailure(it.name, it.exception) }
                            }
                        }
                    }
                }

                val resultMessage = when (result) {
                    is SimResult.Success -> {
                        Result(result.name, "Success", inputVector.toString(), result.result.toString())
                    }
                    is SimResult.Failure -> {
                        Result(result.name, "Error", inputVector.toString(), "Failure: \n${result.exception}")
                    }
                    is SimResult.TimeOut -> {
                        Result(result.name, "Canceled", inputVector.toString(), "Timeout")
                    }
                    is SimResult.TimeOutFailure -> {
                        Result(result.name, "Cancel Failed", inputVector.toString(), "Cancellation failed:\n${result.exception}")
                    }
                }
                modelService.addResult(resultMessage)
                toolNumber++
            }
        }
        modelService.updateStatusMessage("Idle")

    }


    suspend fun stopOptimization() : Boolean {
        state = State.Idle
        return true
    }

    suspend fun syncAll() {
        for (simName in modelService.simByName.keys) {
            syncConfigFor(simName)
        }
    }

    suspend fun updateNode(simName: String) {
        syncConfigFor(simName)

    }

    suspend fun cancelAll() {
        for ((name, sim) in modelService.simByName) {
            val message = OASISQueryDTO.newBuilder().setCancelRequest(
                    OASISQueryDTO.SimulationCancelRequest.newBuilder().setName(name)
            ).build()

            sim.input.onNext(message)
        }
    }

    suspend fun cancel(name: String) {
        val message = OASISQueryDTO.newBuilder().setCancelRequest(
                OASISQueryDTO.SimulationCancelRequest.newBuilder().setName(name)
        ).build()

        modelService.simByName.getValue(name).input.onNext(message)
    }

    fun disconnectAll() {
       throw NotImplementedException()
    }

    suspend fun cancelAndStop() {
        stopOptimization()
        for ((name, sim) in modelService.simByName) {
            val message = OASISQueryDTO.newBuilder().setCancelRequest(
                    OASISQueryDTO.SimulationCancelRequest.newBuilder().setName(name)
            ).build()
            sim.input.onNext(message)
        }
    }

    private suspend fun syncConfigFor(simName: String) {
        val sim = modelService.simByName.getValue(simName)
        val message = OASISQueryDTO.newBuilder().setNodeStatusRequest(
                OASISQueryDTO.NodeStatusUpdateRequest.newBuilder().setName(simName)
        ).build()

        sim.input.onNext(message)

        val result = select<Either<Simulation, Message>> {
            sim.update.onReceive { Either.Left(updateFromResponse(it)) }
            sim.error.onReceive {
                Either.Right(Message(it.name, "Error update simulation $simName due to ${it.message} :\n${it.exception}"))
            }
            onTimeout(Duration.ofSeconds(5).toMillis()) {
                Either.Right(Message(simName, "Update simulation timeout. Please check simulation is registered and reponsive."))
            }
        }

        if (result.isLeft()) {
            modelService.updateSim(result.left().get())
        } else {
            modelService.addMessage(result.right().get())
        }

    }

    override fun stopOptimization(request: StopOptimizationCommandDTO, responseObserver: StreamObserver<StopOptimizationResponseDTO>) = responseObserver.consume {
        GlobalScope.async{ stopOptimization() }
        StopOptimizationResponseDTO.newBuilder().setMessage("Stop acknowledged").setStopped(true).build()
    }

    override fun offerSimulationResult(request: SimulationResponseDTO, responseObserver: StreamObserver<SimulationResultConfirmDTO>) = responseObserver.consume {
        val output = modelService.simByName[request.name]?.output
        if (output != null) {
            output.send(request)
        } else {
            throw IllegalStateException("no simulation '${request.name}' or buffer full")
        }
        SimulationResultConfirmDTO.newBuilder().build()
    }

    override fun offerErrorResult(request: ErrorResponseDTO, responseObserver: StreamObserver<ErrorConfirmDTO>) = responseObserver.consume {
        val error = modelService.simByName[request.name]?.error
        if (error != null) {
            error.send(request)
        } else {
            throw IllegalStateException("no simulation '${request.name}' or buffer full")
        }
        ErrorConfirmDTO.newBuilder().build()
    }

    override fun offerSimulationConfig(request: NodeStatusCommandOrResponseDTO, responseObserver: StreamObserver<NodeChangeConfirmDTO>) = responseObserver.consume {
        val update = modelService.simByName[request.name]?.update

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
        modelService.updateStatusMessage("${request.name} updated")
        NodeChangeConfirmDTO.newBuilder().setMessage("Simulation updated with inputs: ${newNode.inputs} outputs: ${newNode.outputs}").build()
    }


    override fun sendMessage(request: MessageCommandDTO, responseObserver: StreamObserver<MessageReponseDTO>) = responseObserver.consume {
        println("Message from [${request.name}] : ${request.message}")
        modelService.addMessage(Message(request.name, request.message))
        MessageReponseDTO.newBuilder().build()
    }

    override fun unregister(request: UnRegistrationRequestDTO, responseObserver: StreamObserver<UnRegistrationResponseDTO>) = responseObserver.consume {
        var unregistered = false
        if (request.name in modelService.simByName.keys) {
            val name = request.name
            modelService.removeSim(name)
            unregistered = true
        }
        UnRegistrationResponseDTO.newBuilder().setUnregistered(unregistered).build()
    }

    private fun updateFromResponse(request: NodeStatusCommandOrResponseDTO): Simulation {
        return modelService.simByName.getValue(request.name).copy(
                name = request.name,
                inputs = request.inputsList.map { Input(it.name, it.lowerBound, it.upperBound, it.currentValue) },
                outputs = request.outputsList.map { Output(it.name) },
                description = request.description
        )
    }

}
