package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import com.empowerops.volition.ref_oasis.front_end.ConsoleOutput
import com.empowerops.volition.ref_oasis.model.*
import com.empowerops.volition.ref_oasis.model.EvaluationResult.*
import com.empowerops.volition.ref_oasis.optimizer.*
import com.empowerops.volition.ref_oasis.plugin.PluginService
import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import com.nhaarman.mockitokotlin2.*
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.*

class SystemTest {
    private lateinit var logger: ConsoleOutput
    private lateinit var modelService: ModelService
    private lateinit var optimizerService: OptimizerService
    private lateinit var stateMachine: RunStateMachine
    private lateinit var eventBus: EventBus

    private val unregisterDTO = RequestUnRegistrationRequestDTO.newBuilder().setName("N1").build()
    private val registerDTO = RequestRegistrationCommandDTO.newBuilder().setName("N1").build()
    private val registerDTO2 = RequestRegistrationCommandDTO.newBuilder().setName("N2").build()
    val testStatusRequest = buildNodeStatusCommandDTO("N1", listOf("x1", "x2"), listOf("f1", "f2"))
    val testStatusRequest2 = buildNodeStatusCommandDTO("N1", listOf("x1", "x2", "x3"), listOf("f1"))
    val testStatusRequest3 = buildNodeStatusCommandDTO("N3", listOf("x1", "x2", "x3"), listOf("f1", "f2"))

    class ForwardingStream(val response: SendChannel<RequestQueryDTO>) : StreamObserver<RequestQueryDTO> {
        override fun onError(t: Throwable?) {
            response.close(t)
        }

        override fun onCompleted() {
            response.close()
        }

        override fun onNext(value: RequestQueryDTO) = runBlocking {
            response.send(value)
        }
    }

    private suspend fun create(): OptimizerEndpoint {
        eventBus = EventBus()
        logger = ConsoleOutput(eventBus)
        modelService = ModelService(eventBus, false)
        val pluginService = PluginService(modelService, logger, eventBus)
        val evaluationEngine = EvaluationEngine(modelService, eventBus, logger)
        val fixValueOptimizer = FixValueOptimizer(5.0)
        stateMachine = RunStateMachine()
        optimizerService = OptimizerService(
                eventBus,
                modelService,
                fixValueOptimizer,
                pluginService,
                stateMachine,
                evaluationEngine
        )
        val apiService = ApiService(modelService, stateMachine)
        runBlocking {
            stateMachine.initService(optimizerService)
        }
        return OptimizerEndpoint(apiService, modelService)
    }

    private fun List<Message>.toDebugString(): String = this.joinToString(separator = "\n") { with(it) { "$sender > $message" } }

    /**
     * Register/Unregister
     */
    @Test
    fun `when register new node should add the new node`() = runBlocking<Unit> {
        //setup
        val optimizerEndpoint = create()

        //act
        optimizerEndpoint.registerRequest(registerDTO, mock())

        //assert
        assertThat(logger.log.toDebugString()).isEqualTo("""
            |Optimizer Event > ${PluginRegisteredEvent(name = "N1")}
        """.trimMargin())
    }

    @Test
    fun `when unregister should remove the node`() = runBlocking<Unit> {
        //setup
        val optimizerEndpoint = create()

        //act
        optimizerEndpoint.registerRequest(registerDTO, mock())
        val unregisterResponseObserver = mock<StreamObserver<UnRegistrationResponseDTO>>()
        optimizerEndpoint.unregisterRequest(unregisterDTO, unregisterResponseObserver)

        //assert
        verify(unregisterResponseObserver, times(1)).onNext(check {
            assertThat(it.message).isEqualTo("Unregister success")
        })
        assertThat(logger.log.toDebugString()).isEqualTo("""
            |Optimizer Event > ${PluginRegisteredEvent(name = "N1")}
            |Optimizer Event > ${PluginUnRegisteredEvent(name = "N1")}
        """.trimMargin())
    }

    @Test
    fun `when register the node with duplicated name should reject`() = runBlocking<Unit> {
        //setup
        val optimizerEndpoint = create()

        //act
        optimizerEndpoint.registerRequest(registerDTO, mock())
        val responseObserver = mock<StreamObserver<RequestQueryDTO>>()
        optimizerEndpoint.registerRequest(registerDTO, responseObserver)

        //assert
        verify(responseObserver, times(1)).onError(check {
            assertThat(it.message).isEqualTo("ALREADY_EXISTS")
        })
        assertThat(logger.log.toDebugString()).isEqualTo("""
            |Optimizer Event > ${PluginRegisteredEvent(name = "N1")}
        """.trimMargin())
    }

    @Test
    fun `when unregister non exist should stop the call`() = runBlocking<Unit> {
        //setup
        val optimizerEndpoint = create()
        val responseObserver = mock<StreamObserver<UnRegistrationResponseDTO>>()

        //act
        assertThrows<StatusRuntimeException>("PERMISSION_DENIED") {
            optimizerEndpoint.unregisterRequest(unregisterDTO, responseObserver)
        }

        //assert
        verify(responseObserver, times(1)).onError(check {
            assertThat(it.message).isEqualTo("PERMISSION_DENIED")
        })
        assertThat(logger.log).isEmpty()
    }

    /**
     * Rename
     */
    @Test
    fun `rename existing node should update the node`() = runBlocking<Unit> {
        //setup
        val optimizerEndpoint = create()
        optimizerEndpoint.registerRequest(registerDTO, mock())
        val responseObserver = mock<StreamObserver<NodeNameChangeResponseDTO>>()

        //act
        optimizerEndpoint.changeNodeName(NodeNameChangeCommandDTO.newBuilder().setOldName("N1").setNewName("N2").build(), responseObserver)

        //assert
        verify(responseObserver, times(1)).onNext(check {
            assertThat(it.message).isEqualTo(buildNameChangeMessage(true, "N1", "N2"))
        })
        assertThat(logger.log.toDebugString()).isEqualTo("""
            |Optimizer Event > ${PluginRegisteredEvent(name = "N1")}
            |Optimizer Event > ${PluginRenamedEvent(oldName = "N1", newName = "N2")}
        """.trimMargin())
    }

    @Test
    fun `when request rename and there are both proxy and sim should rename both`() = runBlocking<Unit> {
        //setup
        val optimizerEndpoint = create()
        optimizerEndpoint.registerRequest(registerDTO, mock())
        optimizerEndpoint.autoConfigure(testStatusRequest, mock())
        val responseObserver = mock<StreamObserver<NodeNameChangeResponseDTO>>()
        //act
        optimizerEndpoint.changeNodeName(NodeNameChangeCommandDTO.newBuilder().setOldName("N1").setNewName("N2").build(), responseObserver)

        //assert
        verify(responseObserver, times(1)).onNext(check {
            assertThat(it.message).isEqualTo(buildNameChangeMessage(true, "N1", "N2"))
        })
        assertThat(logger.log.toDebugString()).isEqualTo("""
            |Optimizer Event > ${PluginRegisteredEvent(name = "N1")}
            |Optimizer Event > ${PluginUpdatedEvent(name = "N1")}
            |Optimizer Event > ${ProxyAddedEvent(name = "N1")}
            |Optimizer Event > ${ProxyUpdatedEvent(name = "N1")}
            |Optimizer Event > ${PluginRenamedEvent(oldName = "N1", newName = "N2")}
            |Optimizer Event > ${ProxyRenamedEvent(oldName = "N1", newName = "N2")}
        """.trimMargin())
    }

    @Test
    @Disabled("Not implemented")
    fun `rename non existing node`() {
        //deny
    }

    @Test
    fun `rename existing node to a dupcliated name should not do the rename`() = runBlocking<Unit> {
        //setup
        val optimizerEndpoint = create()
        optimizerEndpoint.registerRequest(registerDTO, mock())
        optimizerEndpoint.registerRequest(registerDTO2, mock())
        val responseObserver = mock<StreamObserver<NodeNameChangeResponseDTO>>()

        //act
        optimizerEndpoint.changeNodeName(NodeNameChangeCommandDTO.newBuilder().setOldName("N1").setNewName("N2").build(), responseObserver)

        //assert
        verify(responseObserver, times(1)).onNext(check {
            assertThat(it.message).isEqualTo(buildNameChangeMessage(false, "N1", "N2"))
        })
        assertThat(logger.log.toDebugString()).isEqualTo("""
            |Optimizer Event > ${PluginRegisteredEvent(name = "N1")}
            |Optimizer Event > ${PluginRegisteredEvent(name = "N2")}
        """.trimMargin())
    }

    /**
     * setups: auto update/ update/ update configuration
     */
    @Test
    fun `when request auto setup should add the proxy for the connected plugin`() = runBlocking<Unit> {
        //setup
        val endpoint = create()
        endpoint.registerRequest(registerDTO, mock())
        val responseObserver = mock<StreamObserver<NodeChangeConfirmDTO>>()

        //act
        endpoint.autoConfigure(testStatusRequest, responseObserver)

        //assert
        verify(responseObserver, times(1)).onNext(check {
            assertThat(it.message).isEqualTo(buildAutoSetupMessage(buildSim("N1", listOf("x1", "x2"), listOf("f1", "f2")), true))
        })
        assertThat(logger.log.toDebugString()).isEqualTo("""
            |Optimizer Event > ${PluginRegisteredEvent(name = "N1")}
            |Optimizer Event > ${PluginUpdatedEvent(name = "N1")}
            |Optimizer Event > ${ProxyAddedEvent(name = "N1")}
            |Optimizer Event > ${ProxyUpdatedEvent(name = "N1")}
        """.trimMargin())
    }

    @Test
    @Disabled("Not implmented")
    fun `auto setup but not register should deny`() {
        //deny
    }

    @Test
    fun `when send update with no proxy should do the update only the sim`() = runBlocking<Unit> {
        //setup
        val endpoint = create()
        endpoint.registerRequest(registerDTO, mock())
        val responseObserver = mock<StreamObserver<NodeChangeConfirmDTO>>()

        //act
        endpoint.updateNode(testStatusRequest, responseObserver)

        //assert
        verify(responseObserver, times(1)).onNext(check {
            assertThat(it.message).isEqualTo(buildSimulationUpdateMessage(buildSim("N1", listOf("x1", "x2"), listOf("f1", "f2")), true))
        })
        assertThat(logger.log.toDebugString()).isEqualTo("""
            |Optimizer Event > ${PluginRegisteredEvent(name = "N1")}
            |Optimizer Event > ${PluginUpdatedEvent(name = "N1")}
        """.trimMargin())
    }

    @Test
    fun `when send update with proxy should do the update both`() = runBlocking<Unit> {
        //setup
        val endpoint = create()
        endpoint.registerRequest(registerDTO, mock())
        endpoint.autoConfigure(testStatusRequest, mock())
        val responseObserver = mock<StreamObserver<NodeChangeConfirmDTO>>()

        //act
        endpoint.updateNode(testStatusRequest2, responseObserver)

        //assert
        verify(responseObserver, times(1)).onNext(check {
            assertThat(it.message).isEqualTo(buildSimulationUpdateMessage(buildSim("N1", listOf("x1", "x2", "x3"), listOf("f1")), true))
        })
        assertThat(logger.log.toDebugString()).isEqualTo("""
            |Optimizer Event > ${PluginRegisteredEvent(name = "N1")}
            |Optimizer Event > ${PluginUpdatedEvent(name = "N1")}
            |Optimizer Event > ${ProxyAddedEvent(name = "N1")}
            |Optimizer Event > ${ProxyUpdatedEvent(name = "N1")}
            |Optimizer Event > ${PluginUpdatedEvent(name = "N1")}
            |Optimizer Event > ${ProxyUpdatedEvent(name = "N1")}
        """.trimMargin())
    }

    val startRequest = StartOptimizationCommandDTO.newBuilder().setName("N3").build()
    val resultResponse = SimulationResponseDTO.newBuilder().setName("N3").build()
    val errorResponse = ErrorResponseDTO.newBuilder().setName("N3").setMessage("Test error").setException("Test exception").build()
    val stopRequest = StopOptimizationCommandDTO.newBuilder().setName("N3").build()
    val expectedInputs = mapOf("x1" to 5.0, "x2" to 5.0, "x3" to 5.0)

    /**
     * Execution: Start/ Stop
     */
    @Test
    fun `when do a full run loop and stop after 2nd iteration finished`() = runBlocking<Unit> {
        //setup
        val endpoint = create()
        val channel = Channel<RequestQueryDTO>()
        val simulation = Simulation(
                "N3",
                ForwardingStream(channel),
                listOf("x1", "x2").map { Input(it, 0.0, 5.0, 0.0) },
                listOf("f1", "f2").map { Output(it) }
        )
        modelService.addSim(simulation)
        endpoint.autoConfigure(testStatusRequest3, mock())
        endpoint.startOptimization(startRequest, mock())
        //act
        val feedAndBuildResponseList: List<RequestQueryDTO> = channel.feedAndBuildResponseList(listOf(
                { endpoint.offerSimulationResult(resultResponse, mock()) },
                { endpoint.offerSimulationResult(resultResponse, mock()) },
                { endpoint.stopOptimization(stopRequest, mock()) },
                { runBlocking{awaitOnEvent<RunStoppedEvent> {endpoint.offerSimulationResult(resultResponse, mock())}} },
                { }
        ))



        val runIDString = feedAndBuildResponseList.first().startRequest.runID
        val runID = UUID.fromString(runIDString)
        //assert
        assertThat(feedAndBuildResponseList).isEqualTo(listOf(
                RequestQueryDTO.newBuilder().setStartRequest(
                        RequestQueryDTO.SimulationStartedRequest.newBuilder().setRunID(runIDString)
                ).build(),
                RequestQueryDTO.newBuilder().setEvaluationRequest(
                        RequestQueryDTO.SimulationEvaluationRequest.newBuilder().setName("N3").putAllInputVector(expectedInputs)
                ).build(),
                RequestQueryDTO.newBuilder().setEvaluationRequest(
                        RequestQueryDTO.SimulationEvaluationRequest.newBuilder().setName("N3").putAllInputVector(expectedInputs)
                ).build(),
                RequestQueryDTO.newBuilder().setEvaluationRequest(
                        RequestQueryDTO.SimulationEvaluationRequest.newBuilder().setName("N3").putAllInputVector(expectedInputs)
                ).build(),
                RequestQueryDTO.newBuilder().setStopRequest(
                        RequestQueryDTO.SimulationStoppedRequest.newBuilder().setRunID(runIDString)
                ).build()
        ))

        assertThat(logger.log.toDebugString()).isEqualTo("""
            |Optimizer Event > ${PluginRegisteredEvent(name = "N3")}
            |Optimizer Event > ${PluginUpdatedEvent(name = "N3")}
            |Optimizer Event > ${ProxyAddedEvent(name = "N3")}
            |Optimizer Event > ${ProxyUpdatedEvent(name = "N3")}
            |Optimizer Event > ${StartRequestedEvent(id = runID)}
            |Optimizer Event > ${RunStartedEvent(id = runID)}
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Evaluating: N3 (1/1)")}
            |Optimizer Event > ${NewResultEvent(result = Success(name = "N3", inputs = mapOf("x1" to 5.0, "x2" to 5.0, "x3" to 5.0), result = emptyMap()))}
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Evaluation finished.")}
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Evaluating: N3 (1/1)")}
            |Optimizer Event > ${NewResultEvent(result = Success(name = "N3", inputs = mapOf("x1" to 5.0, "x2" to 5.0, "x3" to 5.0), result = emptyMap()))}
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Evaluation finished.")}
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Evaluating: N3 (1/1)")}
            |Optimizer Event > ${StopRequestedEvent(id = runID)}
            |Optimizer Event > ${NewResultEvent(result = Success(name = "N3", inputs = mapOf("x1" to 5.0, "x2" to 5.0, "x3" to 5.0), result = emptyMap()))}
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Evaluation finished.")}
            |Optimizer Event > ${RunStoppedEvent(id = runID)}
        """.trimMargin())
    }

    private suspend fun <U> Channel<U>.feedAndBuildResponseList(actions: List<() -> Unit>): List<U> = actions.map { action ->
        val receive = receive()
        action()
        receive
    }

    @Test
    fun `when error happens during evaluation`() = runBlocking<Unit> {
        //setup
        val endpoint = create()
        val channel = Channel<RequestQueryDTO>()
        val simulation = Simulation(
                "N3",
                ForwardingStream(channel),
                listOf("x1", "x2").map { Input(it, 0.0, 5.0, 0.0) },
                listOf("f1", "f2").map { Output(it) }
        )
        modelService.addSim(simulation)
        endpoint.autoConfigure(testStatusRequest3, mock())
        endpoint.startOptimization(startRequest, mock())
        //act
        val feedAndBuildResponseList: List<RequestQueryDTO> = channel.feedAndBuildResponseList(listOf(
                { },
                { endpoint.offerErrorResult(errorResponse, mock()) },
                { }
        ))

        //assert
        val runID = feedAndBuildResponseList.first().startRequest.runID

        assertThat(feedAndBuildResponseList).isEqualTo(listOf(
                RequestQueryDTO.newBuilder().setStartRequest(
                        RequestQueryDTO.SimulationStartedRequest.newBuilder().setRunID(runID)
                ).build(),
                RequestQueryDTO.newBuilder().setEvaluationRequest(
                        RequestQueryDTO.SimulationEvaluationRequest.newBuilder().setName("N3").putAllInputVector(expectedInputs)
                ).build(),
                RequestQueryDTO.newBuilder().setEvaluationRequest(
                        RequestQueryDTO.SimulationEvaluationRequest.newBuilder().setName("N3").putAllInputVector(expectedInputs)
                ).build()
        ))

        assertThat(logger.log.toDebugString()).containsOnlyOnce("""
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Evaluating: N3 (1/1)")}
            |Optimizer Event > ${NewResultEvent(result = Failed(name = "N3", inputs = expectedInputs, exception = "Test exception"))}
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Evaluation finished.")}
        """.trimMargin())
    }

    @Test
    fun `when timed out during evaluation`() = runBlocking<Unit> {
        //setup
        val endpoint = create()
        val channel = Channel<RequestQueryDTO>()
        val simulation = Simulation(
                "N3",
                ForwardingStream(channel),
                listOf("x1", "x2").map { Input(it, 0.0, 5.0, 0.0) },
                listOf("f1", "f2").map { Output(it) }
        )
        modelService.addSim(simulation)
        endpoint.autoConfigure(testStatusRequest3, mock())
        modelService.setTimeout("N3", Duration.ofMillis(50))
        endpoint.startOptimization(startRequest, mock())

        //act
        val feedAndBuildResponseList: List<RequestQueryDTO> = channel.feedAndBuildResponseList(listOf(
                { /**Start notice: do nothing or ready up*/ },
                { /**First evaluation, do nothing until timed out*/ },
                {
                    /**Cancel request*/
                    endpoint.offerSimulationResult(resultResponse, mock())
                },
                {}
        ))

        //assert
        val runID1 = feedAndBuildResponseList.first().startRequest.runID
        val runID = UUID.fromString(runID1)

        assertThat(feedAndBuildResponseList).isEqualTo(listOf(
                RequestQueryDTO.newBuilder().setStartRequest(
                        RequestQueryDTO.SimulationStartedRequest.newBuilder().setRunID(runID1)
                ).build(),
                RequestQueryDTO.newBuilder().setEvaluationRequest(
                        RequestQueryDTO.SimulationEvaluationRequest.newBuilder().setName("N3").putAllInputVector(expectedInputs)
                ).build(),
                RequestQueryDTO.newBuilder().setCancelRequest(
                        RequestQueryDTO.SimulationCancelRequest.newBuilder().setName("N3")
                ).build(),
                RequestQueryDTO.newBuilder().setEvaluationRequest(
                        RequestQueryDTO.SimulationEvaluationRequest.newBuilder().setName("N3").putAllInputVector(expectedInputs)
                ).build()
        ))

        assertThat(logger.log.toDebugString()).containsOnlyOnce("""
            |Optimizer Event > ${StartRequestedEvent(id = runID)}
            |Optimizer Event > ${RunStartedEvent(id = runID)}
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Evaluating: N3 (1/1)")}
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Timed out, Canceling...")}
            |Optimizer > Evaluation Canceled
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Cancel finished. [Canceled(name=N3)]")}
            |Optimizer Event > ${NewResultEvent(result = TimeOut(name = "N3", inputs = expectedInputs))}
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Evaluation finished.")}
        """.trimMargin())
    }

    @Test
    fun `when error happens during cancel`() = runBlocking<Unit> {
        //setup
        val endpoint = create()
        val channel = Channel<RequestQueryDTO>()
        val simulation = Simulation(
                "N3",
                ForwardingStream(channel),
                listOf("x1", "x2").map { Input(it, 0.0, 5.0, 0.0) },
                listOf("f1", "f2").map { Output(it) }
        )
        modelService.addSim(simulation)
        endpoint.autoConfigure(testStatusRequest3, mock())
        modelService.setTimeout("N3", Duration.ofMillis(50))
        endpoint.startOptimization(startRequest, mock())

        //act
        val feedAndBuildResponseList: List<RequestQueryDTO> = channel.feedAndBuildResponseList(listOf(
                { /**Start notice: do nothing or ready up*/ },
                { /**First evaluation, do nothing until timed out*/ },
                {
                    /**Cancel request*/
                    endpoint.offerErrorResult(errorResponse, mock())
                }
        ))

        //assert
        val runID1 = feedAndBuildResponseList.first().startRequest.runID

        assertThat(feedAndBuildResponseList).containsSequence(listOf(
                RequestQueryDTO.newBuilder().setStartRequest(
                        RequestQueryDTO.SimulationStartedRequest.newBuilder().setRunID(runID1)
                ).build(),
                RequestQueryDTO.newBuilder().setEvaluationRequest(
                        RequestQueryDTO.SimulationEvaluationRequest.newBuilder().setName("N3").putAllInputVector(expectedInputs)
                ).build(),
                RequestQueryDTO.newBuilder().setCancelRequest(
                        RequestQueryDTO.SimulationCancelRequest.newBuilder().setName("N3")
                ).build()
        ))

        assertThat(logger.log.toDebugString()).containsOnlyOnce("""
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Evaluating: N3 (1/1)")}
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Timed out, Canceling...")}
            |Optimizer > Cancellation Failed, Cause:
            |Test exception
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Cancel finished. [${CancelResult.CancelFailed(name = "N3", exception = "Test exception")}]")}
            |Optimizer Event > ${NewResultEvent(result = TimeOut(name = "N3", inputs = expectedInputs))}
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Evaluation finished.")}
        """.trimMargin())
    }

    @Test
    fun `when force stopped during evaluation`() = runBlocking<Unit> {
        //setup
        val endpoint = create()
        val channel = Channel<RequestQueryDTO>()
        val simulation = Simulation(
                "N3",
                ForwardingStream(channel),
                listOf("x1", "x2").map { Input(it, 0.0, 5.0, 0.0) },
                listOf("f1", "f2").map { Output(it) }
        )
        modelService.addSim(simulation)
        endpoint.autoConfigure(testStatusRequest3, mock())
        endpoint.startOptimization(startRequest, mock())

        //act
        val feedAndBuildResponseList: List<RequestQueryDTO> = channel.feedAndBuildResponseList(listOf(
                { /**Start notice: do nothing or ready up*/ },
                {
                    /**First evaluation*/
                    runBlocking { awaitOnEvent<StopRequestedEvent> { endpoint.stopOptimization(stopRequest, mock()) } }
                }
        ))

        awaitOnEvent<NewResultEvent> { stateMachine.forceStop() }

        //assert
        val runIdString = feedAndBuildResponseList.first().startRequest.runID

        assertThat(feedAndBuildResponseList).isEqualTo(listOf(
                RequestQueryDTO.newBuilder().setStartRequest(
                        RequestQueryDTO.SimulationStartedRequest.newBuilder().setRunID(runIdString)
                ).build(),
                RequestQueryDTO.newBuilder().setEvaluationRequest(
                        RequestQueryDTO.SimulationEvaluationRequest.newBuilder().setName("N3").putAllInputVector(expectedInputs)
                ).build()
        ))

        assertThat(logger.log.toDebugString()).containsOnlyOnce("""
            |Optimizer Event > ${StopRequestedEvent(id = UUID.fromString(runIdString))}
            |Optimizer Event > ${ForceStopRequestedEvent(id = UUID.fromString(runIdString))}
            |Optimizer Event > ${PluginUnRegisteredEvent(name = "N3")}
            |Optimizer Event > ${NewResultEvent(result = Terminated(name = "N3", inputs = expectedInputs, message = "Evaluation is terminated during evaluation"))}
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Evaluation finished.")}
        """.trimMargin())
    }

    @Test
    fun `when force stopped during cancel`() = runBlocking<Unit> {
        //setup
        val endpoint = create()
        val channel = Channel<RequestQueryDTO>()
        val simulation = Simulation(
                "N3",
                ForwardingStream(channel),
                listOf("x1", "x2").map { Input(it, 0.0, 5.0, 0.0) },
                listOf("f1", "f2").map { Output(it) }
        )
        modelService.addSim(simulation)
        endpoint.autoConfigure(testStatusRequest3, mock())
        modelService.setTimeout("N3", Duration.ofMillis(50))
        endpoint.startOptimization(startRequest, mock())

        //act
        val feedAndBuildResponseList: List<RequestQueryDTO> = channel.feedAndBuildResponseList(listOf(
                { /**Start notice: do nothing or ready up*/ },
                { /**First evaluation, do nothing until timed out*/ },
                {
                    /**Cancel request*/
                    runBlocking {
                        awaitOnEvent<StopRequestedEvent> { endpoint.stopOptimization(stopRequest, mock()) }
                    }
                }
        ))

        awaitOnEvent<NewResultEvent> { stateMachine.forceStop() }

        //assert
        val runID1 = feedAndBuildResponseList.first().startRequest.runID

        assertThat(feedAndBuildResponseList).isEqualTo(listOf(
                RequestQueryDTO.newBuilder().setStartRequest(
                        RequestQueryDTO.SimulationStartedRequest.newBuilder().setRunID(runID1)
                ).build(),
                RequestQueryDTO.newBuilder().setEvaluationRequest(
                        RequestQueryDTO.SimulationEvaluationRequest.newBuilder().setName("N3").putAllInputVector(expectedInputs)
                ).build(),
                RequestQueryDTO.newBuilder().setCancelRequest(
                        RequestQueryDTO.SimulationCancelRequest.newBuilder().setName("N3")
                ).build()
        ))

        assertThat(logger.log.toDebugString()).containsOnlyOnce("""
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Timed out, Canceling...")}
            |Optimizer Event > ${StopRequestedEvent(id = UUID.fromString(runID1))}
            |Optimizer Event > ${ForceStopRequestedEvent(id = UUID.fromString(runID1))}
            |Optimizer Event > ${PluginUnRegisteredEvent(name = "N3")}
            |Optimizer > Cancellation Terminated, Cause:
            |Force-stopped
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Cancel finished. [${CancelResult.CancelTerminated(name = "N3", exception = "Cancellation is terminated")}]")}
            |Optimizer Event > ${NewResultEvent(result = TimeOut(name = "N3", inputs = expectedInputs))}
            |Optimizer Event > ${BasicStatusUpdateEvent(message = "Evaluation finished.")}
        """.trimMargin())
    }

    private suspend inline fun <reified T : Event> awaitOnEvent(action: () -> Any? = {}) {
        val deferred = CompletableDeferred<Unit>()
        eventBus.register(object {
            @Subscribe
            fun onReceive(event: T) {
                if (event !is T) return
                deferred.complete(Unit)
                eventBus.unregister(this)
            }
        })
        action()
        deferred.await()
    }

    @Test
    fun `when start but there are issues`() = runBlocking<Unit> {
        //setup
        val endpoint = create()
        val channel = Channel<RequestQueryDTO>()
        val simulation = Simulation(
                "N3",
                ForwardingStream(channel),
                listOf("x1", "x2").map { Input(it, 0.0, 5.0, 0.0) },
                listOf("f1", "f2").map { Output(it) }
        )
        modelService.addSim(simulation)

        //act
        val responseObserver = mock<StreamObserver<StartOptimizationResponseDTO>>()
        endpoint.startOptimization(startRequest, responseObserver)


        //assert
        verify(responseObserver, times(1)).onNext(check {
            assertThat(it.acknowledged).isFalse()
            assertThat(it.message).isEqualTo("Optimization cannot start: " +
                    "${Issue(message = "No proxy setup")}, " +
                    "${Issue(message = "Not used Simulation: Simulation \"N3\" are not used in any proxy setup")}, " +
                    "${Issue(message = "Optimization are not able to start")}")
        })
    }
}