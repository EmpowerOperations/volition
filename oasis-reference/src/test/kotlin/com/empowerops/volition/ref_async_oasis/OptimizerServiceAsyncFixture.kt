package com.empowerops.volition.ref_async_oasis

import com.empowerops.volition.dto.*
import com.google.protobuf.Message
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OptimizerServiceAsyncFixture {

    @Test fun things() = runBlocking<Unit>{
        //setup
        startServerAsync()
        var messages: List<Message?> = emptyList()


        //act
        val channel = ManagedChannelBuilder.forTarget("127.0.0.1:5550").build()
        val client = OptimizerGrpc.newStub(channel)
        val client2 = OptimizerGrpc.newBlockingStub(channel)

        val registerRequest = RequestRegistrationCommandDTO.newBuilder().apply {
            name = "testing!"
        }.build()
        val stream = client.register(registerRequest)

        val update: NodeStatusCommandOrResponseDTO = NodeStatusCommandOrResponseDTO.newBuilder().apply {
            name = "testing!"
            addInputs(
                    NodeStatusCommandOrResponseDTO.PrototypeInputParameter.newBuilder().apply {
                        name = "x1"
                        lowerBound = -1.0
                        upperBound = +5.0
                    }.build()
            )
            addOutputs(
                    NodeStatusCommandOrResponseDTO.PrototypeOutputParameter.newBuilder().apply {
                        name = "f1"
                    }.build()
            )
        }.build()
        messages += client2.updateNode(update)

        val startRequest = StartOptimizationCommandDTO.newBuilder().apply {
            name = "testing!"
        }.build()
        messages += client2.startOptimization(startRequest)
        messages += stream.expect<RequestQueryDTO.SimulationEvaluationRequest>()
        val stopRequest = StopOptimizationCommandDTO.newBuilder().apply {
            name = "testing!"
            id = "1"
        }.build()
        messages += client2.stopOptimization(stopRequest)
        val evaluationResult = SimulationResponseDTO.newBuilder().apply {
            putOutputVector("f1", 42.0)
        }.build()
        messages += client2.offerSimulationResult(evaluationResult)

        messages += stream.receiveOrNull()


        //assert
        assertEquals(emptyList<Message>(), messages)
    }

    operator fun <T, B> B.invoke(config: B.() -> Unit): T where B: com.google.protobuf.GeneratedMessageV3.Builder<B> {

        TODO("THIS BUILDER PATTERN IS SO LAME")
//        config()
//        return build() as B
    }

    fun <T: Message> ReceiveChannel<RequestQueryDTO>.expect(): T{
        TODO()
    }

    private suspend fun OptimizerGrpc.OptimizerStub.register(request: RequestRegistrationCommandDTO): ReceiveChannel<RequestQueryDTO> {
        val result = Channel<RequestQueryDTO>(RENDEZVOUS)

        registerRequest(request, object: StreamObserver<RequestQueryDTO>{
            override fun onNext(value: RequestQueryDTO) = runBlocking { result.send(value) }
            override fun onError(t: Throwable?) { result.close(t) }
            override fun onCompleted() { result.close() }
        })

        return result
    }

    private fun startServerAsync(): Job = GlobalScope.launch {

        val logger = object : Logger {
            override fun log(message: String, sender: String) = System.err.println(message)
        }
        val endpoint = AsyncOptimizerEndpoint()
        val server = NettyServerBuilder
                .forPort(5550)
                .addService(ServerInterceptors.intercept(endpoint, LoggingInterceptor(logger)))
                .build()

        try {
            val service = OptimizerServiceAsync.createStartedAsync(endpoint, Channel())

            launch(Dispatchers.IO) { server.start() }

            service.awaitTermination()
        }
        finally {
            server.shutdown()
        }
    }
}