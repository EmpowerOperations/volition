package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import com.google.protobuf.DoubleValue
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import java.time.Duration
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.NoSuchElementException

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
                            .setReadyNotification(OptimizerGeneratedQueryDTO.ReadyNotification.newBuilder().build())
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
            for (message in channel) {
                val wrapper = OptimizerGeneratedQueryDTO.newBuilder()

                val dc = when (message) {
                    is OptimizerRequestMessage.NodeStatusUpdateRequest -> {
                        wrapper.nodeStatusRequestBuilder.apply {
                            name = message.name
                        }
                    }
                    is OptimizerRequestMessage.SimulationEvaluationRequest -> {
                        wrapper.evaluationRequestBuilder.apply {
                            name = message.name
                            putAllInputVector(message.inputVector)
                        }
                    }
                    is OptimizerRequestMessage.SimulationCancelRequest -> {
                        wrapper.cancelRequestBuilder.apply {
                            name = message.name
                        }
                    }
                }

                responseObserver.onNext(wrapper.build())
            }

            responseObserver.onCompleted()
        }
        catch(ex: Throwable){
            logger.log(Level.SEVERE, "unexpected error sending message to client via registration channel", ex)
            responseObserver.onError(ex)
        }
    }

    override fun unregister(
            request: UnregistrationCommandDTO,
            responseObserver: StreamObserver<UnregistrationConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver, request) {

        val state = checkIs<State.Configuring>(state)

        val wasClosed = state.configurationActor.close()
        check(wasClosed) { "channel already closed" }

        this.state = state.previous

        UnregistrationConfirmDTO.newBuilder().build()
    }

    override fun offerSimulationConfig(
            request: NodeStatusResponseDTO,
            responseObserver: StreamObserver<NodeChangeConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver, request) {

        val state = checkIs<State.Optimizing>(state)

        val message = SimulationProvidedMessage.SimulationConfiguration(Simulation(
                request.name,
                request.inputsList.map { inputDTO ->
                    Input(
                            inputDTO.name,
                            lowerBound = inputDTO.takeIf { it.hasLowerBound() }?.lowerBound?.value ?: Double.NaN,
                            upperBound = inputDTO.takeIf { it.hasUpperBound() }?.upperBound?.value ?: Double.NaN,
                            currentValue = inputDTO.takeIf { it.hasCurrentValue() }?.currentValue?.value ?: Double.NaN
                    )
                },
                request.outputsList.map { Output(it.name) }
        ))
        state.optimizationActor.send(message)

        NodeChangeConfirmDTO.newBuilder().build()
    }

    override fun offerSimulationResult(
            request: SimulationResponseDTO,
            responseObserver: StreamObserver<SimulationResultConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver, request) {

        val state = checkIs<State.Optimizing>(state)

        val message = SimulationProvidedMessage.EvaluationResult(request.outputVectorMap)
        state.optimizationActor.send(message)

        SimulationResultConfirmDTO.newBuilder().build()
    }

    override fun offerErrorResult(
            request: ErrorResponseDTO,
            responseObserver: StreamObserver<ErrorConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver, request) {
        val state = checkIs<State.Optimizing>(state)

        val element = SimulationProvidedMessage.ErrorResponse(request.name, request.message)
        state.optimizationActor.send(element)
        fail; //ook, so the problem appears to be back-pressure.
        // calling 'offer' here under the debugger returns false.
        // 'send' throws 'channel closed exception'.
        // but looking at it under the debugger, it says active. Its mailbox is full (read: size=1)
        // so my guess is this:
        // the actor is busy. When its finished it will close. Thus the 'send' call here queues,
        // but it terminates after the previous message, resulting in a channel closed exception.
        // tl;dr, you called this too late. What do i want to do?

        ErrorConfirmDTO.newBuilder().build()
    }

    override fun offerEvaluationStatusMessage(
            request: MessageCommandDTO,
            responseObserver: StreamObserver<MessageConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver, request) {

        val state = checkIs<State.Optimizing>(state)

        val element = SimulationProvidedMessage.Message(request.name, request.message)
        state.optimizationActor.send(element)

        MessageConfirmDTO.newBuilder().build()
    }


    override fun startOptimization(
            request: StartOptimizationCommandDTO,
            responseObserver: StreamObserver<StartOptimizationConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver, request) {

        val configuringState = checkIs<State.Configuring>(state)
        val optimizationActor = optimizationActorFactory.make(configuringState.outboundOptimizerQueries)

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
                .setMessage(result.resultMessage)
                .setResult(com.empowerops.volition.dto.RunResult.newBuilder()
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
                )
                .build()
    }

    override fun requestIssues(
            request: IssuesQueryDTO,
            responseObserver: StreamObserver<IssuesResponseDTO>
    ) = scope.consumeSingleAsync(responseObserver, request){
        TODO("apiService.requestIssues()")
    }

    override fun requestEvaluationNode(
            request: NodeStatusQueryDTO,
            responseObserver: StreamObserver<NodeStatusResponseDTO>
    ) = scope.consumeSingleAsync(responseObserver, request){
        val state = checkIs<State.Configuring>(state)

        val message = ConfigurationMessage.GetNode(request.name)
        state.configurationActor.send(message)
        val result = message.response.await()

        if(result == null){
            NodeStatusResponseDTO.newBuilder().build()
        }
        else {
            NodeStatusResponseDTO.newBuilder()
                    .setName(result.name)
                    .addAllInputs(result.inputs.map { input ->
                        PrototypeInputParameter.newBuilder()
                                .setName(input.name)
                                .apply { if (!input.lowerBound.isNaN()) setLowerBound(DoubleValue.of(input.lowerBound)) }
                                .apply { if (!input.upperBound.isNaN()) setUpperBound(DoubleValue.of(input.upperBound)) }
                                .build()
                    })
                    .addAllOutputs(result.outputs.map { output ->
                        PrototypeOutputParameter.newBuilder()
                                .setName(output.name)
                                .build()
                    })
                    .apply { if(result.autoImport) setAutoImport(result.autoImport) }
                    .setMappingTable(VariableMapping.newBuilder()
                            .putAllInputs(result.inputMapping)
                            .putAllOutputs(result.outputMapping)
                            .build()
                    )
                    .build()
        }
    }

    override fun upsertEvaluationNode(
            request: NodeChangeCommandDTO,
            responseObserver: StreamObserver<NodeChangeConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver, request){
        val state = checkIs<State.Configuring>(state)

        val message = ConfigurationMessage.UpsertNode(
                request.name,
                request.inputsList.map { inputDTO ->
                    Input(
                            name = inputDTO.name,
                            lowerBound = inputDTO.takeIf { it.hasLowerBound() }?.lowerBound?.value ?: Double.NaN,
                            upperBound = inputDTO.takeIf { it.hasUpperBound() }?.upperBound?.value ?: Double.NaN,
                            currentValue = inputDTO.takeIf { it.hasCurrentValue() }?.currentValue?.value ?: Double.NaN
                    )
                },
                request.outputsList.map { outputDTO -> Output(outputDTO.name) },
                request.autoImport,
                Duration.ofSeconds(request.timeOut.seconds) + Duration.ofNanos(request.timeOut.nanos.toLong()),
                request.mappingTable.inputsMap,
                request.mappingTable.outputsMap
        )

        state.configurationActor.send(message)

        NodeChangeConfirmDTO.newBuilder().setMessage("OK").build()
    }

    override fun requestProblemDefinition(
            request: ProblemDefinitionQueryDTO,
            responseObserver: StreamObserver<ProblemDefinitionResponseDTO>
    ) = scope.consumeSingleAsync(responseObserver, request){
        TODO("apiService.requestProblemDefinition(request)")
    }

    override fun updateProblemDefinition(
            request: ProblemDefinitionUpdateCommandDTO,
            responseObserver: StreamObserver<ProblemDefinitionConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver, request){
        TODO("apiService.updateProblemDefinition(request)")
    }


}

private inline fun <reified T> checkIs(instance: Any): T {
    check(instance is T){ "expected ${T::class.simpleName}, but was $instance" }
    return instance as T
}