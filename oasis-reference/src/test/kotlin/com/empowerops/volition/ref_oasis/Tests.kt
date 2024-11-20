package com.empowerops.volition.ref_oasis

import com.empowerops.volition.BetterExceptionsInterceptor
import com.empowerops.volition.DEFAULT_MAX_HEADER_SIZE
import com.empowerops.volition.dto.*
import com.empowerops.volition.dto.OptimizerGeneratedQueryDTO.PurposeCase.*
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.sync.Mutex
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.text.DecimalFormat
import java.util.*


class Tests {

    init {
        System.setProperty("com.empowerops.volition.ref_oasis.useConsoleAlt", "true")
    }

    private lateinit var server: OptimizerCLI
    private lateinit var service: UnaryOptimizerGrpcKt.UnaryOptimizerCoroutineStub

    @BeforeEach
    fun setupServer(){
        val port = findAvailablePort()

        server = mainAsync(arrayOf("--port", port.toString()))!!

        val builder = ManagedChannelBuilder
            .forAddress("localhost", port)
            .maxInboundMetadataSize(RecommendedMaxHeaderSize)
            .maxInboundMessageSize(RecommendedMaxMessageSize)
            .usePlaintext()

        val channel = builder.build()

        val intercepted = ClientInterceptors.intercept(channel, BetterExceptionsInterceptor(DEFAULT_MAX_HEADER_SIZE))

        service = UnaryOptimizerGrpcKt.UnaryOptimizerCoroutineStub(intercepted)
    }

    private fun findAvailablePort(): Int {
        val result = ServerSocket(0).use {
            it.reuseAddress = true
            it.localPort
        }
        return result
    }

    @AfterEach
    fun teardownServer() = runBlocking {
        try {
            server.stop()
        }
        catch(ex: Throwable){
            val x = 4;
            throw ex
        }
    }

    @Test
    fun `when running with version should print version`() = runBlocking<Unit> {
        mainAsync(arrayOf("--version"))?.job?.join()

        val str = consoleAltBytes.toString("utf-8")

        assertThat(str.trim()).contains("Volition API 1.6")
    }

    @Test
    fun `when running a single var single simulation optimization should optimize normally`() = runBlocking<Unit> {
        //act
        val fifthIteration = CompletableDeferred<Unit>()

        // this is the API equivalent of an OPYL file.
        // here we specify the input parameters, and their variable bounds.
        // this is just about the smallest optimization possible:
        // A single variable, single objective, unconstrained function.
        val startOptimizationRequest = startOptimizationCommandDTO {
            problemDefinition = problemDefinitionDTO {
                inputs += inputParameterDTO {
                    name = "x1"
                    continuous = continuousDTO {
                        lowerBound = 1.0
                        upperBound = 5.0
                    }
                }
                evaluables += evaluableNodeDTO {
                    simulation = simulationNodeDTO {
                        autoMap = true
                        inputs += simulationInputParameterDTO { name = "x1" }
                        outputs += simulationOutputParameterDTO { name = "f1" }
                    }
                }
            }
        }

        var iterationNo = 1
        var runID: UUID? = null

        //act
        val messages: Flow<OptimizerGeneratedQueryDTO> = service.startOptimization(startOptimizationRequest)

        val collector = launch {
            messages.collect { optimizerRequest: OptimizerGeneratedQueryDTO ->
                when(optimizerRequest.purposeCase!!) {
                    EVALUATION_REQUEST -> {

                        // here we implement the simulation callback,
                        // for testing purposes, I've told the simulation client to only execute 5 iterations,
                        // calling 'offerSimulationResponse' for the evaluations.

                        if(iterationNo <= 5) {
                            val inputVector = optimizerRequest.evaluationRequest!!.inputVectorMap.toMap()
                            val statusMessage = StatusMessageCommandDTO.newBuilder()
                                .setMessage("evaluating $inputVector!")
                                .build()

                            // this simulator also calls 'offerEvaluationStatusMessage', which is a way that
                            // the simulator can place things in the optimization log that do not pretain to
                            // the final design. Error messages or status updates about meshing, pre-processing,
                            // post-processing, etc can be set here.
                            service.offerEvaluationStatusMessage(statusMessage)

                            val result = inputVector.values.sumByDouble { it } / 2.0

                            val response = SimulationEvaluationCompletedResponseDTO.newBuilder()
                                .setName("asdf")
                                .putAllOutputVector(mapOf("f1" to result))
                                .build()

                            service.offerSimulationResult(response)
                        }
                        else if (iterationNo > 5){
                            val response = SimulationEvaluationErrorResponseDTO.newBuilder()
                                .setMessage("already evaluated 5 iterations!")
                                .build()

                            service.offerErrorResult(response)
                        }
                        if(iterationNo == 5){
                            fifthIteration.complete(Unit)
                        }
                        iterationNo += 1

                        Unit
                    }
                    CANCEL_REQUEST -> Unit //noop --this is a legal implementation in any situation for cancellation.
                    OPTIMIZATION_STARTED_NOTIFICATION -> {
                        runID = optimizerRequest.optimizationStartedNotification.runID.toUUID()
                    }
                    OPTIMIZATION_FINISHED_NOTIFICATION -> Unit // noop
                    PURPOSE_NOT_SET -> TODO("unknown request $optimizerRequest")
                    OPTIMIZATION_NOT_STARTED_NOTIFICATION -> {
                        TODO("optimization didn't start because: ${optimizerRequest.optimizationNotStartedNotification.issuesList.joinToString()}")
                    }
                    DESIGN_ITERATION_COMPLETED_NOTIFICATION -> Unit
                } as Any?
            }
        }

        //note: the original test semantics didnt have stopOptimization being called on the fifh iteration;
        // I migrated to the kotlin generated coroutiens and preserved those semantics.
        // The multiple optimization run test does call stopOptimization() from the request loop.
        fifthIteration.await()

        // note: with this implementation there is a race condition here.
        // The simulator will release the fifth-iteration lock while (concurrently) moving on to the 6th iteration.
        // thus, you may see a 6th iteration before the stopOptimization call is processed.
        val runIDDTO = runID!!.toRunIDDTO()
        service.stopOptimization(stopOptimizationCommandDTO{
            this.runID = runIDDTO
        })

        collector.join()

        //assert
        val results = service.requestRunResult(OptimizationResultsQueryDTO.newBuilder().setRunID(runIDDTO).build())

        val resultsAll = results.pointsList.toList()
        val resultsFrontier = results.pointsList.filter { it.isFrontier }

        assertThat(resultsAll.size).isGreaterThanOrEqualTo(5)
        assertThat(resultsFrontier).hasSize(1)
        // this is a very weak set of assertions, you can see more details about whats in these results
        // in the transaction log below.
    }

    @Test
    fun `when running multiple single var single simulation optimization should optimize normally`() = runBlocking<Unit> {
        //act

        // this is the API equivalent of an OPYL file.
        // here we specify the input parameters, and their variable bounds.
        // this is just about the smallest optimization possible:
        // A single variable, single objective, unconstrained function.
        val targetIterationCount = 5
        val startOptimizationRequest = startOptimizationCommandDTO {
            problemDefinition = problemDefinitionDTO {
                inputs += inputParameterDTO {
                    name = "x1"
                    continuous = continuousDTO {
                        lowerBound = 1.0
                        upperBound = 5.0
                    }
                }

                evaluables += evaluableNodeDTO {
                    simulation = simulationNodeDTO {
                        autoMap = true
                        inputs += simulationInputParameterDTO { name = "x1" }
                        outputs += simulationOutputParameterDTO { name = "f1" }
                    }
                }
            }

            settings = optimizationSettingsDTO {
                iterationCount = targetIterationCount
            }
        }

        var iterationNo = 0

        //act
        val collector = FlowCollector<OptimizerGeneratedQueryDTO> { optimizerRequest ->
            val dc: Unit = when (optimizerRequest.purposeCase!!) {
                EVALUATION_REQUEST -> {

                    val inputVector = optimizerRequest.evaluationRequest!!.inputVectorMap.toMap()
                    val statusMessage = StatusMessageCommandDTO.newBuilder()
                        .setMessage("evaluating $inputVector!")
                        .build()

                    // this simulator also calls 'offerEvaluationStatusMessage', which is a way that
                    // the simulator can place things in the optimization log that do not pretain to
                    // the final design. Error messages or status updates about meshing, pre-processing,
                    // post-processing, etc can be set here.
                    service.offerEvaluationStatusMessage(statusMessage)

                    val result = inputVector.values.sumByDouble { it } / 2.0

                    val response = simulationEvaluationCompletedResponseDTO {
                        name = "asdf"
                        outputVector["f1"] = result
                    }

                    service.offerSimulationResult(response)

                    iterationNo += 1
                }

                CANCEL_REQUEST -> Unit //noop --this is a legal implementation in any situation for cancellation.
                OPTIMIZATION_STARTED_NOTIFICATION -> Unit // noop,
                OPTIMIZATION_FINISHED_NOTIFICATION -> Unit // noop
                PURPOSE_NOT_SET -> TODO("unknown request $optimizerRequest")
                OPTIMIZATION_NOT_STARTED_NOTIFICATION -> {
                    TODO("optimization didn't start because: ${optimizerRequest.optimizationNotStartedNotification.issuesList.joinToString()}")
                }
                DESIGN_ITERATION_COMPLETED_NOTIFICATION -> Unit
            }
        }
        //first run
        service.startOptimization(startOptimizationRequest).collect(collector)
        println("finished first run!")

        //second run
        service.startOptimization(startOptimizationRequest).collect(collector)
        println("finished second run!")

        assertThat(iterationNo).isEqualTo(2 * targetIterationCount)
    }

    @Test
    fun `when running an optimization with a constraint that constraint should be respected while optimizing`() = runBlocking<Unit> {
        //act
        val startRequest = startOptimizationCommandDTO {
            problemDefinition = problemDefinitionDTO {
                inputs += inputParameterDTO {
                    name = "x1"
                    continuous = continuousDTO {
                        lowerBound = 1.0
                        upperBound = 5.0
                    }
                }
                inputs += inputParameterDTO {
                    name = "x2"
                    continuous = continuousDTO {
                        lowerBound = 1.0
                        upperBound = 5.0
                    }
                }
                evaluables += evaluableNodeDTO {
                    simulation = simulationNodeDTO {
                        autoMap = true
                        inputs += simulationInputParameterDTO { name = "x1" }
                        inputs += simulationInputParameterDTO { name = "x2" }
                        outputs += simulationOutputParameterDTO { name = "f1" }
                    }
                }
                evaluables += evaluableNodeDTO {
                    constraint = babelConstraintNodeDTO {
                        outputName = "c1"
                        booleanExpression = "x1 < x2"
                    }
                }
            }
            settings = optimizationSettingsDTO {
                iterationCount = 5
            }
        }

        val resultID = CompletableDeferred<java.util.UUID>()

        //act
        val requestFlow = service.startOptimization(startRequest)

        requestFlow.collect { optimizerRequest ->

            //this function is called by the optimizer...
            when(optimizerRequest.purposeCase!!) {
                // the optimizer is asking for an input vector to be simulated and its results sent back
                EVALUATION_REQUEST -> {

                    //read the input vector from the message provided by the optimizer
                    val inputVector = optimizerRequest.evaluationRequest!!.inputVectorMap.toMap()

                    //send a status update, (this is optional, but encouraged)
                    val message = StatusMessageCommandDTO.newBuilder()
                        .setMessage("evaluating $inputVector!")
                        .build()
                    service.offerEvaluationStatusMessage(message)

                    //compute the result
                    val result = inputVector.values.sumByDouble { it } / 2.0

                    val response = SimulationEvaluationCompletedResponseDTO.newBuilder()
                        .setName("asdf")
                        .putAllOutputVector(mapOf("f1" to result))
                        .build()

                    //send the response --this is a successful optimization
                    service.offerSimulationResult(response)

                    Unit
                }
                // this is provided by the optimizer when it wishes to preempt the simulation.
                // this can happen because of timeout or a stopOptimization request.
                CANCEL_REQUEST -> {
                    Unit //noop --this is a legal implementation in any situation for cancellation.
                }
                // this is provided by the optimizer at the start of each optimization run.
                // It is to inform the simulation that the optimization has started
                OPTIMIZATION_STARTED_NOTIFICATION -> Unit // noop
                // and similarly the optimization has finished
                OPTIMIZATION_FINISHED_NOTIFICATION -> {
                    resultID.complete(optimizerRequest.optimizationFinishedNotification.runID.toUUID())
                    Unit
                }
                // this is called by the optimizer when the provided problem definition is not valid.
                // each problem will appear in the issuesList
                OPTIMIZATION_NOT_STARTED_NOTIFICATION -> {
                    TODO("optimization didn't start because: ${optimizerRequest.optimizationNotStartedNotification.issuesList.joinToString()}")
                }
                PURPOSE_NOT_SET -> TODO("unknown request $optimizerRequest")
                DESIGN_ITERATION_COMPLETED_NOTIFICATION -> {
                    Unit
                }
            } as Any
        }

        val id = resultID.await()

        //assert
        val request = optimizationResultsQueryDTO {
            runID = id.toRunIDDTO()
        }
        val results = service.requestRunResult(request)

        assertThat(results).isNotNull()
        assertThat(results.pointsList.toList()).hasSize(5)
        assertThat(results.pointsList.filter { it.isFrontier }).hasSize(1)

        //check that the constraint wasn't violated on any of the points
        for(point in results.pointsList){
            assertThat(point.inputsList.first(/*the value for x1*/))
                .describedAs("the value for x1 in the point $point")
                .isLessThan(point.inputsList.last(/*the value for x2*/))
        }
    }

    @Test
    fun `when using seed data should run appropriately`() = runBlocking<Unit>() {
        //act
        val startRequest = startOptimizationCommandDTO {
            problemDefinition = problemDefinitionDTO {
                inputs += inputParameterDTO {
                    name = "x1"
                    continuous = continuousDTO {
                        lowerBound = 1.0
                        upperBound = 5.0
                    }
                }
                inputs += inputParameterDTO {
                    name = "x2"
                    continuous = continuousDTO {
                        lowerBound = 1.0
                        upperBound = 5.0
                    }
                }
                evaluables += evaluableNodeDTO {
                    simulation = simulationNodeDTO {
                        autoMap = true
                        inputs += simulationInputParameterDTO { name = "x1" }
                        inputs += simulationInputParameterDTO { name = "x2" }
                        outputs += simulationOutputParameterDTO { name = "f1" }
                    }
                }
            }
            settings = optimizationSettingsDTO {
                iterationCount = 5
            }
            seedPoints += seedRowDTO {
                inputs += listOf(2.0, 3.0)
                outputs += 2.5
            }
        }

        val resultID = CompletableDeferred<java.util.UUID>()
        var results: OptimizationResultsResponseDTO? = null

        //act
        service.startOptimization(startRequest).collect { optimizerRequest ->
            //this function is called by the optimizer...
            when(optimizerRequest.purposeCase!!) {
                // the optimizer is asking for an input vector to be simulated and its results sent back
                EVALUATION_REQUEST -> {

                    val result = optimizerRequest.evaluationRequest!!.inputVectorMap.values.sumByDouble { it } / 2.0

                    val response = SimulationEvaluationCompletedResponseDTO.newBuilder()
                        .setName("asdf")
                        .putAllOutputVector(mapOf("f1" to result))
                        .build()

                    //send the response --this is a successful optimization
                    service.offerSimulationResult(response)

                    Unit
                }
                CANCEL_REQUEST -> Unit
                OPTIMIZATION_STARTED_NOTIFICATION -> Unit // noop
                OPTIMIZATION_FINISHED_NOTIFICATION -> {
                    val id = optimizerRequest.optimizationFinishedNotification.runID

                    results = service.requestRunResult(optimizationResultsQueryDTO { runID = id })

                    resultID.complete(id.toUUID())
                    Unit
                }
                OPTIMIZATION_NOT_STARTED_NOTIFICATION -> {
                    TODO("optimization didn't start because: ${optimizerRequest.optimizationNotStartedNotification.issuesList.joinToString()}")
                }
                PURPOSE_NOT_SET -> TODO("unknown request $optimizerRequest")
                DESIGN_ITERATION_COMPLETED_NOTIFICATION -> {
                    Unit
                }
            } as Any
        }
        val id = resultID.await()

        //assert
        val firstPoint = results!!.pointsList.first()
        assertThat(firstPoint).isEqualTo(designRowDTO {
            inputs += listOf(2.0, 3.0)
            outputs += 2.5
            isFeasible = true
            isFrontier = firstPoint.isFrontier
        })
    }

    @Test
    fun `when starting and stopping using notifications should produce multiple optimizations`() = runBlocking<Unit> {
        val startCommand = startOptimizationCommandDTO {
            problemDefinition = problemDefinitionDTO {
                inputs += inputParameterDTO {
                    name = "x1"
                    continuous = continuousDTO {
                        lowerBound = 5.0
                        upperBound = 15.0
                    }
                }
                inputs += inputParameterDTO {
                    name = "x2"
                    continuous = continuousDTO {
                        lowerBound = 15.0
                        upperBound = 25.0
                    }
                }
                evaluables += evaluableNodeDTO {
                    transform = babelScalarNodeDTO {
                        outputName = "f1"
                        scalarExpression = "x1 + x2"
                    }
                }
            }
        }

        val iterations = ArrayList<DesignRowDTO>()

        //act
        val collector = FlowCollector<OptimizerGeneratedQueryDTO> { optimizerMessage ->
            when (optimizerMessage.purposeCase) {
                EVALUATION_REQUEST -> TODO("$optimizerMessage")
                CANCEL_REQUEST -> TODO("$optimizerMessage")
                OPTIMIZATION_STARTED_NOTIFICATION -> Unit
                OPTIMIZATION_FINISHED_NOTIFICATION -> Unit
                OPTIMIZATION_NOT_STARTED_NOTIFICATION -> TODO("$optimizerMessage")
                DESIGN_ITERATION_COMPLETED_NOTIFICATION -> {
                    iterations += optimizerMessage.designIterationCompletedNotification.designPoint

                    if (iterations.size % 5 == 0) {
                        service.stopOptimization(stopOptimizationCommandDTO {})
                    }

                    Unit
                }
                null, PURPOSE_NOT_SET -> TODO("$optimizerMessage")
            } as Any
        }
        val firstRun = service.startOptimization(startCommand).collect(collector)
        val secondRun = service.startOptimization(startCommand).collect(collector)

        assertThat(iterations.size == 10)
    }

    @Test
    fun `when running with lots of columns should maintain correct order`() = runBlocking<Unit> {

        // setup
        val startCommand = startOptimizationCommandDTO {
            problemDefinition = problemDefinitionDTO {
                inputs += inputParameterDTO {
                    name = "x1"
                    continuous = continuousDTO {
                        lowerBound = 1.0
                        upperBound = 2.0
                    }
                }
                inputs += inputParameterDTO {
                    name = "x2"
                    continuous = continuousDTO {
                        lowerBound = 2.0
                        upperBound = 3.0
                    }
                }
                evaluables += evaluableNodeDTO {
                    transform = babelScalarNodeDTO {
                        outputName = "i3"
                        scalarExpression = "x1-1.0 + 3.0"
                    }
                }
                evaluables += evaluableNodeDTO {
                    transform = babelScalarNodeDTO {
                        outputName = "i4"
                        scalarExpression = "x2-2.0 + 4.0"
                    }
                }
                evaluables += evaluableNodeDTO {
                    transform = babelScalarNodeDTO {
                        outputName = "f5"
                        scalarExpression = "(i3-3 + i4-4)/2 + 5.0"
                    }
                }
                evaluables += evaluableNodeDTO {
                    constraint = babelConstraintNodeDTO {
                        outputName = "c1"
                        booleanExpression = "i3 < i4"
                    }
                }
            }

            settings = optimizationSettingsDTO {
                iterationCount = 5
            }
        }

        // act
        var givenRunId: UUID? = null
        service.startOptimization(startCommand).collect { optimizerMessage ->
            when (optimizerMessage.purposeCase) {
                EVALUATION_REQUEST -> TODO("$optimizerMessage")
                CANCEL_REQUEST -> TODO("$optimizerMessage")
                OPTIMIZATION_STARTED_NOTIFICATION -> {
                    givenRunId = optimizerMessage.optimizationStartedNotification.runID.toUUID()
                }
                OPTIMIZATION_FINISHED_NOTIFICATION -> Unit
                OPTIMIZATION_NOT_STARTED_NOTIFICATION -> TODO("$optimizerMessage")
                DESIGN_ITERATION_COMPLETED_NOTIFICATION -> Unit
                null, PURPOSE_NOT_SET -> TODO("$optimizerMessage")
            }
        }

        val results = service.requestRunResult(optimizationResultsQueryDTO { runID = givenRunId!!.toRunIDDTO() })

        // assert
        val matcher = listOf(1.0 .. 2.0, 2.0 .. 3.0, 3.0 .. 4.0, 4.0 .. 5.0, 5.0 .. 6.0, Double.MIN_VALUE .. 0.0)

        for(point in results.pointsList){
            assertThat(point.matches(matcher)).describedAs("$point matches $matcher")
        }
    }

    @Test
    fun `when cancelling early and calling requestRunResult on each iteration should function properly`() = runBlocking<Unit> {
        // setup
        val startOptimizationRequest = startOptimizationCommandDTO {
            problemDefinition = problemDefinitionDTO {
                inputs += inputParameterDTO {
                    name = "x1"
                    continuous = continuousDTO {
                        lowerBound = 1.0
                        upperBound = 5.0
                    }
                }
                evaluables += evaluableNodeDTO {
                    simulation = simulationNodeDTO {
                        autoMap = true
                        inputs += simulationInputParameterDTO { name = "x1" }
                        outputs += simulationOutputParameterDTO { name = "f1" }
                    }
                }
            }
        }
        var givenRunId: UUID? = null
        var iterationNo = 0
        var results = emptyList<List<DesignRowDTO>>()

        // act
        service.startOptimization(startOptimizationRequest).collect { optimizerMessage ->
            when (optimizerMessage.purposeCase) {
                OPTIMIZATION_NOT_STARTED_NOTIFICATION -> TODO("$optimizerMessage")
                OPTIMIZATION_STARTED_NOTIFICATION -> {
                    givenRunId = optimizerMessage.optimizationStartedNotification.runID.toUUID()
                }
                EVALUATION_REQUEST -> {
                    val response = service.requestRunResult(optimizationResultsQueryDTO { runID = givenRunId!!.toRunIDDTO() })
                    results = results.plusElement(response.pointsList.toList())

                    service.offerSimulationResult(simulationEvaluationCompletedResponseDTO {
                        outputVector.putAll(mapOf("f1" to 42.0))
                    })
                }
                CANCEL_REQUEST -> {
                    // would be nice of the optimizer would listen to back pressure on the iteration event
                    // but it doesnt, so by the teime you call stop from ITERATION_COMPLETED_NOTIFICATION
                    // it might have already moved to the next iteration.
                    println("cancelled!")
                }
                DESIGN_ITERATION_COMPLETED_NOTIFICATION -> {
                    iterationNo += 1

                    if(iterationNo >= 5){
                        service.stopOptimization(stopOptimizationCommandDTO{})
                    }
                }
                OPTIMIZATION_FINISHED_NOTIFICATION -> Unit
                null, PURPOSE_NOT_SET -> TODO("$optimizerMessage")
            }
        }

        // assert
        assertThat(results.size).isGreaterThanOrEqualTo(iterationNo)
    }

    @Test
    fun `when calling startOptimization when previous optimization is not completed should provide good error message`() = runBlocking<Unit> {

        val startOptimizationRequest = startOptimizationCommandDTO {
            problemDefinition = problemDefinitionDTO {
                inputs += inputParameterDTO {
                    name = "x1"
                    continuous = continuousDTO {
                        lowerBound = 1.0
                        upperBound = 5.0
                    }
                }
                evaluables += evaluableNodeDTO {
                    simulation = simulationNodeDTO {
                        autoMap = true
                        inputs += simulationInputParameterDTO { name = "x1" }
                        outputs += simulationOutputParameterDTO { name = "f1" }
                    }
                }
            }
        }

        val startedMutex = Mutex(true)
        val messages = mutableListOf<String>()

        launch {
            // act 1: seetup an actually running optimization;
            // we'll just hang it on the first evaluation
            service.startOptimization(startOptimizationRequest).collect { optimizerMessage ->
                when(optimizerMessage.purposeCase) {
                    OPTIMIZATION_STARTED_NOTIFICATION -> startedMutex.unlock()
                    OPTIMIZATION_FINISHED_NOTIFICATION -> Unit
                    EVALUATION_REQUEST -> {
                        //we'll actually let it hang on the first iteration
                    }
                    CANCEL_REQUEST -> {
                        // when we get a cancellation, which we get from stop, I'll stop it
                        service.offerErrorResult(simulationEvaluationErrorResponseDTO{})
                    }
                    else -> TODO("unknown $optimizerMessage")
                }
            }
        }
        startedMutex.lock()
        // act 2: attempt to start a second optimization. It should return with NOT_STARTED
        service.startOptimization(startOptimizationRequest).collect { optimizerMessage ->
            when(optimizerMessage.purposeCase){
                OPTIMIZATION_NOT_STARTED_NOTIFICATION -> {
                    messages += optimizerMessage.optimizationNotStartedNotification.issuesList
                }
                else -> TODO("expected not started, but got $optimizerMessage")
            }
        }
        service.stopOptimization(stopOptimizationCommandDTO{})

        assertThat(messages).isEqualTo(listOf("Optimization already running"))
    }

    @Test
    fun `when using postprocessing on simulation output and also an expensive constraint should work properly`() = runBlocking<Unit> {
        val startOptimizationRequest = startOptimizationCommandDTO {
            problemDefinition = problemDefinitionDTO {
                inputs += inputParameterDTO {
                    name = "x1"
                    continuous = continuousDTO {
                        lowerBound = 0.0
                        upperBound = 1.0
                    }
                }
                evaluables += evaluableNodeDTO {
                    simulation = simulationNodeDTO {
                        autoMap = true
                        inputs += simulationInputParameterDTO { name = "x1" }
                        outputs += simulationOutputParameterDTO { name = "i1" }
                    }
                }
                evaluables += evaluableNodeDTO {
                    transform = babelScalarNodeDTO {
                        outputName = "f1"
                        scalarExpression = "i1 + 1.0"
                    }
                }
                evaluables += evaluableNodeDTO {
                    constraint = babelConstraintNodeDTO {
                        outputName = "c1"
                        booleanExpression = "i1 <= 2.5"
                    }
                }
            }

            settings = optimizationSettingsDTO {
                iterationCount = 5
            }
        }

        val designs = mutableListOf<DesignRowDTO>()

        service.startOptimization(startOptimizationRequest).collect { optimizerMessage ->
            when(optimizerMessage.purposeCase){
                EVALUATION_REQUEST -> {
                    val response = simulationEvaluationCompletedResponseDTO {
                        val x1 = optimizerMessage.evaluationRequest.inputVectorMap.values.single()
                        vector = outputVectorDTO {
                            entries["i1"] = x1 + 1.0

//                            fail; //urgh, linkage errors in bable => kotlinx.collections.immutable
                            // go update babel to use 0.3.8 of kotlinx.
                        }
                    }
                    service.offerSimulationResult(response)

                    Unit
                }
                CANCEL_REQUEST -> Unit
                OPTIMIZATION_STARTED_NOTIFICATION -> Unit
                OPTIMIZATION_FINISHED_NOTIFICATION -> Unit
                OPTIMIZATION_NOT_STARTED_NOTIFICATION -> Unit
                DESIGN_ITERATION_COMPLETED_NOTIFICATION -> {
                    designs += optimizerMessage.designIterationCompletedNotification.designPoint
                }
                null, PURPOSE_NOT_SET -> TODO()
            } as Any
        }

        val constraintRange = /* c1: f1 <= 2.5 -> c1 := f1-2.5<=0 -> c1 = f1-2.5, which is [2.0..3.0]-2.5, */ -0.5..+0.5
        val expectedRanges = listOf(0.0..1.0, 1.0..2.0, 2.0..3.0, constraintRange)
        val firstMissmatchingDesign = designs.firstOrNull { !it.matches(expectedRanges) }

        assertThat(firstMissmatchingDesign)
            .isNull()
    }

    @Test
//    @Disabled("this is only to be run functionally")
    fun `should backpressure on iteration completed event`() = runBlocking<Unit> {

        // ook this ones tricky,
        // basically if we dont have backpressure through simulations,
        // IE: if we're running a pure math optimization,
        // and we cant slow the optimizer down waiting for a simulation,
        // the optimizer can run while the client takes a long time to update.
        // in this situation the optimizer will generate more points than the
        //
        // this seems to indicate we _might_ get it for free
        // https://medium.com/@georgeleung_7777/seamless-backpressure-handling-with-grpc-kotlin-a6f99cab4b95
        //
        // ok, similarly, we could interpret a stopOptimization call as an evaluation cancellation.
        // BUT: we would need some mechanism to identify a straggler-evaluation completed response.
        // one solution could be to have each EvaluationCompleted have both a runID and an iterationID.
        // THIS ALSO ENABLES PARALLELISM, since each evaluation response would have an effective composite primary key
        //
        // worth noting, one natural back pressure device we have is simulation evaluations cna stall the optimization
        // but for pure math functions, there is no backpressure

        val startOptimizationRequest = startOptimizationCommandDTO {
            problemDefinition = problemDefinitionDTO {
                inputs += inputParameterDTO {
                    name = "x1"
                    continuous = continuousDTO {
                        lowerBound = 1.0
                        upperBound = 5.0
                    }
                }
                evaluables += evaluableNodeDTO {
                    transform = babelScalarNodeDTO {
                        scalarExpression = "x1 + 1.0"
                    }
                }
            }
        }

        val mutex = Mutex(locked = true)
        val startedOptimization: Flow<OptimizerGeneratedQueryDTO> = service.startOptimization(startOptimizationRequest)

        var iterationCount = 0
        val messages = mutableListOf<String>()
        var stopped = false

        startedOptimization.collect { optimizerMessage ->
            when(optimizerMessage.purposeCase){
                EVALUATION_REQUEST -> { TODO() }
                CANCEL_REQUEST -> Unit
                OPTIMIZATION_STARTED_NOTIFICATION -> Unit
                OPTIMIZATION_FINISHED_NOTIFICATION -> {
                    messages += "optimization finished"
                }
                OPTIMIZATION_NOT_STARTED_NOTIFICATION -> Unit
                DESIGN_ITERATION_COMPLETED_NOTIFICATION -> {
                    messages += "notified of completion"
                    if(!stopped) {
                        val message = withTimeoutOrNull(20) { mutex.lock(); "acquired-lock" } ?: "timed-out."
                        messages += message
                    }

                    Unit
                }
                null, PURPOSE_NOT_SET -> TODO()
            } as Any

            iterationCount += 1

            if(iterationCount == 5) {
                stopped = true
                messages += "stop-optimization"
                service.stopOptimization(stopOptimizationCommandDTO{})
            }
        }

        // note coming up with an oracle here is really hard,
        // but the thing to note is that after the stop optimization
        // **we continue to receive new points**
        // even after the stop optimization
        assertThat(messages.size).isGreaterThanOrEqualTo(100) //the optimizer ran _way_ ahead of us
        assertThat(messages).contains("stop-optimization")
        assertThat(messages.takeLast(5)).isEqualTo(listOf(
            "notified of completion",
            "notified of completion",
            "notified of completion",
            "notified of completion",
            "optimization finished"
        ))
    }

    @Test
    fun `when offering a simulation result with an incorrect number of columns should fail on unary call`(){
        TODO("this was discovered in testing ")
        //requires API change

//            fail; // we should validate the schema of that message here.
//            // in your test, the client is failing to include a value for c1.
//            // that should error here.
//            // design decision: should you put the response on the mssage
//            // or demux it from the output channel?
//            // another decision: do we need to do the same validation for the error result?
    }

    @Test
    fun `when call throws exception should be printed`(){
        // soo kotlin grpc is swallowing exceptions
        // this issue is to insert someting that wraps our implementation calls
        // to catch and print excpetions

        //some code I wrote
        // note that you can put extra content on errors,
        // the default error message type has a field:
        // repeated Any details = 5;
        // you can encode any message type to Any,

//    catch (ex: Throwable) {
//        val metadata = Metadata()
//
//        val formattedErrorMessage = Status.newBuilder()
//            .setCode(io.grpc.Status.Code.ABORTED.value())
//            .setMessage(ex::class.simpleName + " : " + ex.message)
//            .addAllDetails(ex.stackTraceToString().lines().map { it.toProtobufAny() })
//            .build()
//
//        fail; //oook, well somebody is swallowing these exceptions
//        // also check in on https://github.com/grpc/grpc-java/issues/8678
//
//        metadata.put(ProtoUtils.keyForProto(Status.getDefaultInstance()), formattedErrorMessage)
//        throw io.grpc.Status.OUT_OF_RANGE.asRuntimeException(metadata)
//    }

        TODO()
    }
}

private val FourSigFigFixedPointFormat = DecimalFormat("##.##")

fun List<DesignRowDTO>.printTable(): String = joinToString("\n") { row -> buildString {
    append("inputs: ")
    append(row.inputsList.joinToString(", ", transform = FourSigFigFixedPointFormat::format))
    append(", ")
    append("outputs: ")
    append(row.outputsList.joinToString(", ", transform = FourSigFigFixedPointFormat::format))
}}

private fun DesignRowDTO.matches(ranges: List<ClosedRange<Double>>): Boolean {
    val fullCoordinatesList = inputsList + outputsList
    require(fullCoordinatesList.size == ranges.size)
    for((index, value) in fullCoordinatesList.withIndex()){
        val range = ranges[index]
        if(value !in range) return false
    }

    return true
}