package com.empowerops.volition.ref_oasis

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.logging.Logger


typealias InputVector = Map<String, Double>
typealias OutputVector = Map<String, Double> //would an inline class actually help catch type errors?

sealed class OptimizerRequestMessage {

    data class SimulationEvaluationRequest(val name: String, val pointID: UInt, val inputVector: InputVector): OptimizerRequestMessage()
    data class SimulationCancelRequest(val name: String, val pointID: UInt): OptimizerRequestMessage()

    data class RunStartedNotification(val runID: UUID): OptimizerRequestMessage()
    data class RunFinishedNotification(val runID: UUID): OptimizerRequestMessage()

    data class RunNotStartedNotification(val issues: List<String>): OptimizerRequestMessage()
    data class ExpensivePointFoundNotification(val point: ExpensivePointRow): OptimizerRequestMessage()
}

sealed class SimulationProvidedMessage {
    data class EvaluationResult(
            val pointID: UInt,
            val outputVector: OutputVector
    ): SimulationProvidedMessage()

    data class ErrorResponse(
            val name: String,
            val pointID: UInt,
            val message: String
    ) : SimulationProvidedMessage()

    data class Message(
            val name: String,
            val pointID: UInt,
            val message: String
    ) : SimulationProvidedMessage()

    data class SimulationConfiguration(val sim: Simulation): SimulationProvidedMessage()

    data class StopOptimization(
        val isSyntheticFromConvergence: Boolean = false
    ): SimulationProvidedMessage()

    data class StartOptimizationRequest(
        val inputs: List<Input>,
        val transforms: List<Evaluable>,
        val seedPoints: List<ExpensivePointRow>,
        val settings: OptimizationSettings
    ): SimulationProvidedMessage()
}

data class OptimizationSettings(
        val runtime: Duration?,
        val iterationCount: Int?,
        val targetObjectiveValue: Double?,
        val concurrentRunCount: UInt
)

typealias OptimizationActor = Flow<OptimizerRequestMessage>

//SimulationProvidedMessage
class OptimizationActorFactory(
        val scope: CoroutineScope,
        val optimizer: Optimizer,
        val model: MutableMap<UUID, RunResult>,
//        val eventBus: EventBus
) {

    private val logger = Logger.getLogger(OptimizationActorFactory::class.qualifiedName)

    fun make(
        startMessage: SimulationProvidedMessage.StartOptimizationRequest,
        channel: ReceiveChannel<SimulationProvidedMessage>
    ): OptimizationActor {
        return flow<OptimizerRequestMessage> {

            val runID = UUID.randomUUID()

            val sim = startMessage.transforms.filterIsInstance<Simulation>().singleOrNull()

            val orderedInputColumns = startMessage.inputs.map { it.name }
            val orderedOutputColumns = startMessage.transforms.flatMap {
                when (it) {
                    is MathExpression -> listOf(it.name)
                    is Simulation -> it.outputs
                }
            }
            val orderedColumns = orderedInputColumns + orderedOutputColumns
            val constraints = startMessage.transforms
                .filter { it.isConstraint }
                .map { it as MathExpression }

            val constraintNames = constraints.map { it.name }

            // grrr, went through several versions, finally gonna give it a very cheap
            // implementation of the whole thing.
            // Adjacency list style:
            val successorsByEvaluable: Map<Evaluable, List<Evaluable>> = startMessage.transforms.associateWith { entry ->
                startMessage.transforms.mapNotNull { potentialSuccessor ->
                    when {
                        potentialSuccessor == entry -> null
                        potentialSuccessor.inputs.any { it in entry.outputs } -> potentialSuccessor
                        else /*not us and no outputs in our inputs -> not a predecessor*/ -> null
                    }
                }
            }

            val inputConstraints = startMessage.transforms
                .filterIsInstance<MathExpression>()
                .filter { it.expression.isBooleanExpression }
                .filter { it !in successorsByEvaluable.values.flatten() }
                .map { it.expression }

            val terminalObjectives = successorsByEvaluable
                .filter { (_, successors) -> successors.isEmpty() || successors.all { it.isConstraint } }
                .keys.flatMap { it.outputs }
                .filter { it !in constraintNames }
                .orderedBy(orderedOutputColumns)

            val terminalObjectiveIndicies = terminalObjectives.map { orderedOutputColumns.indexOf(it) }

            fun ExpensivePointRow.dominates(right: ExpensivePointRow): Boolean {

                require(this.outputs.size == right.outputs.size)

                val leftObjectives = this.outputs.getAll(terminalObjectiveIndicies)
                val rightObjectives = right.outputs.getAll(terminalObjectiveIndicies)

                for(index in leftObjectives.indices){
                    if(leftObjectives[index] >= rightObjectives[index]) {
                        return false
                    }
                }

                return true
            }

            val issues = ArrayList<String>()
//            if(sim == null){
//                issues += "no simulation registered on start message -- this optimizer does not support math-only optimizations"
//            }
            val badVars = startMessage.inputs.filter { it.lowerBound > it.upperBound }
            if(badVars.any()){
                issues += "the variables ${badVars.joinToString { it.name }} must have a lower bound less than the upper bound"
            }
            if(startMessage.seedPoints.any { it.inputs.size != orderedInputColumns.size }){
                issues += "some seed points dont match the number of input parameters"
            }
            if(startMessage.seedPoints.any { it.outputs.size != orderedOutputColumns.size }){
                issues += "some seed points dont match the number of output parameters"
            }
            //...etc
            // TODO: implement more validation
            if(issues.any()){
                emit(OptimizerRequestMessage.RunNotStartedNotification(issues))
                return@flow
            }
            else try {
                emit(OptimizerRequestMessage.RunStartedNotification(runID))

                var completedDesigns = startMessage.seedPoints
                    .map { seed ->
                        val inputVector = orderedInputColumns.zip(seed.inputs).toMap()
                        val outputVector = orderedOutputColumns.zip(seed.outputs).toMap()
                        val isActuallyFeasible = constraints.all { it.expression.evaluate(inputVector) <= 0.0 }
                        val isActuallyFrontier = ! startMessage.seedPoints.any { it.dominates(seed) }
                        ExpensivePointRow(seed.inputs, seed.outputs, isActuallyFeasible, isActuallyFrontier)
                    }
                    .toList()

                model[runID] = RunResult(runID, orderedInputColumns, orderedOutputColumns, completedDesigns)

                val targetIterationCount: UInt = startMessage.settings.iterationCount?.toUInt() ?: UInt.MAX_VALUE
                var iterationCount: UInt = 0u;

                val endTime = Instant.now() + (startMessage.settings.runtime ?: Duration.ofSeconds(9_999_999))
                var stopRequest: SimulationProvidedMessage.StopOptimization? = null

//                val evaluator: List<(InputVector) -> OutputVector> = TODO()
                val inputs = Channel<InputVector>(startMessage.settings.concurrentRunCount.toInt())
                val outputs = Channel<ExpensivePointRow>(startMessage.settings.concurrentRunCount.toInt())

//                for (parallelID in 0 .. startMessage.settings.concurrentRunCount){
//                    scope.launch {
//                        val inputVector = inputs.receive()
//                    }
//                }

                optimizing@ while (stopRequest == null
                    && iterationCount < targetIterationCount
                    && Instant.now() < endTime
                ) {

                    // before we start an iteration, poll (check without blocking) our message box
                    // for a stop-optimization request.
                    when(val response = channel.tryReceive().getOrNull()) {
                        is SimulationProvidedMessage.StopOptimization -> {
                            stopRequest = response
                            break@optimizing
                        }
                        null -> Unit //noop,
                        else -> throw IllegalStateException("unexpected early message $response")
                    } as Any

                    try {
                        // otherwise start a simulation evaluation
                        val pointCount: UInt = startMessage.settings.concurrentRunCount.coerceAtMost(iterationCount - targetIterationCount)
                        val inputVector = optimizer.generateInputs(startMessage.inputs, inputConstraints)
                        val evaluationVector = orderedInputColumns.associateWith { inputVector.getValue(it) }.toMutableMap()

                        val remaining = successorsByEvaluable.keys.toQueue()

                        val nextEvaluable = evaluateUntilSimulation(remaining, evaluationVector)

                        if (nextEvaluable == null) {
                            // this is a pure math problem,
                            // so we dont need any evaluation code
                        }
                        if (nextEvaluable is Simulation) {
                            emit(OptimizerRequestMessage.SimulationEvaluationRequest(
                                sim!!.name,
                                iterationCount.toUInt(),
                                evaluationVector.filterKeys { it in sim.inputs }
                            ))

//                            fail; so, i could build some kind of fan-in channel here
                            // is there any other solution?

                            //read the response
                            decodeResponse@while(true) when (val response = channel.receive()) {
                                is SimulationProvidedMessage.Message -> {
                                    //nothing to do
                                }
                                is SimulationProvidedMessage.StopOptimization -> {

                                    // if we get a stop request now, cancel the existing simulation request
                                    // **BUT DO NOT STOP OPTIMIZATION** until the simulation evaluation completes
                                    // (below, with either an ErrorResponse or a EvaluationResult)
                                    emit(OptimizerRequestMessage.SimulationCancelRequest(sim.name, TODO("need point IDs")))
                                    stopRequest = response
                                }
                                is SimulationProvidedMessage.EvaluationResult -> {
                                    evaluationVector += response.outputVector
                                    break@decodeResponse
                                }
                                is SimulationProvidedMessage.ErrorResponse -> {
                                    // we dont update the optimizer state when the evaluation encounters an error
                                    // -- this is approximately true for both this implementation and Empower commercial optimizers
                                    break@decodeResponse
                                }
                                is SimulationProvidedMessage.SimulationConfiguration,
                                is SimulationProvidedMessage.StartOptimizationRequest -> TODO("$response")
                            } as Any
                        }

                        if(stopRequest == null) {
                            val remainingEvaluable = evaluateUntilSimulation(remaining, evaluationVector)
                            check(remainingEvaluable == null) { "after 3 step iteration still had work=$remainingEvaluable" }

                            val newPoint = ExpensivePointRow(
                                evaluationVector.getAll(orderedInputColumns),
                                evaluationVector.getAll(orderedOutputColumns),
                                isFeasible = constraintNames.all { evaluationVector.getValue(it) <= 0.0 },
                                isFrontier = null //compute this below
                            )

                            completedDesigns += newPoint

                            for (design in completedDesigns) {
                                design.isFrontier = completedDesigns.none { it.dominates(design) }
                            }

                            model[runID] = RunResult(runID, orderedInputColumns, orderedOutputColumns, completedDesigns)

                            emit(OptimizerRequestMessage.ExpensivePointFoundNotification(newPoint))
                        }
                    }
                    catch (ex: CancellationException) {
                        if(sim != null) emit(OptimizerRequestMessage.SimulationCancelRequest(sim.name, iterationCount))
                        throw ex
                    }

                    iterationCount += 1u
                }

                model[runID] = RunResult(runID, orderedInputColumns, orderedOutputColumns, completedDesigns)
            }
            catch (ex: CancellationException) {
                logger.warning("optimization actor was cancelled, and is now quitting.")
                throw ex
            }
            finally {
                emit(OptimizerRequestMessage.RunFinishedNotification(runID))
            }
        }
    }

    private fun evaluateUntilSimulation(
        remaining: Queue<Evaluable>,
        inputVector: MutableMap<String, Double>
    ): Evaluable? {
        while (remaining.any()) {

            val satisfiable = remaining.removeFirstOrNull { eval -> inputVector.keys.containsAll(eval.inputs) }

            if(satisfiable == null && remaining.isNotEmpty()) throw IllegalStateException("cant satisfy any of ${remaining.joinToString()}")

            inputVector += when (satisfiable) {
                is Simulation -> {
                    return satisfiable
                }
                is MathExpression -> {
                    val result = satisfiable.expression.evaluate(inputVector)
                    satisfiable.name to result
                }
                null -> return null
            }
        }

        return null
    }
}

fun List<String>.orderedBy(orderer: List<String>): List<String> = this.sortedBy { orderer.indexOf(it) }

fun <T> List<T>.getAll(indicies: List<Int>): List<T> = indicies.map { this[it] }

fun <T> Collection<T>.toQueue(): Queue<T> = LinkedList<T>(this)
fun <T> Queue<T>.removeFirstOrNull(predicate: (T) -> Boolean): T? {
    val itr = iterator()
    while(itr.hasNext()){
        val next = itr.next()
        if(predicate(next)){
            itr.remove()
            return next;
        }
    }
    return null
}
fun <K, V> Map<K, V>.getAll(keys: Collection<K>): List<V> = keys.map { getValue(it) }

fun String.toProtobufAny(): com.google.protobuf.Any {
    return com.google.protobuf.Any.pack(com.google.protobuf.stringValue { value = this@toProtobufAny })
}