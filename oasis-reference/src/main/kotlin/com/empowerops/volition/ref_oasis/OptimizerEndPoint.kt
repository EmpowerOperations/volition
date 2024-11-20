package com.empowerops.volition.ref_oasis

import com.empowerops.babel.BabelCompiler
import com.empowerops.babel.BabelExpression
import com.empowerops.volition.dto.*
import com.empowerops.volition.dto.SimulationEvaluationCompletedResponseDTO.OutputCase
import com.empowerops.volition.dto.toRunIDDTO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.util.*

typealias ParameterName = String

sealed class State {
    object Idle: State() {}
    data class Optimizing(val optimizerBoundMessages: Channel<SimulationProvidedMessage>, val previous: Idle): State()
}

class OptimizerEndpoint(
        private val model: Map<UUID, RunResult>,
        private val optimizationActorFactory: OptimizationActorFactory
) : UnaryOptimizerGrpcKt.UnaryOptimizerCoroutineImplBase() {

    val compiler = BabelCompiler

    private var state: State = State.Idle
        get() = field
        set(value) { field = value }

    override suspend fun offerSimulationResult(request: SimulationEvaluationCompletedResponseDTO): SimulationEvaluationResultConfirmDTO {
        val state = checkIs<State.Optimizing>(state)

        val iterationIdx = request.iterationIndex.toUInt()
        val message = when {
            request.outputVectorMap.isNotEmpty() -> {
                SimulationProvidedMessage.EvaluationResult(iterationIdx, request.outputVectorMap)
            }
            request.outputCase == OutputCase.VECTOR -> {
                SimulationProvidedMessage.EvaluationResult(iterationIdx, request.vector.entriesMap)
            }
            request.outputCase == OutputCase.FAILURE -> {
                SimulationProvidedMessage.ErrorResponse(iterationIdx, "user offered error result")
            }
            else -> TODO("no error result, no outputVector, and no vector.entries?")
        }
        state.optimizerBoundMessages.send(message)

        return SimulationEvaluationResultConfirmDTO.newBuilder().build()

    }

    @Deprecated("The underlying service method is marked deprecated.")
    override suspend fun offerErrorResult(request: SimulationEvaluationErrorResponseDTO): SimulationEvaluationErrorConfirmDTO {
        val state = checkIs<State.Optimizing>(state)

        val element = SimulationProvidedMessage.ErrorResponse(request.iterationIndex.toUInt(), request.message)
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
                when (inputDTO.domainCase!!) {
                    InputParameterDTO.DomainCase.CONTINUOUS -> inputDTO.continuous.let {
                        Input.Continuous(
                            inputDTO.name,
                            it.lowerBound,
                            it.upperBound
                        )
                    }
                    InputParameterDTO.DomainCase.DISCRETE_RANGE -> inputDTO.discreteRange.let {
                        Input.DiscreteRange(
                            inputDTO.name,
                            it.lowerBound,
                            it.upperBound,
                            it.stepSize
                        )
                    }
                    InputParameterDTO.DomainCase.VALUES_SET -> inputDTO.valuesSet.let {
                        Input.ValueSet(
                            inputDTO.name,
                            it.valuesList.toList() // just to remove grpc dsl list wierdness
                        )
                    }
                    InputParameterDTO.DomainCase.DOMAIN_NOT_SET -> TODO()
                }
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
            seedPoints = request.seedPointsList.map {
                ExpensivePointRow(it.inputsList, it.outputsList, null, null)
            }
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
                            runID = message.runID.toRunIDDTO()
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
                            runID = message.runID.toRunIDDTO()
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
                    runID = parsedID?.toRunIDDTO() ?: runID
                }
            }
            is State.Optimizing -> {

                val specifiedRunIDOrNull = request.runID.toUUIDOrNull()
                val message = SimulationProvidedMessage.StopOptimization()
                state.optimizerBoundMessages.send(message)

                val runningID = model.keys.last()
                check(specifiedRunIDOrNull == null || runningID == specifiedRunIDOrNull)

                stopOptimizationConfirmDTO {
                    runID = runningID.toRunIDDTO()
                }
            }
        }

    override suspend fun requestRunResult(request: OptimizationResultsQueryDTO): OptimizationResultsResponseDTO {

        val result: RunResult = model.getValue(UUID.fromString(request.runID.value))

        return optimizationResultsResponseDTO {
            runID = result.uuid.toRunIDDTO()
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