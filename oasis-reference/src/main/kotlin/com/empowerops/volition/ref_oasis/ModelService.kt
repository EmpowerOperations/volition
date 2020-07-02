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
        val resultMessage: String,
        val points: List<ExpensivePointRow>,
        val frontier: List<ExpensivePointRow>
)
data class ExpensivePointRow(
        val inputs: List<Double>,
        val outputs: List<Double>,
        val isFeasible: Boolean
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

class ModelService() : IssueFinder {

    private val _inputs: MutableSet<Input> = linkedSetOf<Input>()
    private val _outputs: MutableSet<Output> = linkedSetOf<Output>()

    private val runs: MutableMap<UUID, RunResult> = linkedMapOf()

    val simulations: MutableList<Simulation> = arrayListOf()

    @VisibleForTesting
    private var resultList : Map<UUID, List<EvaluationResult>> = emptyMap()

//    val inputs: Set<Input> get() = _inputs.toSet()
//    val outputs: Set<Output> get() = _outputs.toSet()

    fun removeSim(name: String) {
        simulations.removeIf { it.name == name }
    }

    fun updateSimAndConfiguration(newSim: Simulation) : Boolean {
        TODO()
    }

    fun getResult(id: UUID): RunResult = runs.getValue(id)
    fun setResult(id: UUID, result: RunResult) { runs[id] = result }

     override fun findIssues(): List<Issue> {
         TODO()
    }

    fun addNewResult(runID: UUID, result: EvaluationResult) {
        val results = resultList.getOrElse(runID) { emptyList() }
        resultList += runID to results + result
        TODO("eventBus.post(NewResultEvent(result))")
    }

    fun findSimulationName(name: String): Simulation? = simulations.singleOrNull { it.name == name }

    fun updateSimulation(name: String, transform: (Simulation) -> Simulation){
        val sim = findSimulationName(name)

        if(sim != null){
            simulations.replaceAll {
                if(it == sim) transform(it) else it
            }
        }
    }

}
