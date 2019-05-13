package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.dto.*
import com.empowerops.volition.ref_oasis.model.*
import com.nhaarman.mockitokotlin2.*
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Duration
import java.util.*

class OptimizerEndpointFixture {
    private lateinit var runStateMachine: RunStateMachine
    private lateinit var modelService: ModelService
    private lateinit var endpoint: ApiService

    data class Params<T, U>(
            val name: String,
            val request: T,
            val expectedResponse: U,
            val act: (T) -> U,
            val before: () -> Unit = {},
            val after: () -> Unit = {}
    )

    private fun <T, U> assertResponse(param: Params<T, U>) {
        //setup
        param.before.invoke()
        //act
        val response: U = param.act.invoke(param.request)
        //assert
        assertThat(response).isEqualTo(param.expectedResponse)
        param.after.invoke()
    }

    @BeforeEach
    fun setup() {
        runStateMachine = mock()
        modelService = mock()
        endpoint = ApiService(modelService)
    }

//    @TestFactory
//    fun `Unit test driver`(): Collection<DynamicTest> {
//        val testSimulation = buildSim("node1", listOf("x1", "x2"), listOf("f1", "f2"))
//        val testStatusRequest = buildNodeStatusCommandDTO("node1", listOf("x1", "x2"), listOf("f1", "f2"))
//        val randomID = UUID.randomUUID()
//        val randomRunID = UUID.randomUUID()
//        val resultList = listOf(
//                EvaluationResult.Success("Node1", mapOf("x1" to 1.0, "x2" to 5.0), mapOf("f1" to 15.0)),
//                EvaluationResult.Failed("Node1", mapOf("x1" to 5.0, "x2" to 2.0), "Test fail message")
//        )
//        val testRunResultResponse = ResultResponseDTO.newBuilder().apply {
//            runResult = RunResult.newBuilder().addAllPoint(
//                    listOf(
//                            Design.newBuilder()
//                                    .putAllInput(mapOf("x1" to 1.0, "x2" to 5.0))
//                                    .putAllOutput(mapOf("f1" to 15.0))
//                                    .build(),
//                            Design.newBuilder()
//                                    .putAllInput(mapOf("x1" to 5.0, "x2" to 2.0))
//                                    .build()
//                    )
//            ).build()
//        }.build()
//
//        val params = listOf(
//                Params("when change node succeed should change the node name and return correct message",
//                        NodeNameChangeCommandDTO.newBuilder().setOldName("oldName").setNewName("newName").build(),
//                        NodeNameChangeResponseDTO.newBuilder().setChanged(true).setMessage(buildNameChangeMessage(true, "oldName", "newName")).build(),
//                        endpoint::changeNodeName,
//                        {
//                            whenever(modelService.renameSim(eq("newName"), eq("oldName"))) doReturn true
//                        }
//                ),
//                Params("when request start optimization NodeStatusCommandOrResponseDTO and can not start due to issue should return correct response",
//                        StartOptimizationCommandDTO.newBuilder().setName("node1").build(),
//                        StartOptimizationResponseDTO.newBuilder().setMessage(buildStartIssuesMessage(listOf("issue1", "issue2").map { Issue(it) })).build(),
//                        endpoint::requestStart,
//                        {
//                            whenever(optimizerService.canStart()) doReturn true
//                            whenever(modelService.findIssues()) doReturn listOf("issue1", "issue2").map { Issue(it) }
//                        }
//                ),
//                Params("when request start optimization and can start should ask optimizer to start and return correct response",
//                        StartOptimizationCommandDTO.newBuilder().setName("node1").build(),
//                        StartOptimizationResponseDTO.newBuilder().setMessage(buildStartIssuesMessage(emptyList())).setAcknowledged(true).build(),
//                        endpoint::requestStart,
//                        {
//                            whenever(optimizerService.canStart()) doReturn true
//                            whenever(modelService.findIssues()) doReturn emptyList()
//                        }
//                ),
//                Params("when request stop optimization and can not stop should return correct response",
//                        StopOptimizationCommandDTO.newBuilder().setName("node1").setId(randomRunID.toString()).build(),
//                        StopOptimizationResponseDTO.newBuilder().setMessage(buildStopMessage()).build(),
//                        endpoint::requestStop,
//                        { whenever(optimizerService.canStop(eq(randomRunID))) doReturn false }
//                ),
//                Params("when request stop optimization and can stop should return a correct response",
//                        StopOptimizationCommandDTO.newBuilder().setName("node1").setId(randomRunID.toString()).build(),
//                        StopOptimizationResponseDTO.newBuilder().setRunID(randomRunID.toString()).build(),
//                        endpoint::requestStop,
//                        { whenever(optimizerService.canStop(eq(randomRunID))) doReturn true }
//                ),
//                Params("when send a message should return a response",
//                        MessageCommandDTO.newBuilder().setName("node1").setMessage("test message").build(),
//                        MessageResponseDTO.newBuilder().build(),
//                        endpoint::sendMessage
//                ),
//                Params("when request update and failed should return a failed response",
//                        testStatusRequest,
//                        NodeChangeConfirmDTO.newBuilder().setMessage(buildSimulationUpdateMessage(testSimulation, false)).build(),
//                        endpoint::updateNode,
//                        { whenever(modelService.updateSimAndConfiguration(any<NodeStatusCommandOrResponseDTO>())) doReturn Pair(false, testSimulation) }
//                ),
//                Params("when request update and succeed should return a success response",
//                        testStatusRequest,
//                        NodeChangeConfirmDTO.newBuilder().setMessage(buildSimulationUpdateMessage(testSimulation, true)).build(),
//                        endpoint::updateNode,
//                        { whenever(modelService.updateSimAndConfiguration(any<NodeStatusCommandOrResponseDTO>())) doReturn Pair(true, testSimulation) }
//                ),
//                Params("when request auto setup and succeed should return a success response",
//                        testStatusRequest,
//                        NodeChangeConfirmDTO.newBuilder().setMessage(buildAutoSetupMessage(testSimulation, true)).build(),
//                        endpoint::autoConfigure,
//                        { whenever(modelService.autoSetup(any<NodeStatusCommandOrResponseDTO>())) doReturn Pair(true, testSimulation) }
//                ),
//                Params("when request auto setup and failed should return a failed response",
//                        testStatusRequest,
//                        NodeChangeConfirmDTO.newBuilder().setMessage(buildAutoSetupMessage(testSimulation, false)).build(),
//                        endpoint::autoConfigure,
//                        { whenever(modelService.autoSetup(any<NodeStatusCommandOrResponseDTO>())) doReturn Pair(false, testSimulation) }
//                ),
//                Params("when request result and it exist should return the result response",
//                        ResultRequestDTO.newBuilder().setName("Node1").setRunID(randomID.toString()).build(),
//                        testRunResultResponse,
//                        endpoint::resultRequest,
//                        { whenever(modelService.resultList) doReturn (mapOf(randomID to resultList)) }
//                ),
//                Params("when request result but it does not exist should return the result response",
//                        ResultRequestDTO.newBuilder().setName("Node1").setRunID(randomRunID.toString()).build(),
//                        ResultResponseDTO.newBuilder().setMessage(buildRunNotFoundMessage(randomRunID.toString())).build(),
//                        endpoint::resultRequest,
//                        { whenever(modelService.resultList) doReturn (mapOf()) }
//                ),
//                Params("when request update a configuration(proxy) should update the node",
//                        ConfigurationCommandDTO.newBuilder().setName("Node1").setConfig(ConfigurationCommandDTO.Config.newBuilder().setTimeout(5000)).build(),
//                        ConfigurationResponseDTO.newBuilder().setUpdated(true).setMessage(buildUpdateMessage("Node1", Duration.ofMillis(5000), true)).build(),
//                        endpoint::updateConfiguration,
//                        { whenever(modelService.setTimeout(eq("Node1"), eq(Duration.ofMillis(5000)))) doReturn true }
//                ),
//                Params("when request update a configuration(proxy) should update the node",
//                        ConfigurationCommandDTO.newBuilder().setName("Node1").setConfig(ConfigurationCommandDTO.Config.newBuilder().setTimeout(5000)).build(),
//                        ConfigurationResponseDTO.newBuilder().setUpdated(false).setMessage(buildUpdateMessage("Node1", Duration.ofMillis(5000), false)).build(),
//                        endpoint::updateConfiguration,
//                        { whenever(modelService.setTimeout(eq("Node1"), eq(Duration.ofMillis(5000)))) doReturn false }
//                ),
//                Params("when request unregister a node succeed should return the correct response",
//                        RequestUnRegistrationRequestDTO.newBuilder().setName("Node1").build(),
//                        UnRegistrationResponseDTO.newBuilder().setMessage(buildUnregisterMessage(true)).build(),
//                        endpoint::unregister,
//                        { whenever(modelService.closeSim(eq("Node1"))) doReturn true }
//                ),
//                Params("when request unregister a node failed should return the failed response",
//                        RequestUnRegistrationRequestDTO.newBuilder().setName("Node1").build(),
//                        UnRegistrationResponseDTO.newBuilder().setMessage(buildUnregisterMessage(false)).build(),
//                        endpoint::unregister,
//                        { whenever(modelService.closeSim(eq("Node1"))) doReturn false }
//                )
//        )
//
//        return params.map { param -> DynamicTest.dynamicTest("[${param.name}]") { assertResponse(param) } }
//    }


    @Test
    fun `when optimizer ask to register a node register`() {
        //setup
        val streamObserver = mock<StreamObserver<RequestQueryDTO>>()
        val name = "TestNode1"
        val registrationDTO = RequestRegistrationCommandDTO.newBuilder().setName(name).build()

        //act
        endpoint.register(registrationDTO, streamObserver)

        //assert
        verify(modelService, times(1)).addSim(
                check {
                    assertThat(it.name).isEqualTo(name)
                    assertThat(it.input).isEqualTo(streamObserver)
                }
        )
    }

    @Test
    fun `when try register a node that is already registered should call on error with message`() {
        //setup
        val name = "Node1"
        val responseObserver = mock<StreamObserver<RequestQueryDTO>>()
        whenever(modelService.simulations) doReturn listOf(Simulation(name, responseObserver))

        //act
        endpoint.register(RequestRegistrationCommandDTO.newBuilder().setName(name).build(), responseObserver)

        //assert
        verify(modelService, times(1)).addSim(
                check {
                    assertThat(it.name).isEqualTo(name)
                    assertThat(it.input).isEqualTo(responseObserver)
                }
        )

        verify(responseObserver, times(1)).onError(
                check {
                    assertThat((it as StatusException).status).isEqualTo(Status.ALREADY_EXISTS)
                }
        )
    }


    @Test
    fun `when offering a result should get a response`() = runBlocking {
        //setup
        val model = mock<ModelService>()
        val request = SimulationResponseDTO.newBuilder().setName("node1").build()
        val outputChannel = mock<Channel<SimulationResponseDTO>>()
        val simulation = Simulation(
                "node1",
                mock(),
                output = outputChannel
        )

        whenever(model.simulations).thenReturn(listOf(simulation))
        val apiService = ApiService(model)

        //act
        val offerResult = apiService.offerResult(request)


        //assert
        assertThat(offerResult).isEqualTo(SimulationResultConfirmDTO.newBuilder().build())
        verify(outputChannel, times(1)).send(
                check {
                    assertThat(it).isEqualTo(request)
                }
        )
    }

    @Test
    fun `when offering a configuration should get a response`()= runBlocking<Unit>{
        //setup
        val model = mock<ModelService>()
        val request = NodeStatusCommandOrResponseDTO.newBuilder().setName("node1").build()
        val updateChannel = mock<Channel<NodeStatusCommandOrResponseDTO>>()
        val simulation = Simulation(
                "node1",
                mock(),
                update = updateChannel
        )

        whenever(model.simulations).thenReturn(listOf(simulation))
        val apiService = ApiService(model)

        //act
        val offerResult = apiService.offerConfig(request)


        //assert
        assertThat(offerResult).isEqualTo(NodeChangeConfirmDTO.newBuilder().build())
        verify(updateChannel, times(1)).send(
                check {
                    assertThat(it).isEqualTo(request)
                }
        )
    }

    @Test
    fun `when offering an error should get a response`()= runBlocking<Unit>{
        //setup
        val model = mock<ModelService>()
        val request = ErrorResponseDTO.newBuilder().setName("node1").build()
        val errorChannel = mock<Channel<ErrorResponseDTO>>()
        val simulation = Simulation(
                "node1",
                mock(),
                error = errorChannel
        )

        whenever(model.simulations).thenReturn(listOf(simulation))
        val apiService = ApiService(model)

        //act
        val offerResult = apiService.offerError(request)


        //assert
        assertThat(offerResult).isEqualTo(ErrorConfirmDTO.newBuilder().build())
        verify(errorChannel, times(1)).send(
                check {
                    assertThat(it).isEqualTo(request)
                }
        )
    }
}

fun buildNodeStatusCommandDTO(name: String, inputs: List<String>, outputs: List<String>): NodeStatusCommandOrResponseDTO {
    return NodeStatusCommandOrResponseDTO.newBuilder().setName(name).apply {
        addAllInputs(inputs.map {
            NodeStatusCommandOrResponseDTO.PrototypeInputParameter.newBuilder()
                    .setName(it)
                    .setLowerBound(0.0)
                    .setUpperBound(5.0)
                    .build()
        }
        )
        addAllOutputs(outputs.map {
            NodeStatusCommandOrResponseDTO.PrototypeOutputParameter.newBuilder()
                    .setName(it)
                    .build()
        }
        )
    }.build()
}

fun buildSim(name: String,
             inputs: List<String>,
             outputs: List<String>,
             inputStream: StreamObserver<RequestQueryDTO> = mock()
): Simulation {
    return Simulation(
            name,
            inputStream,
            inputs.map { Input(it, 0.0, 5.0, 0.0) },
            outputs.map { Output(it) }
    )
}