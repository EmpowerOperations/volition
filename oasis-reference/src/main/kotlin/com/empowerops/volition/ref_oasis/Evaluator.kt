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

class Evaluator(private val dep: Dependencies) {

    suspend fun start(input: Map<String, Double>,
                      baseTasks: List<EvaluationTask>
    ): Map<String, Double>  = coroutineScope{
        val resultMap = HashMap<String, Double>()
        baseTasks.map { async { it.evaluate(input) } }.awaitAll().forEach { resultMap.putAll(it) }
        val newInputMap = resultMap.toMap() + input
        baseTasks.flatMap { dep.findNextTasks(it) }
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

interface Dependencies {
    fun findNextTasks(evaluationTask: EvaluationTask): List<EvaluationTask>
}

class SequentialDependencies(val list: List<EvaluationTask>) : Dependencies{
    override fun findNextTasks(evaluationTask: EvaluationTask): List<EvaluationTask> = if (evaluationTask.equals(list.last())) {
        emptyList()
    } else {
        listOf(list[list.indexOf(evaluationTask) + 1])
    }
}

class FakeDependencies(val map: Map<EvaluationTask, List<EvaluationTask>>) : Dependencies{
    override fun findNextTasks(evaluationTask: EvaluationTask): List<EvaluationTask> {
        return map[evaluationTask]?: emptyList()
    }
}