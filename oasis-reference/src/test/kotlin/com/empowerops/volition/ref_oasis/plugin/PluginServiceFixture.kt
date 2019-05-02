//package com.empowerops.volition.ref_oasis.plugin
//
//import com.empowerops.volition.dto.ErrorResponseDTO
//import com.empowerops.volition.dto.NodeStatusCommandOrResponseDTO
//import com.empowerops.volition.dto.RequestQueryDTO
//import com.empowerops.volition.dto.SimulationResponseDTO
//import com.empowerops.volition.ref_oasis.front_end.ConsoleOutput
//import com.empowerops.volition.ref_oasis.model.*
//import com.google.common.eventbus.EventBus
//import com.nhaarman.mockitokotlin2.*
//import io.grpc.stub.StreamObserver
//import kotlinx.coroutines.GlobalScope
//import kotlinx.coroutines.channels.Channel
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.runBlocking
//import org.assertj.core.api.Assertions.assertThat
//import org.junit.jupiter.api.Test
//import java.lang.IllegalStateException
//import java.time.Duration
//
//internal class PluginServiceFixture {
//
//    private val responseDTO = NodeStatusCommandOrResponseDTO.newBuilder().apply {
//        name = "Node1"
//        addAllInputs(
//                listOf("x1", "x2").map {
//                    NodeStatusCommandOrResponseDTO.PrototypeInputParameter.newBuilder()
//                            .setName(it)
//                            .setLowerBound(0.0)
//                            .setUpperBound(5.0)
//                            .build()
//                })
//        addAllOutputs(
//                listOf("f1").map {
//                    NodeStatusCommandOrResponseDTO.PrototypeOutputParameter.newBuilder()
//                            .setName(it)
//                            .build()
//                }
//        )
//        description = "Test updated response - Node1"
//    }.build()
//
//    private val errorResponseDTO = ErrorResponseDTO.newBuilder().setName("Node1").setException("Exception Message").setMessage("Error Message").build()
//    private val simulationResponseDTO = SimulationResponseDTO.newBuilder().setName("Node1").putOutputVector("f1", 50.0).build()
//    private val inputVector = mapOf("x1" to 1.5, "x2" to 5.5)
//    private val testException = IllegalStateException("Test exception")
//
//    /**
//     * onUpdateNodeRequested
//     */
//    @Test
//    fun `when request update should send the update request and receive the update`() = runBlocking<Unit> {
//        //setup
//        val updateChannel = Channel<NodeStatusCommandOrResponseDTO>()
//        val inputStream = mock<StreamObserver<RequestQueryDTO>> {
//            on { it.onNext(any()) } doAnswer {
//                GlobalScope.launch { updateChannel.send(responseDTO) }
//                Unit
//            }
//        }
//        val modelService = mock<ModelService> {
//            on { simulations } doReturn listOf(Simulation("Node1", inputStream, update = updateChannel))
//            on { updateTimeout } doReturn 500000
//        }
//        val eventBus = EventBus()
//        val debugLogger = ConsoleOutput(eventBus)
//        val service = PluginService(modelService, debugLogger, eventBus)
//
//        //act
//        service.onUpdateNodeRequested(SimulationUpdateRequestedEvent("Node1")).join()
//
//
//        //assert
//        verify(inputStream, times(1)).onNext(check {
//            assertThat(it.hasNodeStatusRequest()).isTrue()
//            assertThat(it.nodeStatusRequest.name).isEqualTo("Node1")
//        })
//
//        verify(modelService, times(1)).updateSimAndConfiguration(check<Simulation> {
//            assertThat(it.inputs).isEqualTo(listOf("x1", "x2").map { name -> Input(name, 0.0, 5.0, 0.0) })
//            assertThat(it.outputs).isEqualTo(listOf("f1").map { name -> Output(name) })
//            assertThat(it.description).isEqualTo("Test updated response - Node1")
//        })
//
//        assertThat(debugLogger.log).isEmpty()
//    }
//
//    @Test
//    fun `when request update should send the update but running into an error request and receive the error`() = runBlocking<Unit> {
//        //setup
//        val errorChannel = Channel<ErrorResponseDTO>()
//        val inputStream = mock<StreamObserver<RequestQueryDTO>> {
//            on { it.onNext(any()) } doAnswer {
//                GlobalScope.launch { errorChannel.send(errorResponseDTO) }
//                Unit
//            }
//        }
//        val modelService = mock<ModelService> {
//            on { simulations } doReturn listOf(Simulation("Node1", inputStream, error = errorChannel))
//            on { updateTimeout } doReturn 500000
//        }
//        val eventBus = EventBus()
//        val debugLogger = ConsoleOutput(eventBus)
//        val service = PluginService(modelService, debugLogger, eventBus)
//
//        //act
//        service.onUpdateNodeRequested(SimulationUpdateRequestedEvent("Node1")).join()
//
//        //assert
//        verify(inputStream, times(1)).onNext(check {
//            assertThat(it.hasNodeStatusRequest()).isTrue()
//            assertThat(it.nodeStatusRequest.name).isEqualTo("Node1")
//        })
//
//        verify(modelService, never()).updateSimAndConfiguration(any<Simulation>())
//        with(debugLogger.log) {
//            assertThat(size).isEqualTo(1)
//            assertThat(single().sender).isEqualTo(errorResponseDTO.name)
//            assertThat(single().message).isEqualTo("Error update simulation ${errorResponseDTO.name} due to ${errorResponseDTO.message} :\n${errorResponseDTO.exception}")
//        }
//    }
//
//    @Test
//    fun `when request update should send the update but timedout and receive the error`() = runBlocking<Unit> {
//        //setup
//        val inputStream = mock<StreamObserver<RequestQueryDTO>>()
//        val modelService = mock<ModelService> {
//            on { simulations } doReturn listOf(Simulation("Node1", inputStream))
//            on { updateTimeout } doReturn 50//in ms
//        }
//        val eventBus = EventBus()
//        val debugLogger = ConsoleOutput(eventBus)
//        val service = PluginService(modelService, debugLogger, eventBus)
//
//        //act
//        service.onUpdateNodeRequested(SimulationUpdateRequestedEvent("Node1")).join()
//
//        //assert
//        verify(inputStream, times(1)).onNext(check {
//            assertThat(it.hasNodeStatusRequest()).isTrue()
//            assertThat(it.nodeStatusRequest.name).isEqualTo("Node1")
//        })
//
//        verify(modelService, never()).updateSimAndConfiguration(any<Simulation>())
//        with(debugLogger.log) {
//            assertThat(size).isEqualTo(1)
//            assertThat(single().sender).isEqualTo("Optimizer")
//            assertThat(single().message).isEqualTo("Update simulation timeout. Please check simulation is registered and responsive.")
//        }
//    }
//
//    @Test
//    fun `when request update but encounter an exception should log the result`() = runBlocking<Unit> {
//        //setup
//        val inputStream = mock<StreamObserver<RequestQueryDTO>> {
//            on { onNext(any()) } doAnswer { throw testException }
//        }
//        val modelService = mock<ModelService> {
//            on { simulations } doReturn listOf(Simulation("Node1", inputStream))
//            on { updateTimeout } doReturn 500000//in ms
//        }
//        val eventBus = EventBus()
//        val debugLogger = ConsoleOutput(eventBus)
//        val service = PluginService(modelService, debugLogger, eventBus)
//
//        //act
//        service.onUpdateNodeRequested(SimulationUpdateRequestedEvent("Node1")).join()
//
//        //assert
//        verify(modelService, never()).updateSimAndConfiguration(any<Simulation>())
//        with(debugLogger.log) {
//            assertThat(size).isEqualTo(1)
//            assertThat(single().sender).isEqualTo("Optimizer")
//            assertThat(single().message).isEqualTo("Unexpected error happened when update simulation ${responseDTO.name} failed. Please check simulation is registered and responsive. Cause:\n" +
//                    "$testException")
//        }
//    }
//
//    /**
//     * notifyStart/Stop
//     */
//    @Test
//    fun `when notifying start should send the start message to all the registered`() {
//
//    }
//
//    @Test
//    fun `when notifying stop should send the start message to all the registered`() {
//
//    }
//
//    @Test
//    fun `when notifying start but failed should handle the error`() {
//
//    }
//
//    @Test
//    fun `when notifying stop but failed should handle the error`() {
//
//    }
//
//    /**
//     * Evaluate async
//     */
//    @Test
//    fun `when evaluate async succeed should add the result`() = runBlocking<Unit> {
//        //setup
//        val outputChannel = Channel<SimulationResponseDTO>()
//        val inputStream = mock<StreamObserver<RequestQueryDTO>> {
//            on { it.onNext(any()) } doAnswer {
//                GlobalScope.launch { outputChannel.send(simulationResponseDTO) }
//                Unit
//            }
//        }
//        val modelService = mock<ModelService> {
//            on { simulations } doReturn listOf(Simulation("Node1", inputStream, output = outputChannel))
//        }
//        val service = PluginService(modelService, mock(), mock())
//
//        //act
//        val result = service.evaluateAsync(Proxy("Node1"), inputVector, ForceStopSignal("Node1")).await()
//
//        //assert
//        verify(inputStream, times(1)).onNext(check {
//            assertThat(it.hasEvaluationRequest()).isTrue()
//            assertThat(it.evaluationRequest.name).isEqualTo("Node1")
//            assertThat(it.evaluationRequest.inputVectorMap).isEqualTo(inputVector)
//        })
//        assertThat(result).isEqualTo(EvaluationResult.Success("Node1", inputVector, simulationResponseDTO.outputVectorMap))
//    }
//
//    @Test
//    fun `when evaluate async failed should add the error result`() = runBlocking<Unit> {
//        //setup
//        val errorChannel = Channel<ErrorResponseDTO>()
//        val inputStream = mock<StreamObserver<RequestQueryDTO>> {
//            on { it.onNext(any()) } doAnswer {
//                GlobalScope.launch { errorChannel.send(errorResponseDTO) }
//                Unit
//            }
//        }
//        val modelService = mock<ModelService> {
//            on { simulations } doReturn listOf(Simulation("Node1", inputStream, error = errorChannel))
//        }
//        val service = PluginService(modelService, mock(), mock())
//
//        //act
//        val result = service.evaluateAsync(Proxy("Node1"), inputVector, ForceStopSignal("Node1")).await()
//
//        //assert
//        verify(inputStream, times(1)).onNext(check {
//            assertThat(it.hasEvaluationRequest()).isTrue()
//            assertThat(it.evaluationRequest.name).isEqualTo("Node1")
//            assertThat(it.evaluationRequest.inputVectorMap).isEqualTo(inputVector)
//        })
//        assertThat(result).isEqualTo(EvaluationResult.Failed("Node1", inputVector, errorResponseDTO.exception))
//    }
//
//    @Test
//    fun `when evaluate async timedout should add the time out result`() = runBlocking<Unit> {
//        //setup
//        val inputStream = mock<StreamObserver<RequestQueryDTO>>()
//        val modelService = mock<ModelService> {
//            on { simulations } doReturn listOf(Simulation("Node1", inputStream))
//        }
//        val service = PluginService(modelService, mock(), mock())
//
//        //act
//        val result = service.evaluateAsync(Proxy("Node1", timeOut = Duration.ofMillis(50)), inputVector, ForceStopSignal("Node1")).await()
//
//        //assert
//        verify(inputStream, times(1)).onNext(check {
//            assertThat(it.hasEvaluationRequest()).isTrue()
//            assertThat(it.evaluationRequest.name).isEqualTo("Node1")
//            assertThat(it.evaluationRequest.inputVectorMap).isEqualTo(inputVector)
//        })
//        assertThat(result).isEqualTo(EvaluationResult.TimeOut("Node1", inputVector))
//    }
//
//    @Test
//    fun `when evaluate async encounter an unknown error should add the error result`() = runBlocking<Unit> {
//        //setup
//        val inputStream = mock<StreamObserver<RequestQueryDTO>> {
//            on { onNext(any()) } doAnswer { throw testException }
//        }
//        val modelService = mock<ModelService> {
//            on { simulations } doReturn listOf(Simulation("Node1", inputStream))
//        }
//        val service = PluginService(modelService, mock(), mock())
//
//        //act
//        val result = service.evaluateAsync(Proxy("Node1"), inputVector, ForceStopSignal("Node1")).await()
//
//        //assert
//        verify(inputStream, times(1)).onNext(check {
//            assertThat(it.hasEvaluationRequest()).isTrue()
//            assertThat(it.evaluationRequest.name).isEqualTo("Node1")
//            assertThat(it.evaluationRequest.inputVectorMap).isEqualTo(inputVector)
//        })
//        assertThat(result).isEqualTo(EvaluationResult.Error("Node1", inputVector, "Unexpected error happened when try to evaluate $inputVector though simulation Node1. Cause: $testException"))
//    }
//
//
//    @Test
//    fun `when evaluate async force stopped should return force stop result`() = runBlocking<Unit> {
//        //setup
//        val inputStream = mock<StreamObserver<RequestQueryDTO>>()
//        val modelService = mock<ModelService> {
//            on { simulations } doReturn listOf(Simulation("Node1", inputStream))
//        }
//        val service = PluginService(modelService, mock(), mock())
//        val forceStopSignal = ForceStopSignal("Node1")
//
//        //act
//        val deferredResult = service.evaluateAsync(Proxy("Node1"), inputVector, forceStopSignal)
//        forceStopSignal.completableDeferred.complete(Unit)
//        val result = deferredResult.await()
//
//        //assert
//        verify(inputStream, times(1)).onNext(check {
//            assertThat(it.hasEvaluationRequest()).isTrue()
//            assertThat(it.evaluationRequest.name).isEqualTo("Node1")
//            assertThat(it.evaluationRequest.inputVectorMap).isEqualTo(inputVector)
//        })
//        assertThat(result).isEqualTo(EvaluationResult.Terminated("Node1", inputVector, "Evaluation is terminated during evaluation"))
//    }
//
//    /**
//     * Cancellation
//     */
//    @Test
//    fun `when cancel and encounter an error should return error result`() = runBlocking<Unit> {
//        //setup
//        val errorChannel = Channel<ErrorResponseDTO>()
//        val inputStream = mock<StreamObserver<RequestQueryDTO>> {
//            on { it.onNext(any()) } doAnswer {
//                GlobalScope.launch { errorChannel.send(errorResponseDTO) }
//                Unit
//            }
//        }
//        val modelService = mock<ModelService> {
//            on { simulations } doReturn listOf(Simulation("Node1", inputStream, error = errorChannel))
//        }
//        val service = PluginService(modelService, mock(), mock())
//
//        //act
//        val result = service.cancelCurrentEvaluationAsync(Proxy("Node1"), ForceStopSignal("Node1")).await()
//
//        //verify
//        verify(inputStream, times(1)).onNext(check {
//            assertThat(it.hasCancelRequest()).isTrue()
//            assertThat(it.cancelRequest.name).isEqualTo("Node1")
//        })
//        assertThat(result).isEqualTo(CancelResult.CancelFailed("Node1", errorResponseDTO.exception))
//    }
//
//    @Test
//    fun `when cancel and succeed should return cancel result2`() = runBlocking<Unit> {
//        //setup
//        val outputChannel = Channel<SimulationResponseDTO>()
//        val inputStream = mock<StreamObserver<RequestQueryDTO>> {
//            on { it.onNext(any()) } doAnswer {
//                GlobalScope.launch { outputChannel.send(simulationResponseDTO) }
//                Unit
//            }
//        }
//        val modelService = mock<ModelService> {
//            on { simulations } doReturn listOf(Simulation("Node1", inputStream, output = outputChannel))
//        }
//        val service = PluginService(modelService, mock(), mock())
//
//        //act
//        val result = service.cancelCurrentEvaluationAsync(Proxy("Node1"), ForceStopSignal("Node1")).await()
//
//        //verify
//        verify(inputStream, times(1)).onNext(check {
//            assertThat(it.hasCancelRequest()).isTrue()
//            assertThat(it.cancelRequest.name).isEqualTo("Node1")
//        })
//        assertThat(result).isEqualTo(CancelResult.Canceled("Node1"))
//    }
//
//    @Test
//    fun `when cancel and force stopped should return force stop result`() = runBlocking<Unit> {
//        //setup
//        val errorChannel = Channel<ErrorResponseDTO>()
//        val inputStream = mock<StreamObserver<RequestQueryDTO>>()
//        val modelService = mock<ModelService> {
//            on { simulations } doReturn listOf(Simulation("Node1", inputStream, error = errorChannel))
//        }
//        val service = PluginService(modelService, mock(), mock())
//
//        //act
//        val forceStopSignal = ForceStopSignal("Node1")
//        val deferredResult = service.cancelCurrentEvaluationAsync(Proxy("Node1"), forceStopSignal)
//        forceStopSignal.completableDeferred.complete(Unit)
//        val result = deferredResult.await()
//
//        //verify
//        verify(inputStream, times(1)).onNext(check {
//            assertThat(it.hasCancelRequest()).isTrue()
//            assertThat(it.cancelRequest.name).isEqualTo("Node1")
//        })
//        assertThat(result).isEqualTo(CancelResult.CancelTerminated("Node1", "Cancellation is terminated"))
//    }
//}