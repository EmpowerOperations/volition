package com.empowerops.volition.ref_oasis

import kotlinx.coroutines.*

class Evaluable(val inputs: List<String>,
                val outputs: List<String>) {

    suspend fun evaluate(): Map<String, Double> {
        delay(500)//send input, expecting result from client
        return emptyMap()
    }
}

class EvaluationTask(private val evaluable: Evaluable) {
    suspend fun evaluateAsync(input: Map<String, Double>): Map<String, Double> {
        try {
            return evaluable.evaluate()
        } finally {
            println("Canceled. Evaluable: $evaluable Input: $input")
        }
    }
}

class Evaluator(val dep: Dependencies) {

    suspend fun start(input: Map<String, Double>,
                      baseTasks: List<EvaluationTask>
    ): Map<String, Double>  = coroutineScope{
        val resultMap = HashMap<String, Double>()
        baseTasks
                .map { async { it.evaluateAsync(input) } }
                .let { it.awaitAll() }
                .forEach { resultMap.putAll(it) }

        val newInputMap = resultMap.toMap() + input

        baseTasks
                .flatMap { dep.findNextTasks(it) }
                .map { async { evaluate(it, newInputMap) } }
                .let { it.awaitAll() }
        resultMap
    }

    private suspend fun evaluate(
            task: EvaluationTask,
            input: Map<String, Double>
    ): Map<String, Double> = coroutineScope {
        val resultMap = HashMap<String, Double>()
        resultMap.putAll(withContext(Dispatchers.Default) { task.evaluateAsync(input) })
        val newInputMap = resultMap.toMap() + input
        dep.findNextTasks(task)
                .map { async { evaluate(it, newInputMap) } }
                .let { it.awaitAll() }
                .forEach { resultMap.putAll(it) }
        resultMap.toMap()
    }
}

interface Dependencies {
    fun findNextTasks(evaluationTask: EvaluationTask): List<EvaluationTask>
}