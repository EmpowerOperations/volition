package com.empowerops.volition.ref_oasis

import com.empowerops.babel.BabelExpression
import com.google.common.annotations.VisibleForTesting
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import java.util.logging.Level

interface IssueFinder{
    fun findIssues() : List<Issue>
}

class RunResult(
        val uuid : UUID,
        val inputs: List<String>,
        val outputs: List<String>,
        val points: List<ExpensivePointRow>,
)
data class ExpensivePointRow(
        val inputs: List<Double>,
        val outputs: List<Double>,
        val isFeasible: Boolean?, //nullable for seed values
        var isFrontier: Boolean? //mutable to save a bunch of copying
)
fun ExpensivePointRow.dominates(right: ExpensivePointRow): Boolean {
    val left = this
    if(left === right) return false

    for(index in outputs.indices){
        if(left.outputs[index] > right.outputs[index]){
            return false
        }
    }
    return true
}

data class Input(
        val name: String,
        val lowerBound: Double,
        val upperBound: Double,
        val stepSize: Double?
)

data class Output(
        val name: String
        // TBD: current value?
)

data class MathExpression(
        val name: String,
        val expression: BabelExpression
)

data class Message(
        val sender: String,
        val message: String,
        val level: Level = Level.INFO,
        val receiveTime: LocalDateTime = LocalDateTime.now()
)

sealed class EvaluationResult(
        open val name: String,
        open val inputs: Map<String, Double> = emptyMap(),
        open val result: Map<String, Double> = emptyMap()
) {
    data class Success(
            override val name: String,
            override val inputs: Map<String, Double>,
            override val result: Map<String, Double>
    ) : EvaluationResult(name, inputs, result)

    data class TimeOut(
            override val name: String,
            override val inputs: Map<String, Double>
    ) : EvaluationResult(name, inputs)

    data class Failed(
            override val name: String,
            override val inputs: Map<String, Double>,
            val exception: String
    ) : EvaluationResult(name, inputs)

    data class Error(
            override val name: String,
            override val inputs: Map<String, Double>,
            val exception: String
    ) : EvaluationResult(name, inputs)

    data class Terminated(
            override val name: String,
            override val inputs: Map<String, Double>,
            val message: String
    ): EvaluationResult(name, inputs)
}

data class Simulation(
        val name: String,
        val inputs: List<String> = emptyList(),
        val outputs: List<String> = emptyList(),
        val timeOut: Duration? = null,
        val autoMap: Boolean = true,
        val inputMapping: Map<String, ParameterName>? = null,
        val outputMapping: Map<ParameterName, String>? = null
)

data class Issue(val message: String)

