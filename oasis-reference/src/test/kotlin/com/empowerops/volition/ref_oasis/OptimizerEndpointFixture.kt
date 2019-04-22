package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import com.nhaarman.mockitokotlin2.*
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import org.assertj.core.api.Assertions.assertThat
import org.funktionale.either.Either
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.*

class OptimizerEndpointFixture {
    private lateinit var optimizerService: OptimizerService
    private lateinit var modelService: DataModelService
    private lateinit var endpoint: ApiService

    data class Params<T, U>(
            val name: String,
            val request: T,
            val expectedResponse: U,
            val act: (T) -> U,
            val before: () -> Unit = {},
            val after: () -> Unit = {}
    )

    private fun <T, U> assertResponse(
            param: Params<T, U>
    ) {
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
        optimizerService = mock()
        modelService = mock()
        endpoint = ApiService(modelService, mock())
    }

    @TestFactory
    fun `Unit test driver`(): Collection<DynamicTest> {
        val testSimulation = buildSimDTO("node1", listOf("x1", "x2"), listOf("f1", "f2"))
        val testStatusRequest = buildNodeStatusCommandDTO("node1", listOf("x1", "x2"), listOf("f1", "f2"))
        val randomID = UUID.randomUUID()
        val list = listOf(
                EvaluationResult.Success("Node1", mapOf("x1" to 1.0, "x2" to 5.0), mapOf("f1" to 15.0)),
                EvaluationResult.Failed("Node1", mapOf("x1" to 5.0, "x2" to 2.0), "Test fail message")
        )
        val testRunResultResponse = ResultResponseDTO.newBuilder().setRunResult(
                RunResult.newBuilder().addAllPoint(
                        listOf(
                                Design.newBuilder()
                                        .putAllInput(mapOf("x1" to 1.0, "x2" to 5.0))
                                        .putAllOutput(mapOf("f1" to 15.0))
                                        .build(),
                                Design.newBuilder()
                                        .putAllInput(mapOf("x1" to 5.0, "x2" to 2.0))
                                        .build()
                        )
                )
        ).build()

        val params = listOf(
                Params("when change node succeed should change the node name and return correct message",
                        NodeNameChangeCommandDTO.newBuilder().setOldName("oldName").setNewName("newName").build(),
                        NodeNameChangeResponseDTO.newBuilder().setChanged(true).setMessage(buildNameChangeMessage(true, "oldName", "newName")).build(),
                        endpoint::changeNodeName,
                        { whenever(modelService.renameSim(any(), any())) doReturn true }
                ),
                Params("when request start optimization and can not start due to issue should return correct response",
                        StartOptimizationCommandDTO.newBuilder().setName("node1").build(),
                        StartOptimizationResponseDTO.newBuilder().setMessage(buildStartIssuesMessage(listOf("issue1", "issue2").map { Issue(it) })).build(),
                        endpoint::issueStartOptimization,
                        { whenever(optimizerService.requestStart()) doReturn Either.left(listOf("issue1", "issue2")) }
                ),
                Params("when request start optimization and can start should ask optimizer to start and return correct response",
                        StartOptimizationCommandDTO.newBuilder().setName("node1").build(),
                        StartOptimizationResponseDTO.newBuilder().setAcknowledged(true).build(),
                        endpoint::issueStartOptimization,
                        { whenever(optimizerService.requestStart()) doReturn Either.right(UUID(0, 0)) }
                ),
                Params("when send a message should return a response",
                        MessageCommandDTO.newBuilder().setName("node1").setMessage("test message").build(),
                        MessageResponseDTO.newBuilder().build(),
                        endpoint::sendMessage
                ),
                Params("when request update and failed should return a failed response",
                        testStatusRequest,
                        NodeChangeConfirmDTO.newBuilder().setMessage(buildSimulationUpdateMessage(testSimulation, false)).build(),
                        endpoint::updateNode,
                        { whenever(modelService.updateSimAndConfiguration(any<NodeStatusCommandOrResponseDTO>())) doReturn Pair(false, testSimulation) }
                ),
                Params("when request update and succeed should return a success response",
                        testStatusRequest,
                        NodeChangeConfirmDTO.newBuilder().setMessage(buildSimulationUpdateMessage(testSimulation, true)).build(),
                        endpoint::updateNode,
                        { whenever(modelService.updateSimAndConfiguration(any<NodeStatusCommandOrResponseDTO>())) doReturn Pair(true, testSimulation) }
                ),
                Params("when request result and it exist should return the result response",
                        ResultRequestDTO.newBuilder().setName("Node1").setRunID(randomID.toString()).build(),
                        testRunResultResponse,
                        endpoint::resultRequest,
                        {
                            modelService.resultList += randomID to list
                        }
                )
        )

        return params.map { param -> DynamicTest.dynamicTest("[${param.name}]") { assertResponse(param) } }
    }

    private fun buildNodeStatusCommandDTO(name: String, inputs: List<String>, outputs: List<String>): NodeStatusCommandOrResponseDTO {
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

    private fun buildSimDTO(name: String, inputs: List<String>, outputs: List<String>) = Simulation(
            name,
            mock(),
            inputs.map { Input(it, 0.0, 0.5, 0.0) },
            outputs.map { Output(it) }
    )

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
        modelService.simulations = listOf(Simulation(name, responseObserver))

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
                    assertThat(it).isEqualTo(StatusException(Status.ALREADY_EXISTS))
                }
        )
    }


}
