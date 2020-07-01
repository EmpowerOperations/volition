package com.empowerops.volition.ref_oasis

import com.empowerops.babel.BabelCompiler
import com.empowerops.babel.BabelExpression
import com.empowerops.volition.dto.*
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
    data class Configuring(
            val configurationActor: ConfigurationActor,
            //corresponds to the registration channel
            val outboundOptimizerQueries: SendChannel<OptimizerRequestMessage>,
            val previous: Idle
    ): State()
    data class Optimizing(val optimizationActor: OptimizationActor, val previous: Configuring): State()
}

@Suppress("UsePropertyAccessSyntax") //for idiomatic protobuf use
class OptimizerEndpoint(
        private val configurationActorFactory: ConfigurationActorFactory,
        private val optimizationActorFactory: OptimizationActorFactory
) : OptimizerGrpc.OptimizerImplBase() {

    val compiler = BabelCompiler()
    val logger = Logger.getLogger(OptimizerEndpoint::class.qualifiedName)
    val scope = GlobalScope //TODO

    private var state: State = State.Idle

    // ok, so one of the thigns im looking to do is nicely tie offerXYZ objects into the implementation
    // similarly, startOptimization and stopOptimization must be called at the correct time.
    // so what I'd like to do is

    // so, i want one message box for offer responses,
    // i think cancellation maps to stopOptimization nicely


    override fun register(
            request: RegistrationCommandDTO,
            responseObserver: StreamObserver<OptimizerGeneratedQueryDTO>
    ) {
        try {
            val state = checkIs<State.Idle>(state)
            val outputAdaptor = makeActorConvertingOptimizerRequestMessagesToDTOs(responseObserver)
            this.state = State.Configuring(configurationActorFactory.make(), outputAdaptor, state)

            responseObserver.onNext(
                    OptimizerGeneratedQueryDTO.newBuilder()
                            .setRegistrationConfirmed(OptimizerGeneratedQueryDTO.RegistrationConfirm.newBuilder().build())
                            .build()
            )
        }
        catch(ex: Throwable) {
            logger.log(Level.SEVERE, "failed in generation registered query", ex)
            responseObserver.onError(ex)
        }
    }

    // returns a SendChannel<POJO> that wraps a StreamObserver<DTO>,
    // doing context-free conversions from data-classes to DTOs.
    private fun makeActorConvertingOptimizerRequestMessagesToDTOs(responseObserver: StreamObserver<OptimizerGeneratedQueryDTO>) = scope.actor<OptimizerRequestMessage>(Dispatchers.Unconfined) {
        try {
            for (message in channel) try {
                val wrapper = OptimizerGeneratedQueryDTO.newBuilder()

                val dc = when (message) {
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
                                .build()
                        )
                    }
                    is OptimizerRequestMessage.RunFinishedNotification -> {
                        wrapper.setOptimizationFinishedNotification(OptimizerGeneratedQueryDTO.OptimizationFinishedNotification.newBuilder()
                                .build()
                        )
                    }
                }

                responseObserver.onNext(wrapper.build())
            }
            catch(ex: Exception){
                throw RuntimeException("error while converting message=$message", ex)
            }

            //NOT in a finally block! grpc expects EITHER an 'onError' OR an 'onComplete' call, NOT BOTH!!
            responseObserver.onCompleted()
        }
        catch(ex: Throwable){
            logger.log(Level.SEVERE, "unexpected error in conversion actor", ex)
            responseObserver.onError(ex)
        }
    }

    override fun unregister(
            request: UnregistrationCommandDTO,
            responseObserver: StreamObserver<UnregistrationConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver, request) {

        val state = checkIs<State.Configuring>(state)

        val configurationClosed = state.configurationActor.close()
        (state.configurationActor as Job).join()
        check(configurationClosed) { "channel already closed" }

        val registrationClosed = state.outboundOptimizerQueries.close()
        (state.outboundOptimizerQueries as Job).join()
        check(registrationClosed) { "registration channel already closed" }

        this.state = state.previous

        UnregistrationConfirmDTO.newBuilder().build()
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
            responseObserver: StreamObserver<StartOptimizationConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver, request) {

        val configuringState = checkIs<State.Configuring>(state)
        val startMessage = StartOptimizationRequest(
                inputs = request.problemDefinition.inputsList.map { inputDTO ->
                    Input(
                            name = inputDTO.name,
                            lowerBound = inputDTO.takeIf { it.hasLowerBound() }?.lowerBound?.value ?: Double.NaN,
                            upperBound = inputDTO.takeIf { it.hasUpperBound() }?.upperBound?.value ?: Double.NaN
                    )
                },
                objectives = request.problemDefinition.objectivesList.map { objectiveDTO ->
                    Output(name = objectiveDTO.name)
                },
                intermediates = request.problemDefinition.intermediatesList.map {
                    val compiled = compiler.compile(it.scalarExpression) as BabelExpression
                    require( ! compiled.isBooleanExpression)
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
        //TODO: this should really be passed to the configuration actor for validation.
        // If it passes, the configuration actor can produce the optimization
        val optimizationActor = optimizationActorFactory.make(startMessage, configuringState.outboundOptimizerQueries)

        optimizationActor.invokeOnClose {
            val optimizingState = checkIs<State.Optimizing>(state)
            this.state = optimizingState.previous
        }

        this.state = State.Optimizing(optimizationActor, configuringState)

        StartOptimizationConfirmDTO.newBuilder().build()
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
        check(state is State.Configuring)

        StopOptimizationConfirmDTO.newBuilder()
                .setMessage("OK")
                .setRunID(message.runID.await().toString())
                .build()
    }

    override fun requestRunResult(
            request: OptimizationResultsQueryDTO,
            responseObserver: StreamObserver<OptimizationResultsResponseDTO>
    ) = scope.consumeSingleAsync(responseObserver, request){
        val state = state

        check(state is State.Configuring)

        val message = ConfigurationMessage.RunRequest(UUID.fromString(request.runID))
        state.configurationActor.send(message)

        val result = message.response.await()

        OptimizationResultsResponseDTO.newBuilder()
                .setRunID(result.uuid.toString())
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