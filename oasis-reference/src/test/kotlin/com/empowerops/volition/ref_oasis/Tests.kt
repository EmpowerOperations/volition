package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import com.google.protobuf.DoubleValue
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.IllegalStateException
import java.lang.RuntimeException
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.reflect.KFunction2

class Tests {

    init {
        System.setProperty("com.empowerops.volition.ref_oasis.useConsoleAlt", "true")
    }

    @Test fun `when running with --version should print version`() = runBlocking<Unit> {
        main(arrayOf("--version"))

        val str = consoleAltBytes.toString("utf-8")

        assertThat(str.trim()).isEqualTo("""
            Volition API 0.9.0
        """.trimIndent().replace("\n", System.lineSeparator()))
    }

    private lateinit var server: Job
    private lateinit var service: OptimizerGrpc.OptimizerStub

    @BeforeEach
    fun setupServer(){
        server = mainAsync(arrayOf())!!

        val channel = ManagedChannelBuilder
                .forAddress("localhost", 5550)
                .usePlaintext()
                .build()

        service = OptimizerGrpc.newStub(channel)
    }

    @AfterEach
    fun teardownServer() = runBlocking {
        val x = server
        server.cancelAndJoin()
        val y = 4;
    }

    @Test fun `when configuring simple optimization should do simple things`() = runBlocking<Unit> {

        //act
        val readyBlocker = CompletableDeferred<Unit>()
        service.register(RegistrationCommandDTO.newBuilder().setName("asdf").build(), object: StreamObserver<OptimizerGeneratedQueryDTO>{
            val parentJob = coroutineContext[Job]!!
            override fun onNext(value: OptimizerGeneratedQueryDTO) = cancelOnException {
                TODO()
                if(value.hasReadyNotification()) {
                    readyBlocker.complete(Unit)
                    return@cancelOnException
                }
                TODO()
            }

            override fun onError(t: Throwable) {
                t.printStackTrace()
                parentJob.cancel()
            }

            override fun onCompleted() { }
        })
        readyBlocker.await()

        val changeRequest = NodeChangeCommandDTO.newBuilder()
                .setName("asdf")
                .setAutoImport(true)
                .setMappingTable(VariableMapping.newBuilder()
                        .putInputs("x1", "x1")
                        .putOutputs("f1", "f1")
                )
                .addInputs(PrototypeInputParameter.newBuilder()
                        .setName("x1")
                        .setLowerBound(DoubleValue.of(1.0))
                        .setUpperBound(DoubleValue.of(5.0))
                        .build()
                )
                .addOutputs(PrototypeOutputParameter.newBuilder()
                        .setName("f1")
                        .build()
                )
                .build()

        val response = doSingle(service::upsertEvaluationNode, changeRequest)

        // assert
        val check = doSingle(service::requestEvaluationNode, NodeStatusQueryDTO.newBuilder().setName("asdf").build())

        assertThat(check).isEqualTo(NodeStatusResponseDTO.newBuilder()
                .setName("asdf")
                .setAutoImport(true)
                .setMappingTable(VariableMapping.newBuilder()
                        .putInputs("x1", "x1")
                        .putOutputs("f1", "f1")
                )
                .addInputs(PrototypeInputParameter.newBuilder()
                        .setName("x1")
                        .setLowerBound(DoubleValue.of(1.0))
                        .setUpperBound(DoubleValue.of(5.0))
                        .build()
                )
                .addOutputs(PrototypeOutputParameter.newBuilder()
                        .setName("f1")
                        .build()
                )
                .build()
        )
    }

    @Test fun `when running an optimization should optimize`() = runBlocking<Unit> {
        //act
        val readyBlocker = CompletableDeferred<Unit>()
        val fifthIteration = CompletableDeferred<Unit>()

        service.register(RegistrationCommandDTO.newBuilder().setName("asdf").build(), object: StreamObserver<OptimizerGeneratedQueryDTO>{
            val parentJob = coroutineContext[Job]!!

            var iterationNo = 1;

            override fun onNext(optimizerRequest: OptimizerGeneratedQueryDTO) = cancelOnException {
                runBlocking<Unit> {
                    if(optimizerRequest.hasReadyNotification()) {
                        readyBlocker.complete(Unit)
                        return@runBlocking
                    }

                    when {
                        optimizerRequest.hasEvaluationRequest() -> {
                            val inputVector = optimizerRequest.evaluationRequest!!.inputVectorMap.toMap()
                            doSingle(service::offerEvaluationStatusMessage)(MessageCommandDTO.newBuilder().setMessage(
                                    "evaluating $inputVector!"
                            ).build())
                            val result = inputVector.values.sumByDouble { it }
                            val response = SimulationResponseDTO.newBuilder()
                                    .setName("asdf")
                                    .putAllOutputVector(mapOf("f1" to result))
                                    .build()
                            doSingle(service::offerSimulationResult)(response)

                            if(iterationNo == 5){
                                fifthIteration.complete(Unit)
                            }
                            iterationNo += 1;
                        }
                        optimizerRequest.hasNodeStatusRequest() -> {
                            doSingle(service::offerSimulationConfig)(NodeStatusResponseDTO.newBuilder()
                                    .setName("asdf")
                                    .setAutoImport(true)
                                    .addInputs(PrototypeInputParameter.newBuilder()
                                            .setName("x1")
                                            .setLowerBound(DoubleValue.of(1.0))
                                            .setUpperBound(DoubleValue.of(5.0))
                                            .build()
                                    )
                                    .addOutputs(PrototypeOutputParameter.newBuilder()
                                            .setName("f1")
                                            .build()
                                    )
                                    .build()
                            )
                        }
                        optimizerRequest.hasCancelRequest() -> {
                            TODO()
                        }
                        else -> TODO("unhandled $optimizerRequest")
                    }
                }
            }

            override fun onError(t: Throwable) {
                t.printStackTrace()
                parentJob.cancel()
            }

            override fun onCompleted() = cancelOnException {
//                TODO("Not yet implemented")
            }
        })
        readyBlocker.await()

        val changeRequest = NodeChangeCommandDTO.newBuilder()
                .setName("asdf")
                .setAutoImport(true)
                .setMappingTable(VariableMapping.newBuilder()
                        .putInputs("x1", "x1")
                        .putOutputs("f1", "f1")
                )
                .addInputs(PrototypeInputParameter.newBuilder()
                        .setName("x1")
                        .setLowerBound(DoubleValue.of(1.0))
                        .setUpperBound(DoubleValue.of(5.0))
                        .build()
                )
                .addOutputs(PrototypeOutputParameter.newBuilder()
                        .setName("f1")
                        .build()
                )
                .build()

        doSingle(service::upsertEvaluationNode)(changeRequest)

        //act
        doSingle(service::startOptimization)(StartOptimizationCommandDTO.newBuilder().build())
        fifthIteration.await()
        val run = doSingle(service::stopOptimization)(StopOptimizationCommandDTO.newBuilder().build())

        //assert
        val results = doSingle(service::requestRunResult)(OptimizationResultsQueryDTO.newBuilder().setRunID(run.runID).build())

        assertThat(results).isEqualTo("asdf")
    }
}

private sealed class ResponseState<out R> {
    object NoValue: ResponseState<Nothing>()
    data class Failure(val throwable: Throwable): ResponseState<Nothing>()
    data class Result<R>(val result: Any?): ResponseState<R>()
}

fun <M, R> doSingle(func: KFunction2<M, StreamObserver<R>, Unit>): suspend (request: M) -> R = { request: M -> doSingle(func, request) }
suspend fun <M, R> doSingle(func: KFunction2<M, StreamObserver<R>, Unit>, request: M) = suspendCoroutine<R> { continuation ->
    val source = RuntimeException("error in call to ${func.name} with $request")
    func(request, object: StreamObserver<R> {
        var result: ResponseState<R> = ResponseState.NoValue

        override fun onNext(value: R) {
            if(result != ResponseState.NoValue) {
                continuation.resumeWithException(IllegalStateException("received 2 or more responses, now $value, previously $result"))
            }
            result = ResponseState.Result(value)
        }

        override fun onError(thrown: Throwable) {
            if(result is ResponseState.Result){
                thrown.addSuppressed(RuntimeException("rpc call completed previously with $result"))
            }
            source.initCause(thrown)
            continuation.resumeWithException(thrown)
        }

        override fun onCompleted() {
            val result: Result<R> = when(val state = result){
                is ResponseState.NoValue -> Result.failure(IllegalStateException("no response recevied"))
                is ResponseState.Failure -> Result.failure(RuntimeException("exception generated by Server", state.throwable))
                is ResponseState.Result -> Result.success(state.result as R)
            }

            continuation.resumeWith(result)
        }
    })
}

private fun <R> CoroutineScope.cancelOnException(block: () -> R): R {
    try {
        return block()
    }
    catch(ex: Throwable){
        coroutineContext[Job]?.cancel()
                ?: ex.addSuppressed(RuntimeException("exception did not cancel any coroutines (when it probably should have)"))

        throw ex
    }
}