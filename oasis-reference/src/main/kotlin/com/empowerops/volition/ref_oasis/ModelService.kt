package com.empowerops.volition.ref_oasis

import com.google.common.annotations.VisibleForTesting
import com.google.common.eventbus.EventBus
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

data class Input(
        val name: String,
        val lowerBound: Double,
        val upperBound: Double,
        val currentValue: Double
)

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

data class Simulation(
        val name: String,
        val inputs: List<Input> = emptyList(),
        val outputs: List<Output> = emptyList(),
        val description: String= "",
        val timeOut: Duration? = null,
        val autoImport: Boolean = true,
        val inputMapping: Map<String, ParameterName> = emptyMap(),
        val outputMapping: Map<ParameterName, String> = emptyMap()
)

data class Issue(val message: String)



class ModelService(private val eventBus: EventBus) : IssueFinder {

    private val inputs: Set<Input> = linkedSetOf()
    private val outputs: Set<Output> = linkedSetOf()



    val simulations: MutableList<Simulation> = arrayListOf()

    @VisibleForTesting
    private var resultList : Map<UUID, List<EvaluationResult>> = emptyMap()

    fun addSim(simulation: Simulation) {
        simulations += simulation
    }

    fun removeSim(name: String) {
        simulations.removeIf { it.name == name }
    }

    fun updateSimAndConfiguration(newSim: Simulation) : Boolean {
        TODO()
    }

    fun autoSetup(newSim: Simulation) : Boolean {

        if(newSim !in simulations) return false

        TODO()
    }

     override fun findIssues(): List<Issue> {
         TODO()
    }

    fun addNewResult(runID: UUID, result: EvaluationResult) {
        val results = resultList.getOrElse(runID) { emptyList() }
        resultList += runID to results + result
        eventBus.post(NewResultEvent(result))
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
