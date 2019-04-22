package com.empowerops.volition.ref_oasis

import kotlinx.coroutines.*

class Evaluable(val inputs: List<String>,
                val outputs: List<String>) {
    suspend fun evaluate(symbolMap: Map<String, Double>): Map<String, Double> {
        val inputs = inputs.associate { it to symbolMap.getValue(it) }
        val output = outputs.associate { it to Math.random() }
        delay(500)//send input, expecting result from client
        return output
    }
}

class EvaluationTask(private val evaluable: Evaluable) {
    suspend fun evaluate(symbolMap: Map<String, Double>): Map<String, Double> {
        return evaluable.evaluate(symbolMap)
    }
}

/**
 * Not yet used, also not intended
 * This may not be the most efficient way and it is just a proof of concept using some basic recursion and the
 * fact that coroutine are light weight and cheap.
 * Some note:
 * This evaluator is designed to be able to run parallel using coroutine, however, this is not really partial and a good
 * dispatcher/order finding is much more important
 *  1. first of all, this is not a good algorithm it self, it is not correct
 *  2. it is also not efficient, the base line of a order is not there
 *  3. this doesn't considering anything else but running everything that can run at the same tim
 */
class Evaluator(private val dep: EvaluationOrder) {

    suspend fun start(input: Map<String, Double>,
                      baseTasks: List<EvaluationTask>
    ): Map<String, Double>  = coroutineScope{
        val resultMap = HashMap<String, Double>()
        baseTasks.map { async { it.evaluate(input) } }.awaitAll().forEach { resultMap.putAll(it) }
        val newInputMap = resultMap.toMap() + input
        baseTasks.flatMap { dep.findNextTasks(it) }.toSet()
                .map { async { evaluate(it, newInputMap) } }
                .run { awaitAll() }
                .forEach(resultMap::putAll)
        resultMap
    }

    private suspend fun evaluate(
            task: EvaluationTask,
            input: Map<String, Double>
    ): Map<String, Double> = coroutineScope {
        val resultMap = HashMap<String, Double>()
        resultMap.putAll(withContext(Dispatchers.Default) { task.evaluate(input) })
        val newInputMap = resultMap.toMap() + input
        val findNextTasks = dep.findNextTasks(task)
        findNextTasks
                .map { async { evaluate(it, newInputMap) } }
                .run { awaitAll() }
                .forEach(resultMap::putAll)
        resultMap.toMap()
    }
}

interface EvaluationOrder {
    fun findNextTasks(evaluationTask: EvaluationTask): List<EvaluationTask>
}

class SequentialEvaluationOrder(val list: List<EvaluationTask>) : EvaluationOrder{
    override fun findNextTasks(evaluationTask: EvaluationTask): List<EvaluationTask> = if (evaluationTask.equals(list.last())) {
        emptyList()
    } else {
        listOf(list[list.indexOf(evaluationTask) + 1])
    }
}

class FakeEvaluationOrder(val map: Map<EvaluationTask, List<EvaluationTask>>) : EvaluationOrder{
    override fun findNextTasks(evaluationTask: EvaluationTask): List<EvaluationTask> {
        return map[evaluationTask]?: emptyList()
    }
}