package com.empowerops.volition.ref_oasis

import com.empowerops.babel.BabelCompiler
import com.empowerops.babel.BabelExpression
import com.empowerops.volition.dto.*
import com.empowerops.volition.dto.toDTO
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.util.*
import java.util.logging.Logger

typealias ParameterName = String

sealed class State {
    object Idle: State() {}
    data class Optimizing(val optimizerBoundMessages: Channel<SimulationProvidedMessage>, val previous: Idle): State()
}

@Suppress("UsePropertyAccessSyntax") //for idiomatic protobuf use
class OptimizerEndpoint(
        private val model: Map<UUID, RunResult>,
        private val optimizationActorFactory: OptimizationActorFactory
) : UnaryOptimizerGrpcKt.UnaryOptimizerCoroutineImplBase() {

    val compiler = BabelCompiler
    val logger = Logger.getLogger(OptimizerEndpoint::class.qualifiedName)
    val scope = GlobalScope //TODO

    private var state: State = State.Idle
        get() = field
        set(value) { field = value }

    override suspend fun offerSimulationResult(request: SimulationEvaluationCompletedResponseDTO): SimulationEvaluationResultConfirmDTO {
        val state = checkIs<State.Optimizing>(state)

        val message = SimulationProvidedMessage.EvaluationResult(request.iterationIndex.toUInt(), request.outputVectorMap)
        state.optimizerBoundMessages.send(message)

        return SimulationEvaluationResultConfirmDTO.newBuilder().build()
    }

    override suspend fun offerErrorResult(request: SimulationEvaluationErrorResponseDTO): SimulationEvaluationErrorConfirmDTO {
        val state = checkIs<State.Optimizing>(state)

        val element = SimulationProvidedMessage.ErrorResponse(request.name, request.iterationIndex.toUInt(), request.message)
        state.optimizerBoundMessages.send(element)

        return simulationEvaluationErrorConfirmDTO {  }
    }

    override suspend fun offerEvaluationStatusMessage(request: StatusMessageCommandDTO): StatusMessageConfirmDTO {
        val state = checkIs<State.Optimizing>(state)

        val element = SimulationProvidedMessage.Message(request.name, request.iterationIndex.toUInt(), request.message)
        state.optimizerBoundMessages.send(element)

        return statusMessageConfirmDTO {  }
    }

    override fun startOptimization(request: StartOptimizationCommandDTO): Flow<OptimizerGeneratedQueryDTO> {

        if(state !is State.Idle) return flow<OptimizerGeneratedQueryDTO> {
            emit(optimizerGeneratedQueryDTO {
                optimizationNotStartedNotification = optimizationNotStartedNotificationDTO {
                    issues += "Optimization already running"
                }
            })
        }
        val idleState = checkIs<State.Idle>(state)

        val channel = Channel<SimulationProvidedMessage>(Channel.RENDEZVOUS)
        state = State.Optimizing(channel, idleState)

        val startMessage = SimulationProvidedMessage.StartOptimizationRequest(
            inputs = request.problemDefinition.inputsList.map { inputDTO ->

                val lowerBound = when (inputDTO.domainCase!!) {
                    InputParameterDTO.DomainCase.CONTINUOUS -> inputDTO.continuous.lowerBound
                    InputParameterDTO.DomainCase.DISCRETE_RANGE -> inputDTO.discreteRange.lowerBound
                    InputParameterDTO.DomainCase.DOMAIN_NOT_SET -> TODO()
                    InputParameterDTO.DomainCase.VALUES_SET -> TODO()
                }
                val upperBound = when (inputDTO.domainCase!!) {
                    InputParameterDTO.DomainCase.CONTINUOUS -> inputDTO.continuous.upperBound
                    InputParameterDTO.DomainCase.DISCRETE_RANGE -> inputDTO.discreteRange.upperBound
                    InputParameterDTO.DomainCase.DOMAIN_NOT_SET -> TODO()
                    InputParameterDTO.DomainCase.VALUES_SET -> TODO()
                }
                val stepSize = when (inputDTO.domainCase!!) {
                    InputParameterDTO.DomainCase.CONTINUOUS -> null
                    InputParameterDTO.DomainCase.DISCRETE_RANGE -> inputDTO.discreteRange.stepSize
                    InputParameterDTO.DomainCase.DOMAIN_NOT_SET -> TODO()
                    InputParameterDTO.DomainCase.VALUES_SET -> TODO()
                }

                Input(
                    name = inputDTO.name,
                    lowerBound = lowerBound,
                    upperBound = upperBound,
                    stepSize = stepSize
                )
            },
            transforms = request.problemDefinition.evaluablesList.map { node ->
                val evaluable = when (node.valueCase) {
                    EvaluableNodeDTO.ValueCase.TRANSFORM -> {
                        val value = node.transform
                        val compiled = compiler.compile(value.scalarExpression) as BabelExpression
                        require(!compiled.isBooleanExpression)
                        MathExpression(value.outputName, compiled)
                    }
                    EvaluableNodeDTO.ValueCase.SIMULATION -> {
                        val simulation = node.simulation
                        val autoMap = simulation.autoMap
                        val mappingTableOrNull = simulation.takeIf { !autoMap }?.mappingTable

                        Simulation(
                            simulation.name,
                            simulation.inputsList.map { it.name },
                            simulation.outputsList.map { simOutput ->
                                simOutput.name.also {
                                    require(!simOutput.isBoolean) {
                                        "constraint outputs on simulations not yet supported in ref optimizer (it is suppored by the OASIS volition service."
                                    }
                                }
                            },
                            simulation.timeOut.toDuration(),
                            autoMap,
                            mappingTableOrNull?.inputsMap?.mapValues { (key, value) -> value.value },
                            mappingTableOrNull?.outputsMap?.invert()?.mapKeys { key -> key.value }
                        )
                    }
                    EvaluableNodeDTO.ValueCase.CONSTRAINT -> {
                        val value = node.constraint
                        val compiled = compiler.compile(value.booleanExpression) as BabelExpression
                        require(compiled.isBooleanExpression)
                        MathExpression(value.outputName, compiled)
                    }
                    null, EvaluableNodeDTO.ValueCase.VALUE_NOT_SET -> TODO("unknown evaluable=$node")
                }

                evaluable
            },
            settings = request.settings.run {
                OptimizationSettings(
                    runtime = if (hasRunTime()) Duration.ofSeconds(runTime.seconds) else null,
                    iterationCount = if (hasIterationCount()) iterationCount else null,
                    targetObjectiveValue = if (hasTargetObjectiveValue()) targetObjectiveValue else null,
                    concurrentRunCount = concurrentRunCount.toUInt()
                )
            },
            seedPoints = request.seedPointsList.map { ExpensivePointRow(it.inputsList, it.outputsList, null, null) }
        )

        val optimizationActor = optimizationActorFactory.make(startMessage, channel)

        return optimizationActor.map { message ->
            optimizerGeneratedQueryDTO {
                when (message) {
                    is OptimizerRequestMessage.ExpensivePointFoundNotification -> {
                        designIterationCompletedNotification = designIterationCompletedNotificationDTO {
                            designPoint = designRowDTO {
                                inputs += message.point.inputs
                                outputs += message.point.outputs
                                isFeasible = message.point.isFeasible!!
                                isFrontier = message.point.isFrontier!!
                            }
                        }
                    }
                    is OptimizerRequestMessage.RunFinishedNotification -> {

                        state = (state as? State.Optimizing)?.previous ?: state

                        optimizationFinishedNotification = optimizationFinishedNotificationDTO {
                            runID = message.runID.toDTO()
                        }
                    }
                    is OptimizerRequestMessage.RunNotStartedNotification -> {
                        val clazz = OptimizationNotStartedNotificationDTO::class
                        optimizationNotStartedNotification = optimizationNotStartedNotificationDTO {
                            issues += message.issues
                        }
                    }
                    is OptimizerRequestMessage.RunStartedNotification -> {
                        optimizationStartedNotification = optimizationStartedNotificationDTO {
                            runID = message.runID.toDTO()
                        }
                    }
                    is OptimizerRequestMessage.SimulationCancelRequest -> {
                        cancelRequest = simulationCancelRequestDTO {
                            name = message.name
                        }
                    }
                    is OptimizerRequestMessage.SimulationEvaluationRequest -> {
                        evaluationRequest = simulationEvaluationRequestDTO {
                            name = message.name
                            inputVector.putAll(message.inputVector)
                        }
                    }
                }
            }
        }
    }

    override suspend fun stopOptimization(request: StopOptimizationCommandDTO): StopOptimizationConfirmDTO =
        when (val state = state) {

            is State.Idle -> {
                val parsedID = request.runID.toUUIDOrNull()
                check(parsedID == null || parsedID in model) { "id=$parsedID is not a recognized run ID" }

                stopOptimizationConfirmDTO {
                    runID = parsedID?.toDTO() ?: runID
                }
            }
            is State.Optimizing -> {

                val specifiedRunIDOrNull = request.runID.toUUIDOrNull()
                val message = SimulationProvidedMessage.StopOptimization()
                state.optimizerBoundMessages.send(message)

                val runningID = model.keys.last()
                check(specifiedRunIDOrNull == null || runningID == specifiedRunIDOrNull)

                stopOptimizationConfirmDTO {
                    runID = runningID.toDTO()
                }
            }
        }

    override suspend fun requestRunResult(request: OptimizationResultsQueryDTO): OptimizationResultsResponseDTO {

        val result: RunResult = model.getValue(UUID.fromString(request.runID.value))

        return optimizationResultsResponseDTO {
            runID = result.uuid.toDTO()
            inputColumns += result.inputs
            outputColumns += result.outputs
            points += result.points.map { point ->
                designRowDTO {
                    inputs += point.inputs
                    outputs += point.outputs
                    isFeasible = point.isFeasible!!
                    isFrontier = point.isFrontier!!
                }
            }
        }
    }
}

private inline fun <reified T> checkIs(instance: Any): T {
    check(instance is T){ "expected ${T::class.simpleName}, but was $instance" }
    return instance as T
}

private fun <K, V> Map<K, V>.invert(): Map<V, K> {
    val result = mutableMapOf<V, K>()
    for((key, value) in this){
        val oldKey = result.put(value, key)
        require(oldKey == null) { "duplicate value $oldKey" }
    }
    return result
}