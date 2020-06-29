package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import com.google.common.eventbus.EventBus
import com.google.protobuf.DoubleValue
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import java.lang.Exception
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

sealed class SimulationProvidedMessage {
    data class EvaluationResult(
            val outputVector: Map<String, Double>
    ): SimulationProvidedMessage()

    data class ErrorResponse(
            val name: String,
            val message: String
    ) : SimulationProvidedMessage()

    data class Message(
            val name: String,
            val message: String
    ) : SimulationProvidedMessage()

    data class SimulationConfiguration(val sim: Simulation): SimulationProvidedMessage()
}

typealias ParameterName = String

sealed class ConfigurationMessage {

    data class RunRequest(val id: String): UnaryRequestResponseConfigurationMessage<RunResult>()
    data class UpsertNode(
            val name: String,
            val inputs: List<Input>,
            val outputs: List<Output>,
            val autoImport: Boolean,
            val timeOut: Duration,
            val inputMapping: Map<String, ParameterName>,
            val outputMapping: Map<ParameterName, String>
    ): ConfigurationMessage()

    data class GetNode(
            val name: String
    ): UnaryRequestResponseConfigurationMessage<Simulation?>()
}

abstract class UnaryRequestResponseConfigurationMessage<T>: ConfigurationMessage(), ResponseNeeded<T> {
    val response: CompletableDeferred<T> = CompletableDeferred()
    final override fun respondWith(value: T) { response.complete(value) }
    final override fun failedWith(ex: Throwable) { response.completeExceptionally(ex) }
    final override fun close() {
        response.completeExceptionally(NoSuchElementException("internal error: no result provided by service to $this"))
    }
}

sealed class OptimizerRequestMessage {

    data class NodeStatusUpdateRequest(val name: String): OptimizerRequestMessage()
    data class SimulationEvaluationRequest(val name: String, val inputVector: Map<String, Double>): OptimizerRequestMessage()
    data class SimulationCancelRequest(val name: String): OptimizerRequestMessage()
}

typealias ConfigurationActor = SendChannel<ConfigurationMessage>

class ConfigurationActorFactory(
        val scope: CoroutineScope,
        val model: ModelService
){
    fun make(): ConfigurationActor = scope.actor<ConfigurationMessage> {

        for(message in channel) try {
            println("config actor receieved $message!")

            val result = when(message){
                is ConfigurationMessage.RunRequest -> TODO()
                is ConfigurationMessage.UpsertNode -> {
                    val simulation = Simulation(
                            message.name,
                            message.inputs,
                            message.outputs,
                            "description of ${message.name}",
                            message.timeOut,
                            message.autoImport,
                            message.inputMapping,
                            message.outputMapping
                    )

                    model.addSim(simulation)

                    if(message.autoImport){
                        val setupSuccess = model.autoSetup(simulation)
                        require(setupSuccess)
                    }

                    Unit
                }
                is ConfigurationMessage.GetNode -> {
                    val singleSimulation = model.findSimulationName(message.name)

                    message.respondWith(singleSimulation)
                }
                is UnaryRequestResponseConfigurationMessage<*> -> TODO()
            }
        }
        catch(ex: Throwable){
            if(message is ResponseNeeded<*>){
                message.failedWith(ex)
            }
        }
        finally {
            if(message is ResponseNeeded<*>){
                message.close()
            }
        }
    }
}

typealias OptimizationActor = SendChannel<SimulationProvidedMessage>

class OptimizationActorFactory(
        val scope: CoroutineScope,
        val optimizer: Optimizer,
        val model: ModelService,
        val eventBus: EventBus
) {

    private val logger = Logger.getLogger(OptimizationActorFactory::class.qualifiedName)

    fun make(output: SendChannel<OptimizerRequestMessage>): OptimizationActor = scope.actor<SimulationProvidedMessage>() {

        val sim = model.simulations.single()

        output.send(OptimizerRequestMessage.NodeStatusUpdateRequest(sim.name))
        val config = channel.receive() as? SimulationProvidedMessage.SimulationConfiguration

        require(config is SimulationProvidedMessage.SimulationConfiguration) { "expected SimulationConfiguration, but received $config" }
        require(config.sim.name == sim.name) { "at optimization start, config=$config, expected config=$sim" }
        require(config.sim.inputs == sim.inputs) { "at optimization start, config=$config, expected config=$sim" }
        require(config.sim.outputs == sim.outputs) { "at optimization start, config=$config, expected config=$sim" }

        val runID = UUID.randomUUID()
        try {
            eventBus.post(RunStartedEvent(runID))
            while (isActive) { //stopOptimization maps to cancellation, to stop an optimization, cancel this actor

                val inputVector = optimizer.generateInputs(sim.inputs)

                try {
                    output.send(OptimizerRequestMessage.SimulationEvaluationRequest(sim.name, inputVector))

                    var response = channel.receive()

                    //read out any status messages
                    while (response is SimulationProvidedMessage.Message) {
                        eventBus.post(NewMessageEvent(Message(response.name, response.message)))
                        response = channel.receive()
                    }

                    //read the response
                    when (response) {
                        is SimulationProvidedMessage.EvaluationResult -> {
                            eventBus.post(NewResultEvent(EvaluationResult.Success(sim.name, inputVector, response.outputVector)))
                            optimizer.addCompleteDesign(inputVector + response.outputVector)
                        }
                        is SimulationProvidedMessage.ErrorResponse -> {
                            eventBus.post(NewResultEvent(EvaluationResult.Error(sim.name, inputVector, response.message)))
                            // we dont update the optimizer state when the evaluation encounters an error
                            // -- this is approximately true for both this implementation and Empower commercial optimizers
                        }
                        is SimulationProvidedMessage.SimulationConfiguration -> TODO()
                        is SimulationProvidedMessage.Message -> TODO()
                    } as Any
                } catch (ex: CancellationException) {
                    if (!isActive) output.send(OptimizerRequestMessage.SimulationCancelRequest(sim.name))
                    else logger.warning("$this is cancelled but still active?")

                    throw ex
                }
            }
        }
        catch(ex: Throwable){
            throw ex;
        }
        finally {
            eventBus.post(RunStoppedEvent(runID))
        }
    }
}

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
    private fun makeActorConvertingOptimizerRequestMessagesToDTOs(responseObserver: StreamObserver<OptimizerGeneratedQueryDTO>) = scope.actor<OptimizerRequestMessage> {
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
    ) = scope.consumeSingleAsync(responseObserver) {

        val state = checkIs<State.Configuring>(state)

        val wasClosed = state.configurationActor.close()
        check(wasClosed) { "channel already closed" }

        this.state = state.previous

        UnregistrationConfirmDTO.newBuilder().build()
    }

    override fun offerSimulationConfig(
            request: NodeStatusResponseDTO,
            responseObserver: StreamObserver<NodeChangeConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver) {

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
    ) = scope.consumeSingleAsync(responseObserver) {

        val state = checkIs<State.Optimizing>(state)

        val message = SimulationProvidedMessage.EvaluationResult(request.outputVectorMap)
        state.optimizationActor.send(message)

        SimulationResultConfirmDTO.newBuilder().build()
    }

    override fun offerErrorResult(
            request: ErrorResponseDTO,
            responseObserver: StreamObserver<ErrorConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver) {
        val state = checkIs<State.Optimizing>(state)

        val element = SimulationProvidedMessage.ErrorResponse(request.name, request.message)
        state.optimizationActor.send(element)

        ErrorConfirmDTO.newBuilder().build()
    }

    override fun offerEvaluationStatusMessage(
            request: MessageCommandDTO,
            responseObserver: StreamObserver<MessageConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver) {

        fail; //this is happening after the optimization ends...
        val state = checkIs<State.Optimizing>(state)

        val element = SimulationProvidedMessage.Message(request.name, request.message)
        state.optimizationActor.send(element)

        MessageConfirmDTO.newBuilder().build()
    }


    override fun startOptimization(
            request: StartOptimizationCommandDTO,
            responseObserver: StreamObserver<StartOptimizationConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver) {

        val configuringState = checkIs<State.Configuring>(state)
        val optimizationActor = optimizationActorFactory.make(configuringState.outboundOptimizerQueries)
        this.state = State.Optimizing(optimizationActor, configuringState)

        optimizationActor.invokeOnClose {
            val optimizingState = checkIs<State.Optimizing>(state)
            this.state = optimizingState.previous
        }

        StartOptimizationConfirmDTO.newBuilder().build()
    }

    override fun stopOptimization(
            request: StopOptimizationCommandDTO,
            responseObserver: StreamObserver<StopOptimizationConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver) {
        var state = state

        check(state is State.Optimizing)

        state.optimizationActor.close()
        (state.optimizationActor as Job).join()

        state = this.state
        check(state is State.Configuring)

        StopOptimizationConfirmDTO.newBuilder().build()
    }

    override fun requestRunResult(
            request: OptimizationResultsQueryDTO,
            responseObserver: StreamObserver<OptimizationResultsResponseDTO>
    ) = scope.consumeSingleAsync(responseObserver){
        val state = state

        check(state is State.Configuring)

        val message = ConfigurationMessage.RunRequest(request.runID)
        state.configurationActor.send(message)

        val result = message.response.await()

        OptimizationResultsResponseDTO.newBuilder()
                .setMessage(result.resultMessage)
                .setRunResult(com.empowerops.volition.dto.RunResult.newBuilder()
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
    ) = scope.consumeSingleAsync(responseObserver){
        TODO("apiService.requestIssues()")
    }

    override fun requestEvaluationNode(
            request: NodeStatusQueryDTO,
            responseObserver: StreamObserver<NodeStatusResponseDTO>
    ) = scope.consumeSingleAsync(responseObserver){
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
    ) = scope.consumeSingleAsync(responseObserver){
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
    ) = scope.consumeSingleAsync(responseObserver){
        TODO("apiService.requestProblemDefinition(request)")
    }

    override fun updateProblemDefinition(
            request: ProblemDefinitionUpdateCommandDTO,
            responseObserver: StreamObserver<ProblemDefinitionConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver){
        TODO("apiService.updateProblemDefinition(request)")
    }


}

private inline fun <reified T> checkIs(instance: Any): T {
    check(instance is T){ "expected ${T::class.simpleName}, but was $instance" }
    return instance as T
}