package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import com.empowerops.volition.dto.SimulationNodeChangeCommandDTO.ChangeCase
import com.empowerops.volition.dto.SimulationNodeChangeCommandDTO.CompleteSimulationNode.MappingCase
import com.google.protobuf.DoubleValue
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
                    is OptimizerRequestMessage.NodeStatusUpdateRequest -> {
                        wrapper.setNodeStatusRequest(OptimizerGeneratedQueryDTO.NodeStatusUpdateRequest.newBuilder()
                                .setName(message.name)
                                .build()
                        )
                    }
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

    override fun offerSimulationConfig(
            request: SimulationNodeResponseDTO,
            responseObserver: StreamObserver<SimulationNodeUpsertConfirmDTO>
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

        SimulationNodeUpsertConfirmDTO.newBuilder().build()
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

    override fun requestSimulationNode(
            request: SimulationNodeStatusQueryDTO,
            responseObserver: StreamObserver<SimulationNodeResponseDTO>
    ) = scope.consumeSingleAsync(responseObserver, request){
        val state = checkIs<State.Configuring>(state)

        val message = ConfigurationMessage.GetNode(request.name)
        state.configurationActor.send(message)
        val result = message.response.await()

        if(result == null){
            SimulationNodeResponseDTO.newBuilder().build()
        }
        else {
            SimulationNodeResponseDTO.newBuilder()
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

    override fun upsertSimulationNode(
            request: SimulationNodeChangeCommandDTO,
            responseObserver: StreamObserver<SimulationNodeUpsertConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver, request){
        val state = checkIs<State.Configuring>(state)

        val rawInputsOrNull = when(request.changeCase) {
            ChangeCase.NEW_INPUTS -> request.newInputs.valuesList
            ChangeCase.NEW_NODE -> request.newNode.inputsList
            else -> null
        }
        val inputs = rawInputsOrNull?.map { inputDTO -> Input(
                name = inputDTO.name,
                lowerBound = inputDTO.takeIf { it.hasLowerBound() }?.lowerBound?.value ?: Double.NaN,
                upperBound = inputDTO.takeIf { it.hasUpperBound() }?.upperBound?.value ?: Double.NaN,
                currentValue = inputDTO.takeIf { it.hasCurrentValue() }?.currentValue?.value ?: Double.NaN
        )}

        val rawOutputsOrNull = when(request.changeCase) {
            ChangeCase.NEW_OUTPUTS -> request.newOutputs.valuesList
            ChangeCase.NEW_NODE -> request.newNode.outputsList
            else -> null
        }

        val outputs = rawOutputsOrNull?.map { outputDTO -> Output(
                name = outputDTO.name
        )}

        val autoImportOrNull = when(request.changeCase){
            ChangeCase.NEW_AUTO_IMPORT -> request.newAutoImport
            ChangeCase.NEW_NODE -> request.newNode.autoImport
            else -> null
        }

        val timeOutOrNull = when(request.changeCase){
            ChangeCase.NEW_TIME_OUT -> request.newTimeOut
            ChangeCase.NEW_NODE -> request.newNode.timeout
            else -> null
        }

        val inputsMapOrNull = when(request.changeCase){
            ChangeCase.NEW_MAPPING_TABLE -> request.newMappingTable.inputsMap
            ChangeCase.NEW_NODE -> when(request.newNode.mappingCase!!){
                MappingCase.AUTOIMPORT -> null
                MappingCase.MAPPINGTABLE -> request.newNode.mappingTable.inputsMap
                MappingCase.MAPPING_NOT_SET -> null
            }
            else -> null
        }
        val outputsMapOrNull = when(request.changeCase){
            ChangeCase.NEW_MAPPING_TABLE -> request.newMappingTable.outputsMap
            ChangeCase.NEW_NODE -> when(request.newNode.mappingCase!!){
                MappingCase.AUTOIMPORT -> null
                MappingCase.MAPPINGTABLE -> request.newNode.mappingTable.outputsMap
                MappingCase.MAPPING_NOT_SET -> null
            }
            else -> null
        }

        val message = ConfigurationMessage.UpsertNode(
                request.name,
                if(request.changeCase == ChangeCase.NEW_NAME) request.newName else null,
                inputs,
                outputs,
                autoImportOrNull,
                timeOutOrNull?.let { Duration.ofSeconds(it.seconds) + Duration.ofNanos(it.nanos.toLong()) },
                inputsMapOrNull,
                outputsMapOrNull
        )

        state.configurationActor.send(message)

        SimulationNodeUpsertConfirmDTO.newBuilder().setMessage("OK").build()
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
        val state = checkIs<State.Configuring>(state)

        val

        state.configurationActor.send(ConfigurationMessage.UpdateProblemDef(
                request.inputs
        ))

        ProblemDefinitionConfirmDTO.newBuilder().build()
    }
}

private inline fun <reified T> checkIs(instance: Any): T {
    check(instance is T){ "expected ${T::class.simpleName}, but was $instance" }
    return instance as T
}