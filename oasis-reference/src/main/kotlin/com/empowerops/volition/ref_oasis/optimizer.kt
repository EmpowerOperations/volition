package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import javafx.application.Application
import javafx.collections.ObservableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.random.Random

fun main(args: Array<String>) {
    Application.launch(OptimizerApp::class.java)
}

class OptimizerEndpoint(val list: ObservableList<String>,
                        val messages: ObservableList<Message>
) : OptimizerGrpc.OptimizerImplBase() {

    var simulationsByName: Map<String, Simulation> = emptyMap()
    var stopRequested = false

    data class Input(
            val name: String,
            val lowerBound: Double,
            val upperBound: Double,
            val currentValue: Double
    )

    data class Output(
            val name: String
    )

    data class Message(
            val receiveTime: LocalDateTime,
            val sender: String,
            val message: String
    )

    data class Simulation(
            val inputs: List<Input>,
            val outputs: List<Output>,
            val input: StreamObserver<OASISQueryDTO>,
            val output: Channel<SimulationResponseDTO>,
            val update: Channel<NodeStatusCommandOrResponseDTO>,
            val error: Channel<SimulationErrorResponseDTO>
    )

    override fun changeNodeName(request: NodeNameChangeCommandDTO, responseObserver: StreamObserver<NodeNameChangeResponseDTO>) = responseObserver.consume {
        withContext(Dispatchers.JavaFx) {
            val newNameSimulation = simulationsByName[request.newName]
            val target = simulationsByName[request.oldName]
            if (newNameSimulation == null && target != null) {
                simulationsByName += request.newName to target
                simulationsByName -= request.oldName

                list.remove(request.oldName)
                list.add(request.newName)

                NodeNameChangeResponseDTO.newBuilder().setChanged(true).build()
            } else {
                NodeNameChangeResponseDTO.newBuilder().setChanged(false).build()
            }
        }
    }

    override fun register(request: RegistrationCommandDTO, responseObserver: StreamObserver<OASISQueryDTO>) {
        GlobalScope.launch(Dispatchers.JavaFx) {
            if (request.name in simulationsByName.keys) {
                return@launch
            }
            list.add(request.name)
            simulationsByName += request.name to Simulation(emptyList(), emptyList(), responseObserver, Channel(1), Channel(1), Channel(1))
        }
    }

    fun unregisterAll() {
        GlobalScope.launch(Dispatchers.JavaFx) {
            list.clear()
            simulationsByName.values.forEach { sim ->
                sim.input.onCompleted()
                sim.output.close()
            }
            simulationsByName = emptyMap()
        }
    }

    override fun startOptimization(request: StartOptimizationCommandDTO, responseObserver: StreamObserver<StartOptimizationResponseDTO>) = responseObserver.consume {
        startOptimization()
        StartOptimizationResponseDTO.newBuilder().setMessage("Started").setStarted(true).build()
    }

    sealed class SimResult(open val name: String, open val result: Map<String, Double>) {
        data class Success(override val name: String, override val result: Map<String, Double>) : SimResult(name, result)
        data class Failure(override val name: String, override val result: Map<String, Double>, val exception: String) : SimResult(name, result)
    }


    fun startOptimization() {
        stopRequested = false

        GlobalScope.launch {
            for (simName in simulationsByName.keys) {
                syncConfigFor(simName)
            }

            while (canContinue()) {
                for ((simName, sim) in simulationsByName) {

                    val input = sim.inputs.associate {
                        it.name to
                                if (it.lowerBound == it.upperBound) {
                                    Random.nextDouble(0.0, 10.0)
                                } else {
                                    Random.nextDouble(it.lowerBound, it.upperBound)
                                }
                    }

                    val message = OASISQueryDTO.newBuilder()
                            .setEvaluationRequest(OASISQueryDTO.SimulationEvaluationRequest.newBuilder()
                                    .setName(simName)
                                    .putAllInputVector(input)
                            )
                            .build()

                    sim.input.onNext(message)
                    val result = select<SimResult> {
                        sim.output.onReceive { it -> SimResult.Success(it.name, it.outputVectorMap) }
                        sim.error.onReceive { it -> SimResult.Failure(it.name, it.outputVectorMap, it.exception) }
                    }
                }
            }
        }
    }

    fun stopOptimization() {
        stopRequested = true
    }

    fun syncAll() {
        GlobalScope.launch {
            for (simName in simulationsByName.keys) {
                syncConfigFor(simName)
            }
        }
    }

    fun cancelAll() {
        GlobalScope.launch {
            for ((name, sim) in simulationsByName) {
                val message = OASISQueryDTO.newBuilder().setCancelRequest(
                        OASISQueryDTO.SimulationCancelRequest.newBuilder().setName(name)
                ).build()
                sim.input.onNext(message)
            }
        }
    }

    fun disconnectAll() {
        GlobalScope.launch {
            for ((name, sim) in simulationsByName) {
                try {
                    sim.input.onCompleted()
                }
                catch (e : StatusRuntimeException){
                    println("Error when close input for:\n$e")
                }
            }
        }
    }

    fun cancelAndStop() {
        GlobalScope.launch {
            stopOptimization()
            for ((name, sim) in simulationsByName) {
                val message = OASISQueryDTO.newBuilder().setCancelRequest(
                        OASISQueryDTO.SimulationCancelRequest.newBuilder().setName(name)
                ).build()
                sim.input.onNext(message)
            }
        }
    }

    private suspend fun syncConfigFor(simName: String) {
        val sim = simulationsByName.getValue(simName)
        val message = OASISQueryDTO.newBuilder().setNodeStatusRequest(
                OASISQueryDTO.NodeStatusUpdateRequest.newBuilder().setName(simName)
        ).build()

        sim.input.onNext(message)
        val receive = sim.update.receive()
        updateFromResponse(receive)
    }

    private fun canContinue(): Boolean {
        return !stopRequested
    }


    override fun stopOptimization(request: StopOptimizationCommandDTO, responseObserver: StreamObserver<StopOptimizationResponseDTO>) = responseObserver.consume {
        stopOptimization()
        StopOptimizationResponseDTO.newBuilder().setMessage("Stopped").setStopped(true).build()
    }


    override fun offerSimulationResult(request: SimulationResponseDTO, responseObserver: StreamObserver<SimulationResultConfirmDTO>) = responseObserver.consume {
        val output = simulationsByName[request.name]?.output
        if (output != null) {
            output.offer(request)
        } else {
            throw IllegalStateException("no simulation '${request.name}' or buffer full")
        }
        SimulationResultConfirmDTO.newBuilder().build()
    }

    override fun offerErrorResult(request: SimulationErrorResponseDTO, responseObserver: StreamObserver<SimulationErrorConfrimDTO>) = responseObserver.consume {
        val error = simulationsByName[request.name]?.error
        if (error != null) {
            error.offer(request)
        } else {
            throw IllegalStateException("no simulation '${request.name}' or buffer full")
        }
        SimulationErrorConfrimDTO.newBuilder().build()
    }


    override fun offerSimulationConfig(request: NodeStatusCommandOrResponseDTO, responseObserver: StreamObserver<NodeChangeConfirmDTO>) = responseObserver.consume {
        val update = simulationsByName[request.name]?.update

        if (update != null) {
            update.offer(request)
        } else {
            throw IllegalStateException("no simulation '${request.name}' or buffer full")
        }
        NodeChangeConfirmDTO.newBuilder().build()
    }


    override fun updateNode(request: NodeStatusCommandOrResponseDTO, responseObserver: StreamObserver<NodeChangeConfirmDTO>) = responseObserver.consume {
        val newNode = updateFromResponse(request)

        NodeChangeConfirmDTO.newBuilder().setMessage("Node updated with inputs: ${newNode.inputs} outputs: ${newNode.outputs}").build()
    }

    override fun sendMessage(request: MessageCommandDTO, responseObserver: StreamObserver<MessageReponseDTO>) = responseObserver.consume {
        withContext(Dispatchers.JavaFx) {
            println("Message from [${request.name}] : ${request.message}")
            messages.add(Message(LocalDateTime.now(), request.name, request.message))
            MessageReponseDTO.newBuilder().build()
        }
    }

    override fun unregister(request: UnRegistrationRequestDTO, responseObserver: StreamObserver<UnRegistrationResponseDTO>) = responseObserver.consume {
        withContext(Dispatchers.JavaFx) {
            var unregistered = false
            if (request.name in simulationsByName.keys) {
                simulationsByName -= request.name
                list -= request.name
                unregistered = true
            }
            UnRegistrationResponseDTO.newBuilder().setUnregistered(unregistered).build()
        }
    }

    private fun updateFromResponse(request: NodeStatusCommandOrResponseDTO): Simulation {
        val existingNode = simulationsByName.getValue(request.name)

        val newNode = existingNode.copy(
                inputs = request.inputsList.map { Input(it.name, it.lowerBound, it.upperBound, it.currentValue) },
                outputs = request.outputsList.map { Output(it.name) }
        )

        simulationsByName += request.name to newNode
        return newNode
    }

}
