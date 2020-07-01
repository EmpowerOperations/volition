package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import com.empowerops.volition.dto.OptimizerGeneratedQueryDTO.RequestCase
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


    //this was the very first sanity test I added.
    @Test fun `when running with --version should print version`() = runBlocking<Unit> {
        main(arrayOf("--version"))

        val str = consoleAltBytes.toString("utf-8")

        assertThat(str.trim()).isEqualTo("""
            Volition API 0.9.0
        """.trimIndent().replace("\n", System.lineSeparator()))
    }

    @Test fun `when running a single var single simulation optimization should optimize normally`() = runBlocking<Unit> {
        //act
        val readyBlocker = CompletableDeferred<Unit>()
        val fifthIteration = CompletableDeferred<Unit>()

        service.register(RegistrationCommandDTO.newBuilder().setName("asdf").build(), object: StreamObserver<OptimizerGeneratedQueryDTO>{
            val parentJob = coroutineContext[Job]!!

            var iterationNo = 1;

            override fun onNext(optimizerRequest: OptimizerGeneratedQueryDTO) = cancelOnException {
                runBlocking<Unit> {
                    val dc: Unit = when(optimizerRequest.requestCase!!) {
                        RequestCase.EVALUATION_REQUEST -> {
                            if(iterationNo <= 5) {
                                val inputVector = optimizerRequest.evaluationRequest!!.inputVectorMap.toMap()
                                sendAndAwaitResponse(service::offerEvaluationStatusMessage)(StatusMessageCommandDTO.newBuilder().setMessage(
                                        "evaluating $inputVector!"
                                ).build())

                                val result = inputVector.values.sumByDouble { it } / 2.0

                                val response = SimulationEvaluationCompletedResponseDTO.newBuilder()
                                        .setName("asdf")
                                        .putAllOutputVector(mapOf("f1" to result))
                                        .build()

                                sendAndAwaitResponse(service::offerSimulationResult)(response)
                            }
                            if(iterationNo == 5){
                                fifthIteration.complete(Unit)
                            }
                            else if (iterationNo >= 5){
                                val response = SimulationEvaluationErrorResponseDTO.newBuilder()
                                        .setMessage("already evaluated 5 iterations!")
                                        .build()
                                sendAndAwaitResponse(service::offerErrorResult)(response)
                            }
                            iterationNo += 1

                            Unit
                        }
                        RequestCase.CANCEL_REQUEST -> {
                            Unit //noop --this is a legal implementation in any situation for cancellation.
                        }
                        RequestCase.REGISTRATION_CONFIRMED -> {
                            readyBlocker.complete(Unit)
                            Unit
                        }
                        RequestCase.OPTIMIZATION_STARTED_NOTIFICATION -> {
                            Unit // noop,
                        }
                        RequestCase.OPTIMIZATION_FINISHED_NOTIFICATION -> {
                            Unit // noop
                        }
                        RequestCase.REQUEST_NOT_SET -> TODO("unknown request $optimizerRequest")
                    }
                }
            }

            override fun onError(t: Throwable) {
                t.printStackTrace()
                parentJob.cancel()
            }

            override fun onCompleted() {
                println("registration channel completed!")
            }
        })
        readyBlocker.await()


        val startOptimizationRequest = StartOptimizationCommandDTO.newBuilder()
                .setProblemDefinition(StartOptimizationCommandDTO.ProblemDefinition.newBuilder()
                        .addInputs(PrototypeInputParameter.newBuilder()
                                .setName("x1")
                                .setLowerBound(DoubleValue.of(1.0))
                                .setUpperBound(DoubleValue.of(5.0))
                                .build()
                        )
                        .addObjectives(PrototypeOutputParameter.newBuilder()
                                .setName("f1")
                                .build()
                        )
                        .build()
                )
                .addNodes(StartOptimizationCommandDTO.SimulationNode.newBuilder()
                        .setAutoMap(true)
                        .addInputs("x1")
                        .addOutputs("f1")
                        .build()
                )
                .build()

        //act

        sendAndAwaitResponse(service::startOptimization)(startOptimizationRequest)
        fifthIteration.await()
        val run = sendAndAwaitResponse(service::stopOptimization)(StopOptimizationCommandDTO.newBuilder().build())

        //assert
        val results = sendAndAwaitResponse(service::requestRunResult)(OptimizationResultsQueryDTO.newBuilder().setRunID(run.runID).build())

        assertThat(results.pointsList.toList()).hasSize(5)
        assertThat(results.frontierList.toList()).hasSize(1)
        assertThat(results.pointsList.toList()).contains(results.frontierList.single())

        //teardown
        sendAndAwaitResponse(service::unregister)(UnregistrationCommandDTO.newBuilder().setName("asdf").build())
    }

    @Test fun `when running an optimization with a constraint that constraint should be respected while optimizing`() = runBlocking<Unit> {
        //act
        val readyBlocker = CompletableDeferred<Unit>()
        val fifthIteration = CompletableDeferred<Unit>()

        service.register(RegistrationCommandDTO.newBuilder().setName("asdf").build(), object: StreamObserver<OptimizerGeneratedQueryDTO>{
            val parentJob = coroutineContext[Job]!!

            var iterationNo = 1;

            override fun onNext(optimizerRequest: OptimizerGeneratedQueryDTO) = cancelOnException {
                runBlocking<Unit> {
                    val dc: Unit = when(optimizerRequest.requestCase!!) {
                        RequestCase.EVALUATION_REQUEST -> {
                            if(iterationNo <= 5) {
                                val inputVector = optimizerRequest.evaluationRequest!!.inputVectorMap.toMap()
                                sendAndAwaitResponse(service::offerEvaluationStatusMessage)(StatusMessageCommandDTO.newBuilder().setMessage(
                                        "evaluating $inputVector!"
                                ).build())

                                val result = inputVector.values.sumByDouble { it } / 2.0

                                val response = SimulationEvaluationCompletedResponseDTO.newBuilder()
                                        .setName("asdf")
                                        .putAllOutputVector(mapOf("f1" to result))
                                        .build()

                                sendAndAwaitResponse(service::offerSimulationResult)(response)
                            }
                            if(iterationNo == 5){
                                fifthIteration.complete(Unit)
                            }
                            else if (iterationNo >= 5){
                                val response = SimulationEvaluationErrorResponseDTO.newBuilder().setMessage("already evaluated 5 iterations!").build()
                                sendAndAwaitResponse(service::offerErrorResult)(response)
                            }
                            iterationNo += 1

                            Unit
                        }
                        RequestCase.CANCEL_REQUEST -> {
                            Unit //noop --this is a legal implementation in any situation for cancellation.
                        }
                        RequestCase.REGISTRATION_CONFIRMED -> {
                            readyBlocker.complete(Unit)
                            Unit
                        }
                        RequestCase.OPTIMIZATION_STARTED_NOTIFICATION -> Unit // noop
                        RequestCase.OPTIMIZATION_FINISHED_NOTIFICATION -> Unit // noop
                        RequestCase.REQUEST_NOT_SET -> TODO("unknown request $optimizerRequest")
                    }
                }
            }

            override fun onError(t: Throwable) {
                t.printStackTrace()
                parentJob.cancel()
            }

            override fun onCompleted() {
                println("registration channel completed!")
            }
        })
        readyBlocker.await()

        val startRequest = StartOptimizationCommandDTO.newBuilder()
                .setProblemDefinition(StartOptimizationCommandDTO.ProblemDefinition.newBuilder()
                        .addAllInputs(listOf(
                                PrototypeInputParameter.newBuilder()
                                        .setName("x1")
                                        .setLowerBound(DoubleValue.of(1.0))
                                        .setUpperBound(DoubleValue.of(5.0))
                                        .build(),
                                PrototypeInputParameter.newBuilder()
                                        .setName("x2")
                                        .setLowerBound(DoubleValue.of(1.0))
                                        .setUpperBound(DoubleValue.of(5.0))
                                        .build()
                        ))
                        .addAllObjectives(listOf(
                                PrototypeOutputParameter.newBuilder()
                                        .setName("f1")
                                        .build()
                        ))
                        .addConstraints(BabelConstraint.newBuilder()
                                .setOutputName("c1")
                                .setBooleanExpression("x1 < x2")
                                .build())
                        .build()
                )
                .addNodes(StartOptimizationCommandDTO.SimulationNode.newBuilder()
                        .setAutoMap(true)
                        .addInputs("x1").addInputs("x2")
                        .addOutputs("f1")
                        .build()
                )
                .build()

        //act
        sendAndAwaitResponse(service::startOptimization)(startRequest)
        fifthIteration.await()
        val run = sendAndAwaitResponse(service::stopOptimization)(
                StopOptimizationCommandDTO.newBuilder().build()
        )

        //assert
        val results = sendAndAwaitResponse(service::requestRunResult)(OptimizationResultsQueryDTO.newBuilder().setRunID(run.runID).build())

        assertThat(results).isNotNull()
        assertThat(results.pointsList.toList()).hasSize(5)
        assertThat(results.frontierList.toList()).hasSize(1)
        assertThat(results.pointsList.toList()).contains(results.frontierList.single())

        //check that the constraint wasnt violated
        for(point in results.pointsList){
            assertThat(point.inputsList.first())
                    .describedAs("the value for x1 in the point $point")
                    .isLessThan(point.inputsList.last())
        }

        //teardown
        sendAndAwaitResponse(service::unregister)(UnregistrationCommandDTO.newBuilder().setName("asdf").build())
    }
}

private sealed class ResponseState<out R> {
    object NoValue: ResponseState<Nothing>()
    data class Failure(val throwable: Throwable): ResponseState<Nothing>()
    data class Result<R>(val result: Any?): ResponseState<R>()
}

// wrapper on other function to aid kotlins type-inference
fun <M, R> sendAndAwaitResponse(func: KFunction2<M, StreamObserver<R>, Unit>): suspend (request: M) -> R = { request: M -> sendAndAwaitResponse(func, request) }

// sends a message, and converts the response observer to kotlin suspension (and exception) semantics
// assumes a unary call (that is, a call with one value given to the response observer)
suspend fun <M, R> sendAndAwaitResponse(func: KFunction2<M, StreamObserver<R>, Unit>, request: M): R {
    val source = RuntimeException("server replied with error on call to ${func.name} with $request")

    return suspendCoroutine<R> { continuation ->
        func(request, object: StreamObserver<R> {
            var result: ResponseState<R> = ResponseState.NoValue

            override fun onNext(value: R) {
                if(result != ResponseState.NoValue) {
                    continuation.resumeWithException(IllegalStateException(
                            "received 2 or more responses, now $value, previously $result. Are you that ${func.name} is unary?"
                    ))
                }
                result = ResponseState.Result(value)
            }

            override fun onError(thrown: Throwable) {
                if(result is ResponseState.Result){
                    source.addSuppressed(RuntimeException("rpc call completed previously with $result"))
                }
                source.initCause(thrown)
                continuation.resumeWithException(source)
            }

            override fun onCompleted() {
                val result: Result<R> = when(val state = result){
                    is ResponseState.NoValue -> Result.failure(IllegalStateException("no response received"))
                    is ResponseState.Failure -> Result.failure(RuntimeException("exception generated by server", state.throwable))
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