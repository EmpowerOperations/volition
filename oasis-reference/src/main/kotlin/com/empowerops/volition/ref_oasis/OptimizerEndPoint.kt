package com.empowerops.volition.ref_oasis

import com.empowerops.babel.BabelCompiler
import com.empowerops.babel.BabelExpression
import com.empowerops.volition.dto.*
import io.grpc.Status
import io.grpc.stub.ServerCallStreamObserver
import com.empowerops.volition.dto.UUID as UUIDDto
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import java.lang.RuntimeException
import java.time.Duration
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger

interface ResponseNeeded<in T>: AutoCloseable {
    fun respondWith(value: T)
    fun failedWith(ex: Throwable)
    override fun close() //resume any waiters regardless of whether respondWith was called
}

typealias ParameterName = String

sealed class State {
    object Idle: State() {}
    data class Optimizing(val optimizationActor: OptimizationActor, val previous: Idle): State()
}

@Suppress("UsePropertyAccessSyntax") //for idiomatic protobuf use
class OptimizerEndpoint(
        private val model: Map<UUID, RunResult>,
        private val optimizationActorFactory: OptimizationActorFactory
) : UnaryOptimizerGrpc.UnaryOptimizerImplBase() {

    val compiler = BabelCompiler()
    val logger = Logger.getLogger(OptimizerEndpoint::class.qualifiedName)
    val scope = GlobalScope //TODO

    private var state: State = State.Idle

    // returns a SendChannel<POJO> that wraps a StreamObserver<DTO>,
    // doing context-free conversions from data-classes to DTOs.
    private fun makeActorConvertingOptimizerRequestMessagesToDTOs(
            responseObserver: StreamObserver<OptimizerGeneratedQueryDTO>
    ): SendChannel<OptimizerRequestMessage> {

        val actor = scope.actor<OptimizerRequestMessage>(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {

            try {
                for (message in channel) try {
                    val wrapper = OptimizerGeneratedQueryDTO.newBuilder()

                    val dto = when(message) {
                        is OptimizerRequestMessage.SimulationEvaluationRequest -> {
                            wrapper.setEvaluationRequest(OptimizerGeneratedQueryDTO.SimulationEvaluationRequest.newBuilder()
                                    .setName(message.name)
                                    .putAllInputVector(message.inputVector)
                                    .build()
                            )
                        }
                        is OptimizerRequestMessage.SimulationCancelRequest -> {
                            wrapper.setCancelRequest(OptimizerGeneratedQueryDTO.SimulationCancelRequest.newBuilder()
                                    .setName(message.name)
                                    .build()
                            )
                        }
                        is OptimizerRequestMessage.RunStartedNotification -> {
                            wrapper.setOptimizationStartedNotification(OptimizerGeneratedQueryDTO.OptimizationStartedNotification.newBuilder()
                                    .setRunID(message.runID.toDTO())
                                    .build()
                            )
                        }
                        is OptimizerRequestMessage.RunFinishedNotification -> {
                            wrapper.setOptimizationFinishedNotification(OptimizerGeneratedQueryDTO.OptimizationFinishedNotification.newBuilder()
                                    .build()
                            )
                        }
                        is OptimizerRequestMessage.RunNotStartedNotification -> {
                            wrapper.setOptimizationNotStartedNotification(OptimizerGeneratedQueryDTO.OptimizationFailedToStartNotification.newBuilder()
                                    .addAllIssues(message.issues)
                                    .build()
                            )
                        }
                    }.build()

                    responseObserver.onNext(dto)
                } catch(ex: Exception){
                    throw RuntimeException("error while converting message=$message", ex)
                }

                //NOT in a finally block! grpc expects EITHER an 'onError' OR an 'onComplete' call, NOT BOTH!!
                responseObserver.onCompleted()
            } catch(ex: Throwable){
                logger.log(Level.SEVERE, "unexpected error in conversion actor", ex)
                responseObserver.onError(ex)
            }
        }

        val observer = responseObserver as? ServerCallStreamObserver
        if(observer == null) {
            logger.warning("stream isnt ServerCallStreamObserver => wont know if client disconnects")
        }
        else observer.setOnCancelHandler {
            actor.close(Status.CANCELLED
                    .withDescription("client cancelled start-optimization stream")
                    .asRuntimeException()
            )
        }

        return actor
    }

    override fun offerSimulationResult(
            request: SimulationEvaluationCompletedResponseDTO,
            responseObserver: StreamObserver<SimulationEvaluationResultConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver, request) {

        val state = checkIs<State.Optimizing>(state)

        val message = SimulationProvidedMessage.EvaluationResult(request.outputVectorMap)
        state.optimizationActor.send(message)

        SimulationEvaluationResultConfirmDTO.newBuilder().build()
    }

    override fun offerErrorResult(
            request: SimulationEvaluationErrorResponseDTO,
            responseObserver: StreamObserver<SimulationEvaluationErrorConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver, request) {
        val state = checkIs<State.Optimizing>(state)

        val element = SimulationProvidedMessage.ErrorResponse(request.name, request.message)
        state.optimizationActor.send(element)

        SimulationEvaluationErrorConfirmDTO.newBuilder().build()
    }

    override fun offerEvaluationStatusMessage(
            request: StatusMessageCommandDTO,
            responseObserver: StreamObserver<StatusMessageConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver, request) {

        val state = checkIs<State.Optimizing>(state)

        val element = SimulationProvidedMessage.Message(request.name, request.message)
        state.optimizationActor.send(element)

        StatusMessageConfirmDTO.newBuilder().build()
    }


    override fun startOptimization(
            request: StartOptimizationCommandDTO,
            responseObserver: StreamObserver<OptimizerGeneratedQueryDTO>
    ): Unit {

        val outputAdaptor = try { makeActorConvertingOptimizerRequestMessagesToDTOs(responseObserver) }
                catch(ex: RuntimeException){responseObserver.onError(ex); throw ex }

        try {
            val idleState = checkIs<State.Idle>(state)

            val optimizationActor = optimizationActorFactory.make(outputAdaptor)

            optimizationActor.invokeOnClose {
                outputAdaptor.close()
                val optimizingState = checkIs<State.Optimizing>(state)
                state = optimizingState.previous
            }

            outputAdaptor.invokeOnClose { closingEx ->
                if( ! optimizationActor.isClosedForSend && closingEx is io.grpc.StatusRuntimeException) {
                    // this is invoked when the client abrutply disconnects (think crash)
                    // the problem there is that the output closes before the actor finishes,
                    // so we just cancel the actor.
                    optimizationActor.close(RuntimeException(closingEx))
                }
            }

            state = State.Optimizing(optimizationActor, idleState)

            val startMessage = SimulationProvidedMessage.StartOptimizationRequest(
                    inputs = request.problemDefinition.inputsList.map { inputDTO ->

                        val lowerBound = when (inputDTO.domainCase!!) {
                            PrototypeInputParameter.DomainCase.CONTINUOUS -> inputDTO.continuous.lowerBound
                            PrototypeInputParameter.DomainCase.DISCRETE_RANGE -> inputDTO.discreteRange.lowerBound
                            PrototypeInputParameter.DomainCase.DOMAIN_NOT_SET -> TODO()
                        }
                        val upperBound = when (inputDTO.domainCase!!) {
                            PrototypeInputParameter.DomainCase.CONTINUOUS -> inputDTO.continuous.upperBound
                            PrototypeInputParameter.DomainCase.DISCRETE_RANGE -> inputDTO.discreteRange.upperBound
                            PrototypeInputParameter.DomainCase.DOMAIN_NOT_SET -> TODO()
                        }
                        val stepSize = when (inputDTO.domainCase!!) {
                            PrototypeInputParameter.DomainCase.CONTINUOUS -> null
                            PrototypeInputParameter.DomainCase.DISCRETE_RANGE -> inputDTO.discreteRange.stepSize
                            PrototypeInputParameter.DomainCase.DOMAIN_NOT_SET -> TODO()
                        }

                        Input(
                                name = inputDTO.name,
                                lowerBound = lowerBound,
                                upperBound = upperBound,
                                stepSize = stepSize
                        )
                    },
                    objectives = request.problemDefinition.objectivesList.map { objectiveDTO ->
                        Output(name = objectiveDTO.name)
                    },
                    intermediates = request.problemDefinition.intermediatesList.map {
                        val compiled = compiler.compile(it.scalarExpression) as BabelExpression
                        require(!compiled.isBooleanExpression)
                        MathExpression(it.outputName, compiled)
                    },
                    constraints = request.problemDefinition.constraintsList.map {
                        val compiled = compiler.compile(it.booleanExpression) as BabelExpression
                        require(compiled.isBooleanExpression)
                        MathExpression(it.outputName, compiled)
                    },

                    nodes = request.nodesList.map { simDTO ->

                        val timeOut = simDTO
                                .takeIf { it.hasTimeOut() }
                                ?.timeOut?.let { Duration.ofSeconds(it.seconds) + Duration.ofNanos(it.nanos.toLong()) }

                        val autoMapping = simDTO
                                .takeIf { it.mappingCase == StartOptimizationCommandDTO.SimulationNode.MappingCase.AUTO_MAP }
                                ?.autoMap ?: false

                        val explicitMapping = simDTO
                                .takeIf { it.mappingCase == StartOptimizationCommandDTO.SimulationNode.MappingCase.MAPPING_TABLE }
                                ?.mappingTable

                        Simulation(
                                name = simDTO.name,
                                inputs = simDTO.inputsList,
                                outputs = simDTO.outputsList,
                                timeOut = timeOut,
                                autoMap = autoMapping,
                                inputMapping = explicitMapping?.inputsMap,
                                outputMapping = explicitMapping?.outputsMap
                        )
                    }
            )

            runBlocking<Unit> { optimizationActor.send(startMessage) }
        }
        catch(ex: Throwable) {
            logger.log(Level.SEVERE, "internal error starting optimization", ex)
            responseObserver.onError(ex)
        }
    }

    override fun stopOptimization(
            request: StopOptimizationCommandDTO,
            responseObserver: StreamObserver<StopOptimizationConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver, request) {
        var state = state

        check(state is State.Optimizing)

        val message = SimulationProvidedMessage.StopOptimization()
        state.optimizationActor.send(message)
        (state.optimizationActor as Job).join()

        state = this.state
        check(state is State.Idle)

        StopOptimizationConfirmDTO.newBuilder()
                .setRunID(message.runID.await().toDTO())
                .build()
    }

    override fun requestRunResult(
            request: OptimizationResultsQueryDTO,
            responseObserver: StreamObserver<OptimizationResultsResponseDTO>
    ) = scope.consumeSingleAsync(responseObserver, request){
        val state = state

        check(state is State.Idle)

        val result = model.getValue(UUID.fromString(request.runID.value))

        OptimizationResultsResponseDTO.newBuilder()
                .setRunID(result.uuid.toDTO())
                .addAllInputColumns(result.inputs)
                .addAllOutputColumns(result.outputs)
                .addAllPoints(result.points.map { point -> DesignRow.newBuilder()
                        .addAllInputs(point.inputs)
                        .addAllOutputs(point.outputs)
                        .setIsFeasible(point.isFeasible)
                        .build()
                })
                .addAllFrontier(result.frontier.map { frontierPoint -> DesignRow.newBuilder()
                        .addAllInputs(frontierPoint.inputs)
                        .addAllOutputs(frontierPoint.outputs)
                        .setIsFeasible(frontierPoint.isFeasible)
                        .build()
                })
                .build()
    }

}

private inline fun <reified T> checkIs(instance: Any): T {
    check(instance is T){ "expected ${T::class.simpleName}, but was $instance" }
    return instance as T
}

private fun UUID.toDTO(): UUIDDto = UUIDDto.newBuilder().setValue(this.toString()).build()