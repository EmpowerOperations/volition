package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import com.empowerops.volition.dto.OptimizerGeneratedQueryDTO.PurposeCase.*
import com.google.protobuf.UInt32Value
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
    private lateinit var service: UnaryOptimizerGrpc.UnaryOptimizerStub

    @BeforeEach
    fun setupServer(){
        server = mainAsync(arrayOf())!!

        val channel = ManagedChannelBuilder
                .forAddress("localhost", 5550)
                .usePlaintext()
                .build()

        service = UnaryOptimizerGrpc.newStub(channel)
    }

    @AfterEach
    fun teardownServer() = runBlocking {
        server.cancelAndJoin()
    }


    //this was the very first sanity test I added.
    @Test fun `when running with --version should print version`() = runBlocking<Unit> {
        mainAsync(arrayOf("--version"))?.join()

        val str = consoleAltBytes.toString("utf-8")

        assertThat(str.trim()).contains("Volition API 1.2")
    }

    @Test fun `when running a single var single simulation optimization should optimize normally`() = runBlocking<Unit> {
        //act
        val fifthIteration = CompletableDeferred<Unit>()

        // this is the API equivalent of an OPYL file.
        // here we specify the input parameters, and their variable bounds.
        // this is just about the smallest optimization possible:
        // A single variable, single objective, unconstrained function.
        val startOptimizationRequest = StartOptimizationCommandDTO.newBuilder()
                .setProblemDefinition(ProblemDefinitionDTO.newBuilder()
                        .addInputs(PrototypeInputParameterDTO.newBuilder()
                                .setName("x1")
                                .setContinuous(ContinuousDTO.newBuilder()
                                        .setLowerBound(1.0)
                                        .setUpperBound(5.0)
                                        .build()
                                )
                                .build()
                        )
                        .addObjectives(PrototypeOutputParameterDTO.newBuilder()
                                .setName("f1")
                                .build()
                        )
                        .build()
                )
                .addNodes(SimulationNodeDTO.newBuilder()
                        .setAutoMap(true) //by default, optimizers will not assume that a tool's input 'x1'
                                          // is the same as the optimization variable 'x1'. Set 'autoMap' to use a name-matching system.
                                          // some characters are illegal for variable names, eg 'x*1' is not a legal name.
                        .addInputs("x1")
                        .addOutputs("f1")
                        .build()
                )
                .build()

        val parentJob = coroutineContext[Job]!!
        var iterationNo = 1;

        //act
        service.startOptimization(startOptimizationRequest, object: StreamObserver<OptimizerGeneratedQueryDTO>{
            override fun onNext(optimizerRequest: OptimizerGeneratedQueryDTO) = cancelOnException { runBlocking<Unit> {
                val dc: Unit = when(optimizerRequest.purposeCase!!) {
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
                            service::offerEvaluationStatusMessage.send(statusMessage)

                            val result = inputVector.values.sumByDouble { it } / 2.0

                            val response = SimulationEvaluationCompletedResponseDTO.newBuilder()
                                    .setName("asdf")
                                    .putAllOutputVector(mapOf("f1" to result))
                                    .build()

                            service::offerSimulationResult.send(response)
                        }
                        else if (iterationNo > 5){
                            val response = SimulationEvaluationErrorResponseDTO.newBuilder()
                                    .setMessage("already evaluated 5 iterations!")
                                    .build()

                            service::offerErrorResult.send(response)
                        }
                        if(iterationNo == 5){
                            fifthIteration.complete(Unit)
                        }
                        iterationNo += 1

                        Unit
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
            }}

            override fun onError(t: Throwable) {
                t.printStackTrace()
                parentJob.cancel()
            }

            override fun onCompleted() {
                println("registration channel completed!")
            }
        })
        fifthIteration.await()
        // note: with this implementation there is a race condition here.
        // The simulator will release the fifth-iteration lock while (concurrently) moving on to the 6th iteration.
        // thus, you may see a 6th iteration before the stopOptimization call is processed.
        val run = sendAndAwaitResponse(service::stopOptimization)(StopOptimizationCommandDTO.newBuilder().build())

        //assert
        val results = sendAndAwaitResponse(service::requestRunResult)(OptimizationResultsQueryDTO.newBuilder().setRunID(run.runID).build())

        assertThat(results.pointsList.toList()).hasSize(5)
        assertThat(results.pointsList.filter { it.isFrontier }).hasSize(1)
        // this is a very weak set of assertions, you can see more details about whats in these results
        // in the transaction log below.
    }
    /*
    The above produces the following logs:

        [2020-07-02T01:55:32.496] API INBOUND > empowerops.volition.api.UnaryOptimizer/StartOptimization
        problem_definition {
          inputs {
            name: "x1"
            continuous {
              lower_bound: 1.0
              upper_bound: 5.0
            }
          }
          objectives {
            name: "f1"
          }
        }
        nodes {
          auto_map: true
          inputs: "x1"
          outputs: "f1"
        }
        [2020-07-02T01:55:32.523] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        optimization_started_notification {
        }
        [2020-07-02T01:55:32.529] Event > Run Started - ID:f88337e8-776a-4c0d-aef2-9d4f804793e2
        [2020-07-02T01:55:32.540] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        evaluation_request {
          input_vector {
            key: "x1"
            value: 2.3860577215157033
          }
        }
        [2020-07-02T01:55:32.547] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage
        message: "evaluating {x1=2.3860577215157033}!"
        [2020-07-02T01:55:32.549] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage [empty StatusMessageConfirmDTO]
        [2020-07-02T01:55:32.550] Event > New message received: Message(sender=, message=evaluating {x1=2.3860577215157033}!, level=INFO, receiveTime=2020-07-02T01:55:32.550)
        [2020-07-02T01:55:32.557] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult
        name: "asdf"
        outputVector {
          key: "f1"
          value: 1.1930288607578516
        }
        [2020-07-02T01:55:32.559] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult [empty SimulationEvaluationResultConfirmDTO]
        [2020-07-02T01:55:32.562] Event > New result received: Success(name=, inputs={x1=2.3860577215157033}, result={f1=1.1930288607578516})
        [2020-07-02T01:55:32.565] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        evaluation_request {
          input_vector {
            key: "x1"
            value: 1.3187490204732786
          }
        }
        [2020-07-02T01:55:32.567] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage
        message: "evaluating {x1=1.3187490204732786}!"
        [2020-07-02T01:55:32.567] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage [empty StatusMessageConfirmDTO]
        [2020-07-02T01:55:32.567] Event > New message received: Message(sender=, message=evaluating {x1=1.3187490204732786}!, level=INFO, receiveTime=2020-07-02T01:55:32.567)
        [2020-07-02T01:55:32.569] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult
        name: "asdf"
        outputVector {
          key: "f1"
          value: 0.6593745102366393
        }
        [2020-07-02T01:55:32.570] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult [empty SimulationEvaluationResultConfirmDTO]
        [2020-07-02T01:55:32.570] Event > New result received: Success(name=, inputs={x1=1.3187490204732786}, result={f1=0.6593745102366393})
        [2020-07-02T01:55:32.571] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        evaluation_request {
          input_vector {
            key: "x1"
            value: 3.9289716608882785
          }
        }
        [2020-07-02T01:55:32.573] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage
        message: "evaluating {x1=3.9289716608882785}!"
        [2020-07-02T01:55:32.573] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage [empty StatusMessageConfirmDTO]
        [2020-07-02T01:55:32.573] Event > New message received: Message(sender=, message=evaluating {x1=3.9289716608882785}!, level=INFO, receiveTime=2020-07-02T01:55:32.573)
        [2020-07-02T01:55:32.575] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult
        name: "asdf"
        outputVector {
          key: "f1"
          value: 1.9644858304441393
        }
        [2020-07-02T01:55:32.575] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult [empty SimulationEvaluationResultConfirmDTO]
        [2020-07-02T01:55:32.576] Event > New result received: Success(name=, inputs={x1=3.9289716608882785}, result={f1=1.9644858304441393})
        [2020-07-02T01:55:32.595] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        evaluation_request {
          input_vector {
            key: "x1"
            value: 3.220131192218629
          }
        }
        [2020-07-02T01:55:32.600] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage
        message: "evaluating {x1=3.220131192218629}!"
        [2020-07-02T01:55:32.600] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage [empty StatusMessageConfirmDTO]
        [2020-07-02T01:55:32.601] Event > New message received: Message(sender=, message=evaluating {x1=3.220131192218629}!, level=INFO, receiveTime=2020-07-02T01:55:32.601)
        [2020-07-02T01:55:32.604] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult
        name: "asdf"
        outputVector {
          key: "f1"
          value: 1.6100655961093144
        }
        [2020-07-02T01:55:32.604] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult [empty SimulationEvaluationResultConfirmDTO]
        [2020-07-02T01:55:32.604] Event > New result received: Success(name=, inputs={x1=3.220131192218629}, result={f1=1.6100655961093144})
        [2020-07-02T01:55:32.605] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        evaluation_request {
          input_vector {
            key: "x1"
            value: 2.4629973268117906
          }
        }
        [2020-07-02T01:55:32.607] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage
        message: "evaluating {x1=2.4629973268117906}!"
        [2020-07-02T01:55:32.607] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage [empty StatusMessageConfirmDTO]
        [2020-07-02T01:55:32.607] Event > New message received: Message(sender=, message=evaluating {x1=2.4629973268117906}!, level=INFO, receiveTime=2020-07-02T01:55:32.607)
        [2020-07-02T01:55:32.609] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult
        name: "asdf"
        outputVector {
          key: "f1"
          value: 1.2314986634058953
        }
        [2020-07-02T01:55:32.609] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult [empty SimulationEvaluationResultConfirmDTO]
        [2020-07-02T01:55:32.610] Event > New result received: Success(name=, inputs={x1=2.4629973268117906}, result={f1=1.2314986634058953})
        [2020-07-02T01:55:32.610] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        evaluation_request {
          input_vector {
            key: "x1"
            value: 3.5308031856032955
          }
        }
        [2020-07-02T01:55:32.617] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferErrorResult
        message: "already evaluated 5 iterations!"
        [2020-07-02T01:55:32.618] API INBOUND > empowerops.volition.api.UnaryOptimizer/StopOptimization [empty StopOptimizationCommandDTO]
        [2020-07-02T01:55:32.622] Event > Stop Requested - ID:f88337e8-776a-4c0d-aef2-9d4f804793e2
        [2020-07-02T01:55:32.624] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        cancel_request {
        }
        [2020-07-02T01:55:32.626] Event > New result received: Error(name=, inputs={x1=3.5308031856032955}, exception=already evaluated 5 iterations!)
        [2020-07-02T01:55:32.626] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferErrorResult [empty SimulationEvaluationErrorConfirmDTO]
        [2020-07-02T01:55:32.631] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        optimization_finished_notification {
        }
        [2020-07-02T01:55:32.633] Event > Run Stopped - ID:f88337e8-776a-4c0d-aef2-9d4f804793e2
        [2020-07-02T01:55:32.637] API CLOSED > empowerops.volition.api.UnaryOptimizer/StartOptimization
        registration channel completed!
        [2020-07-02T01:55:32.640] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/StopOptimization
        run_ID: "f88337e8-776a-4c0d-aef2-9d4f804793e2"
        [2020-07-02T01:55:32.648] API INBOUND > empowerops.volition.api.UnaryOptimizer/RequestRunResult
        run_ID: "f88337e8-776a-4c0d-aef2-9d4f804793e2"
        [2020-07-02T01:55:32.659] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/RequestRunResult
        run_ID: "f88337e8-776a-4c0d-aef2-9d4f804793e2"
        input_columns: "x1"
        output_columns: "f1"
        points {
          inputs: 2.3860577215157033
          outputs: 1.1930288607578516
          is_feasible: true
        }
        points {
          inputs: 1.3187490204732786
          outputs: 0.6593745102366393
          is_feasible: true
        }
        points {
          inputs: 3.9289716608882785
          outputs: 1.9644858304441393
          is_feasible: true
        }
        points {
          inputs: 3.220131192218629
          outputs: 1.6100655961093144
          is_feasible: true
        }
        points {
          inputs: 2.4629973268117906
          outputs: 1.2314986634058953
          is_feasible: true
        }
        frontier {
          inputs: 1.3187490204732786
          outputs: 0.6593745102366393
          is_feasible: true
        }

     */

    @Test fun `when running an optimization with a constraint that constraint should be respected while optimizing`() = runBlocking<Unit> {
        //act
        val fifthIteration = CompletableDeferred<Unit>()

        val startRequest = StartOptimizationCommandDTO.newBuilder()
                .setProblemDefinition(ProblemDefinitionDTO.newBuilder()
                        // this test is more complex than the above test because:
                        // 1. it uses two variables instead of one
                        // 2. it uses a constraint
                        .addAllInputs(listOf(
                                PrototypeInputParameterDTO.newBuilder()
                                        .setName("x1")
                                        .setContinuous(ContinuousDTO.newBuilder()
                                                .setLowerBound(1.0)
                                                .setUpperBound(5.0)
                                                .build()
                                        )
                                        .build(),
                                PrototypeInputParameterDTO.newBuilder()
                                        .setName("x2")
                                        .setContinuous(ContinuousDTO.newBuilder()
                                                .setLowerBound(1.0)
                                                .setUpperBound(5.0)
                                                .build()
                                        )
                                        .build()
                        ))
                        .addAllObjectives(listOf(
                                PrototypeOutputParameterDTO.newBuilder()
                                        .setName("f1")
                                        .build()
                        ))
                        .addConstraints(BabelConstraintDTO.newBuilder()
                                .setOutputName("c1")
                                .setBooleanExpression("x1 < x2")
                                .build())
                        .build()
                )
                .addNodes(SimulationNodeDTO.newBuilder()
                        .setAutoMap(true)
                        .addInputs("x1").addInputs("x2")
                        .addOutputs("f1")
                        .build()
                )
                .build()

        var finishedPoints: List<DesignRowDTO> = emptyList()

        //act
        service.startOptimization(startRequest, object: StreamObserver<OptimizerGeneratedQueryDTO>{
            // the second argument to this function, the stream observer,
            // is a callback offered by the client to the optimizer
            // it is called by the optimizer when the optimizer makes an evaluation request
            // or feels it should notify the client of some change.

            val parentJob = coroutineContext[Job]!!
            var iterationNo = 1;

            override fun onNext(optimizerRequest: OptimizerGeneratedQueryDTO) = cancelOnException { runBlocking<Unit> {

                //this function is called by the optimizer...
                when(optimizerRequest.purposeCase!!) {
                    // the optimizer is asking for an input vector to be simulated and its results sent back
                    EVALUATION_REQUEST -> {
                        if(iterationNo <= 5) {

                            //read the input vector from the message provided by the optimizer
                            val inputVector = optimizerRequest.evaluationRequest!!.inputVectorMap.toMap()

                            //send a status update, (this is optional, but encouraged)
                            val message = StatusMessageCommandDTO.newBuilder()
                                    .setMessage("evaluating $inputVector!")
                                    .build()
                            service::offerEvaluationStatusMessage.send(message)

                            //compute the result
                            val result = inputVector.values.sumByDouble { it } / 2.0

                            val response = SimulationEvaluationCompletedResponseDTO.newBuilder()
                                    .setName("asdf")
                                    .putAllOutputVector(mapOf("f1" to result))
                                    .build()

                            //send the response --this is a successful optimization
                            service::offerSimulationResult.send(response)
                        }
                        if(iterationNo == 5){
                            // this code exists so that the test only runs for 5 iterations.
                            fifthIteration.complete(Unit)
                        }
                        else if (iterationNo >= 5){
                            // if we're attempting to evaluate a 6th point,
                            // fail the evaluation by sending an errorResult.
                            // you can use this same strategy for things like meshing failure or other simulation errors.
                            val response = SimulationEvaluationErrorResponseDTO.newBuilder().setMessage("already evaluated 5 iterations!").build()
                            service::offerErrorResult.send(response)
                        }
                        iterationNo += 1

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
                    OPTIMIZATION_FINISHED_NOTIFICATION -> Unit // noop
                    // this is called by the optimizer when the provided problem definition is not valid.
                    // each problem will appear in the issuesList
                    OPTIMIZATION_NOT_STARTED_NOTIFICATION -> {
                        TODO("optimization didn't start because: ${optimizerRequest.optimizationNotStartedNotification.issuesList.joinToString()}")
                    }
                    PURPOSE_NOT_SET -> TODO("unknown request $optimizerRequest")
                    DESIGN_ITERATION_COMPLETED_NOTIFICATION -> finishedPoints += optimizerRequest.designIterationCompletedNotification.designPoint
                } as Any
            }}

            override fun onError(t: Throwable) {
                t.printStackTrace()
                parentJob.cancel()
            }

            override fun onCompleted() {
                println("registration channel completed!")
            }
        })

        fifthIteration.await()
        val run = service::stopOptimization.send(
                StopOptimizationCommandDTO.newBuilder().build()
        )

        //assert
        val results = service::requestRunResult.send(OptimizationResultsQueryDTO.newBuilder().setRunID(run.runID).build())

        assertThat(results).isNotNull()
        assertThat(results.pointsList.toList()).hasSize(5)
        assertThat(results.pointsList.filter { it.isFrontier }).hasSize(1)

        assertThat(finishedPoints).isEqualTo(results.pointsList)

        //check that the constraint wasn't violated on any of the points
        for(point in results.pointsList){
            assertThat(point.inputsList.first(/*the value for x1*/))
                    .describedAs("the value for x1 in the point $point")
                    .isLessThan(point.inputsList.last(/*the value for x2*/))
        }
    }
    /*  output from this test:

        [2020-07-02T02:05:56.059] API INBOUND > empowerops.volition.api.UnaryOptimizer/StartOptimization
        problem_definition {
          inputs {
            name: "x1"
            continuous {
              lower_bound: 1.0
              upper_bound: 5.0
            }
          }
          inputs {
            name: "x2"
            continuous {
              lower_bound: 1.0
              upper_bound: 5.0
            }
          }
          objectives {
            name: "f1"
          }
          constraints {
            output_name: "c1"
            boolean_expression: "x1 < x2"
          }
        }
        nodes {
          auto_map: true
          inputs: "x1"
          inputs: "x2"
          outputs: "f1"
        }
        [2020-07-02T02:05:56.170] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        optimization_started_notification {
          run_ID {
            value: "d7863580-deae-4e47-9cbb-9e5047e4b5a8"
          }
        }
        [2020-07-02T02:05:56.190] Event > Run Started - ID:d7863580-deae-4e47-9cbb-9e5047e4b5a8
        [2020-07-02T02:05:56.214] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        evaluation_request {
          input_vector {
            key: "x1"
            value: 1.1873350226522947
          }
          input_vector {
            key: "x2"
            value: 3.4525398961878757
          }
        }
        [2020-07-02T02:05:56.221] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage
        message: "evaluating {x1=1.1873350226522947, x2=3.4525398961878757}!"
        [2020-07-02T02:05:56.224] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage [empty StatusMessageConfirmDTO]
        [2020-07-02T02:05:56.225] Event > New message received: Message(sender=, message=evaluating {x1=1.1873350226522947, x2=3.4525398961878757}!, level=INFO, receiveTime=2020-07-02T02:05:56.225)
        [2020-07-02T02:05:56.233] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult
        name: "asdf"
        output_vector {
          key: "f1"
          value: 2.3199374594200854
        }
        [2020-07-02T02:05:56.236] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult [empty SimulationEvaluationResultConfirmDTO]
        [2020-07-02T02:05:56.239] Event > New result received: Success(name=, inputs={x1=1.1873350226522947, x2=3.4525398961878757}, result={f1=2.3199374594200854})
        [2020-07-02T02:05:56.242] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        evaluation_request {
          input_vector {
            key: "x1"
            value: 1.4360031551270533
          }
          input_vector {
            key: "x2"
            value: 1.9902563871808439
          }
        }
        [2020-07-02T02:05:56.244] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage
        message: "evaluating {x1=1.4360031551270533, x2=1.9902563871808439}!"
        [2020-07-02T02:05:56.245] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage [empty StatusMessageConfirmDTO]
        [2020-07-02T02:05:56.245] Event > New message received: Message(sender=, message=evaluating {x1=1.4360031551270533, x2=1.9902563871808439}!, level=INFO, receiveTime=2020-07-02T02:05:56.245)
        [2020-07-02T02:05:56.247] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult
        name: "asdf"
        output_vector {
          key: "f1"
          value: 1.7131297711539486
        }
        [2020-07-02T02:05:56.248] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult [empty SimulationEvaluationResultConfirmDTO]
        [2020-07-02T02:05:56.248] Event > New result received: Success(name=, inputs={x1=1.4360031551270533, x2=1.9902563871808439}, result={f1=1.7131297711539486})
        [2020-07-02T02:05:56.249] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        evaluation_request {
          input_vector {
            key: "x1"
            value: 1.5893881828218057
          }
          input_vector {
            key: "x2"
            value: 3.7375279460397097
          }
        }
        [2020-07-02T02:05:56.254] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage
        message: "evaluating {x1=1.5893881828218057, x2=3.7375279460397097}!"
        [2020-07-02T02:05:56.255] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage [empty StatusMessageConfirmDTO]
        [2020-07-02T02:05:56.255] Event > New message received: Message(sender=, message=evaluating {x1=1.5893881828218057, x2=3.7375279460397097}!, level=INFO, receiveTime=2020-07-02T02:05:56.255)
        [2020-07-02T02:05:56.257] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult
        name: "asdf"
        output_vector {
          key: "f1"
          value: 2.663458064430758
        }
        [2020-07-02T02:05:56.258] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult [empty SimulationEvaluationResultConfirmDTO]
        [2020-07-02T02:05:56.258] Event > New result received: Success(name=, inputs={x1=1.5893881828218057, x2=3.7375279460397097}, result={f1=2.663458064430758})
        [2020-07-02T02:05:56.259] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        evaluation_request {
          input_vector {
            key: "x1"
            value: 2.3874267737095676
          }
          input_vector {
            key: "x2"
            value: 4.717997855227804
          }
        }
        [2020-07-02T02:05:56.263] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage
        message: "evaluating {x1=2.3874267737095676, x2=4.717997855227804}!"
        [2020-07-02T02:05:56.263] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage [empty StatusMessageConfirmDTO]
        [2020-07-02T02:05:56.263] Event > New message received: Message(sender=, message=evaluating {x1=2.3874267737095676, x2=4.717997855227804}!, level=INFO, receiveTime=2020-07-02T02:05:56.263)
        [2020-07-02T02:05:56.266] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult
        name: "asdf"
        output_vector {
          key: "f1"
          value: 3.5527123144686854
        }
        [2020-07-02T02:05:56.266] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult [empty SimulationEvaluationResultConfirmDTO]
        [2020-07-02T02:05:56.266] Event > New result received: Success(name=, inputs={x1=2.3874267737095676, x2=4.717997855227804}, result={f1=3.5527123144686854})
        [2020-07-02T02:05:56.267] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        evaluation_request {
          input_vector {
            key: "x1"
            value: 1.4546440223685648
          }
          input_vector {
            key: "x2"
            value: 3.748288340982036
          }
        }
        [2020-07-02T02:05:56.270] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage
        message: "evaluating {x1=1.4546440223685648, x2=3.748288340982036}!"
        [2020-07-02T02:05:56.270] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferEvaluationStatusMessage [empty StatusMessageConfirmDTO]
        [2020-07-02T02:05:56.270] Event > New message received: Message(sender=, message=evaluating {x1=1.4546440223685648, x2=3.748288340982036}!, level=INFO, receiveTime=2020-07-02T02:05:56.270)
        [2020-07-02T02:05:56.277] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult
        name: "asdf"
        output_vector {
          key: "f1"
          value: 2.6014661816753004
        }
        [2020-07-02T02:05:56.278] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferSimulationResult [empty SimulationEvaluationResultConfirmDTO]
        [2020-07-02T02:05:56.278] Event > New result received: Success(name=, inputs={x1=1.4546440223685648, x2=3.748288340982036}, result={f1=2.6014661816753004})
        [2020-07-02T02:05:56.278] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        evaluation_request {
          input_vector {
            key: "x1"
            value: 1.6897836977364764
          }
          input_vector {
            key: "x2"
            value: 2.248593839594565
          }
        }
        [2020-07-02T02:05:56.285] API INBOUND > empowerops.volition.api.UnaryOptimizer/OfferErrorResult
        message: "already evaluated 5 iterations!"
        [2020-07-02T02:05:56.285] API INBOUND > empowerops.volition.api.UnaryOptimizer/StopOptimization [empty StopOptimizationCommandDTO]
        [2020-07-02T02:05:56.289] Event > Stop Requested - ID:d7863580-deae-4e47-9cbb-9e5047e4b5a8
        [2020-07-02T02:05:56.291] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        cancel_request {
        }
        [2020-07-02T02:05:56.293] Event > New result received: Error(name=, inputs={x1=1.6897836977364764, x2=2.248593839594565}, exception=already evaluated 5 iterations!)
        [2020-07-02T02:05:56.293] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/OfferErrorResult [empty SimulationEvaluationErrorConfirmDTO]
        [2020-07-02T02:05:56.298] API OUTBOUND-ITEM > empowerops.volition.api.UnaryOptimizer/StartOptimization
        optimization_finished_notification {
        }
        [2020-07-02T02:05:56.300] Event > Run Stopped - ID:d7863580-deae-4e47-9cbb-9e5047e4b5a8
        [2020-07-02T02:05:56.304] API CLOSED > empowerops.volition.api.UnaryOptimizer/StartOptimization
        registration channel completed!
        [2020-07-02T02:05:56.306] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/StopOptimization
        run_ID {
          value: "d7863580-deae-4e47-9cbb-9e5047e4b5a8"
        }
        [2020-07-02T02:05:56.311] API INBOUND > empowerops.volition.api.UnaryOptimizer/RequestRunResult
        run_ID {
          value: "d7863580-deae-4e47-9cbb-9e5047e4b5a8"
        }
        [2020-07-02T02:05:56.316] API OUTBOUND > empowerops.volition.api.UnaryOptimizer/RequestRunResult
        run_ID {
          value: "d7863580-deae-4e47-9cbb-9e5047e4b5a8"
        }
        input_columns: "x1"
        input_columns: "x2"
        output_columns: "f1"
        points {
          inputs: 1.1873350226522947
          inputs: 3.4525398961878757
          outputs: 2.3199374594200854
          is_feasible: true
        }
        points {
          inputs: 1.4360031551270533
          inputs: 1.9902563871808439
          outputs: 1.7131297711539486
          is_feasible: true
        }
        points {
          inputs: 1.5893881828218057
          inputs: 3.7375279460397097
          outputs: 2.663458064430758
          is_feasible: true
        }
        points {
          inputs: 2.3874267737095676
          inputs: 4.717997855227804
          outputs: 3.5527123144686854
          is_feasible: true
        }
        points {
          inputs: 1.4546440223685648
          inputs: 3.748288340982036
          outputs: 2.6014661816753004
          is_feasible: true
        }
        frontier {
          inputs: 1.4360031551270533
          inputs: 1.9902563871808439
          outputs: 1.7131297711539486
          is_feasible: true
        }


     */

    @Test fun `when using seed data should run appropriately`() = runBlocking<Unit>() {
        //act
        val startRequest = StartOptimizationCommandDTO.newBuilder()
            .setProblemDefinition(ProblemDefinitionDTO.newBuilder()
                // this test is more complex than the above test because:
                // 1. it uses two variables instead of one
                // 2. it uses a constraint
                .addAllInputs(listOf(
                    PrototypeInputParameterDTO.newBuilder()
                        .setName("x1")
                        .setContinuous(ContinuousDTO.newBuilder()
                            .setLowerBound(1.0)
                            .setUpperBound(5.0)
                            .build()
                        )
                        .build(),
                    PrototypeInputParameterDTO.newBuilder()
                        .setName("x2")
                        .setContinuous(ContinuousDTO.newBuilder()
                            .setLowerBound(1.0)
                            .setUpperBound(5.0)
                            .build()
                        )
                        .build()
                ))
                .addAllObjectives(listOf(
                    PrototypeOutputParameterDTO.newBuilder()
                        .setName("f1")
                        .build()
                ))
                .build()
            )
            .addNodes(SimulationNodeDTO.newBuilder()
                .setAutoMap(true)
                .addInputs("x1").addInputs("x2")
                .addOutputs("f1")
                .build()
            )
            .setSettings(OptimizationSettingsDTO.newBuilder()
                .setIterationCount(UInt32Value.of(5))
            )
            .addSeedPoints(SeedRowDTO.newBuilder()
                .addAllInputs(listOf(2.0, 3.0))
                .addAllOutputs(listOf(2.5))
            )
            .build()

        val resultID = CompletableDeferred<java.util.UUID>()
        var results: OptimizationResultsResponseDTO? = null

        //act
        service.startOptimization(startRequest, object: StreamObserver<OptimizerGeneratedQueryDTO>{
            // the second argument to this function, the stream observer,
            // is a callback offered by the client to the optimizer
            // it is called by the optimizer when the optimizer makes an evaluation request
            // or feels it should notify the client of some change.

            val parentJob = coroutineContext[Job]!!

            override fun onNext(optimizerRequest: OptimizerGeneratedQueryDTO) = cancelOnException { runBlocking<Unit> {

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
                        service::offerSimulationResult.send(response)

                        Unit
                    }
                    CANCEL_REQUEST -> Unit
                    OPTIMIZATION_STARTED_NOTIFICATION -> Unit // noop
                    OPTIMIZATION_FINISHED_NOTIFICATION -> {
                        val id = optimizerRequest.optimizationFinishedNotification.runID.value
                        val request = OptimizationResultsQueryDTO.newBuilder()
                            .setRunID(UUIDDTO.newBuilder().setValue(id.toString()))
                            .build()
                        results = service::requestRunResult.send(request)
                        resultID.complete(java.util.UUID.fromString(id))
                        Unit
                    }
                    OPTIMIZATION_NOT_STARTED_NOTIFICATION -> {
                        TODO("optimization didn't start because: ${optimizerRequest.optimizationNotStartedNotification.issuesList.joinToString()}")
                    }
                    PURPOSE_NOT_SET -> TODO("unknown request $optimizerRequest")
                    DESIGN_ITERATION_COMPLETED_NOTIFICATION -> Unit
                } as Any
            }}

            override fun onError(t: Throwable) {
                t.printStackTrace()
                parentJob.cancel()
            }

            override fun onCompleted() {
                println("registration channel completed!")
            }
        })
        val id = resultID.await()

        //assert
        assertThat(results!!.pointsList.first().inputsList).isEqualTo(listOf(2.0, 3.0))
        assertThat(results!!.pointsList.first().outputsList).isEqualTo(listOf(2.5))
    }
}

private sealed class ResponseState<out R> {
    object NoValue: ResponseState<Nothing>()
    data class Failure(val throwable: Throwable): ResponseState<Nothing>()
    data class Result<R>(val result: Any?): ResponseState<R>()
}


suspend fun <M, R> KFunction2<M, StreamObserver<R>, Unit>.send(request: M): R = sendAndAwaitResponse(this, request)
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