package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import io.grpc.*
import io.grpc.MethodDescriptor.MethodType.*
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

fun main(args: Array<String>){

    val server = ServerBuilder.forPort(5550)
            .addService(ServerInterceptors.intercept(OptimizerEndpoint(), LoggingInterceptor(System.out)))
            .build()

    server.start()

    println("reference optimizer is running")

    server.awaitTermination()
}

class OptimizerEndpoint: OptimizerGrpc.OptimizerImplBase() {

    data class Simulation(
            val inputs: List<String>,
            val input: StreamObserver<OASISQueryDTO>,
            val output: Channel<SimulationResponseDTO>
    )

    private var simulationsByName: Map<String, Simulation> = emptyMap()

    override fun register(request: RegistrationCommandDTO, responseObserver: StreamObserver<OASISQueryDTO>) {
        simulationsByName += request.name to Simulation(emptyList(), responseObserver, Channel(1))
    }

    override fun updateNode(request: NodeStatusCommandOrResponseDTO, responseObserver: StreamObserver<NodeChangeConfirmDTO>) = responseObserver.consume {
        val existingNode = simulationsByName.getValue(request.name)

        val newNode = existingNode.copy(
                inputs = request.inputsList.map { it.name }
        )

        simulationsByName += request.name to newNode

        NodeChangeConfirmDTO.newBuilder().build()
    }

    override fun startOptimization(request: StartOptimizationCommandDTO, responseObserver: StreamObserver<StartOptimizationResponseDTO>) = responseObserver.consume {

        var previousValue = 1.0

        GlobalScope.launch {
            for(iteration in 1 .. 5){
                for ((simName, sim) in simulationsByName) {
                    val input = sim.inputs.associate { it to previousValue++ }
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

        StartOptimizationResponseDTO.newBuilder().build()
    }

    override fun offerSimulationResult(request: SimulationResponseDTO, responseObserver: StreamObserver<SimulationResultConfirmDTO>) = responseObserver.consume {
        val output = simulationsByName[request.name]?.output
        if(output != null){
            output.offer(request)
        }
        else {
            throw IllegalStateException("no simulation '${request.name}' or buffer full")
        }
        SimulationResultConfirmDTO.newBuilder().build()
    }
}
