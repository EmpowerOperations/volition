package com.empowerops.volition.ref_oasis

import com.empowerops.babel.BabelExpression
import com.google.common.annotations.VisibleForTesting
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import java.util.logging.Level
import kotlin.math.exp

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

sealed interface Input {

    val name: String

    data class Continuous(
        override val name: String,
        val lowerBound: Double,
        val upperBound: Double
    ): Input

    data class DiscreteRange(
        override val name: String,
        val lowerBound: Double,
        val upperBound: Double,
        val stepSize: Double
    ): Input

    data class ValueSet(
        override val name: String,
        val valuesSet: List<Double>
    ): Input
}

data class Output(
        val name: String
        // TBD: current value?
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

sealed class Evaluable()

data class MathExpression(
    val name: String,
    val expression: BabelExpression
): Evaluable()

data class Simulation(
        val name: String,
        val inputs: List<String> = emptyList(),
        val outputs: List<String> = emptyList(),
        val timeOut: Duration? = null,
        val autoMap: Boolean = true,
        val inputMapping: Map<String, ParameterName>? = null,
        val outputMapping: Map<ParameterName, String>? = null
): Evaluable()

val Evaluable.isConstraint: Boolean get() = when(this){
    is MathExpression -> expression.isBooleanExpression
    is Simulation -> false
}
val Evaluable.inputs: List<String> get() = when(this){
    is MathExpression -> expression.staticallyReferencedSymbols.toList()
    is Simulation -> inputs
}
val Evaluable.outputs: List<String> get() = when(this){
    is MathExpression -> listOf(name)
    is Simulation -> outputs
}

data class Issue(val message: String)

