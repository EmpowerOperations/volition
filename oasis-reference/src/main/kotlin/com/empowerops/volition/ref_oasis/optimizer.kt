package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import io.grpc.stub.StreamObserver
import javafx.application.Application
import javafx.collections.ObservableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.random.Random

fun main(args: Array<String>) {
    Application.launch(OptimizerApp::class.java)
}

class OptimizerEndpoint(val list: ObservableList<String>,
                        val messages : ObservableList<Message>
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
            val update: Channel<NodeStatusCommandOrResponseDTO>
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
        GlobalScope.launch(Dispatchers.JavaFx){
            list.add(request.name)
        }
        simulationsByName += request.name to Simulation(emptyList(), emptyList(), responseObserver, Channel(1), Channel(1))

    }

    override fun startOptimization(request: StartOptimizationCommandDTO, responseObserver: StreamObserver<StartOptimizationResponseDTO>) = responseObserver.consume {
        startOptimization()
        StartOptimizationResponseDTO.newBuilder().setMessage("Started").setStarted(true).build()
    }

    fun startOptimization() {
        stopRequested = false

        GlobalScope.launch {
            for (simName in simulationsByName.keys) {
                syncConfigFor(simName)
            }

            while (canContinue()) {
                for ((simName, sim) in simulationsByName) {

                    val input = sim.inputs.associate { it.name to Random.nextDouble(it.lowerBound, it.upperBound) }
                    val message = OASISQueryDTO.newBuilder()
                            .setEvaluationRequest(OASISQueryDTO.SimulationEvaluationRequest.newBuilder()
                                    .setName(simName)
                                    .putAllInputVector(input)
                            )
                            .build()

                    sim.input.onNext(message)
                    val result = sim.output.receive()
                }
            }
        }
    }

    fun stopOptimization() {
        stopRequested = true
    }

    fun syncAll(){
        GlobalScope.launch {
            for (simName in simulationsByName.keys) {
                syncConfigFor(simName)
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
