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
                if(value.hasReadyNotification()) {
                    readyBlocker.complete(Unit)
                    return@cancelOnException
                }
            }

            override fun onError(t: Throwable) {
                t.printStackTrace()
                parentJob.cancel()
            }

            override fun onCompleted() { }
        })
        readyBlocker.await()

        val changeRequest = SimulationNodeChangeCommandDTO.newBuilder()
                .setName("asdf")
                .setNewNode(SimulationNodeChangeCommandDTO.CompleteSimulationNode.newBuilder()
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
                )
                .build()

        val response = doSingle(service::upsertSimulationNode, changeRequest)

        // assert
        val check = doSingle(service::requestSimulationNode, SimulationNodeStatusQueryDTO.newBuilder().setName("asdf").build())

        assertThat(check).isEqualTo(SimulationNodeResponseDTO.newBuilder()
                .setName("asdf")
                .setAutoImport(true)
                .setMappingTable(VariableMapping.newBuilder()
                        .putInputs("x1", "x1")
                        .putOutputs("f1", "f1")
                        .build()
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
                            if(iterationNo <= 5) {
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
                            }
                            if(iterationNo == 5){
                                fifthIteration.complete(Unit)
                            }
                            else if (iterationNo >= 5){
                                val response = ErrorResponseDTO.newBuilder().setMessage("already evaluated 5 iterations!").build()
                                doSingle(service::offerErrorResult)(response)
                            }
                            iterationNo += 1;
                        }
                        optimizerRequest.hasNodeStatusRequest() -> {
                            doSingle(service::offerSimulationConfig)(SimulationNodeResponseDTO.newBuilder()
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
                val x = 4;
//                TODO("Not yet implemented")
            }
        })
        readyBlocker.await()

        val changeRequest = SimulationNodeChangeCommandDTO.newBuilder()
                .setName("asdf")
                .setNewNode(SimulationNodeChangeCommandDTO.CompleteSimulationNode.newBuilder()
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
                )
                .build()

        doSingle(service::upsertSimulationNode)(changeRequest)

        //act
        doSingle(service::startOptimization)(StartOptimizationCommandDTO.newBuilder().build())
        fifthIteration.await()
        val run = doSingle(service::stopOptimization)(StopOptimizationCommandDTO.newBuilder().build())

        //assert
        val responseDTO = doSingle(service::requestRunResult)(OptimizationResultsQueryDTO.newBuilder().setRunID(run.runID).build())
        val results = responseDTO.result

        assertThat(results).isNotNull()
        assertThat(results.pointsList.toList()).hasSize(5)
        assertThat(results.frontierList.toList()).hasSize(1)
        assertThat(results.pointsList.toList()).contains(results.frontierList.single())
    }
}

private sealed class ResponseState<out R> {
    object NoValue: ResponseState<Nothing>()
    data class Failure(val throwable: Throwable): ResponseState<Nothing>()
    data class Result<R>(val result: Any?): ResponseState<R>()
}

fun <M, R> doSingle(func: KFunction2<M, StreamObserver<R>, Unit>): suspend (request: M) -> R = { request: M -> doSingle(func, request) }
suspend fun <M, R> doSingle(func: KFunction2<M, StreamObserver<R>, Unit>, request: M): R {
    val source = RuntimeException("error in call to ${func.name} with $request")

    return suspendCoroutine<R> { continuation ->
        func(request, object: StreamObserver<R> {
            var result: ResponseState<R> = ResponseState.NoValue

            override fun onNext(value: R) {
                if(result != ResponseState.NoValue) {
                    continuation.resumeWithException(IllegalStateException("received 2 or more responses, now $value, previously $result"))
                }
                result = ResponseState.Result(value)
            }

            override fun onError(thrown: Throwable) {
                val wrapped = RuntimeException("server error in call to ${func.name}", thrown)
                if(result is ResponseState.Result){
                    thrown.addSuppressed(RuntimeException("rpc call completed previously with $result"))
                }
                source.initCause(wrapped)
                continuation.resumeWithException(source)
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
}

private fun <R> CoroutineScope.cancelOnException(block: () -> R): Unit {
    try {
        block()
    }
    catch(ex: Throwable){
        coroutineContext[Job]?.cancel()
                ?: ex.addSuppressed(RuntimeException("exception did not cancel any coroutines (when it probably should have)"))

        RuntimeException("client-side exception cancelled the tests", ex).printStackTrace()
    }
}