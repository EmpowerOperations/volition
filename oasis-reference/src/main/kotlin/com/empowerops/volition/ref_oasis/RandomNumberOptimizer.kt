package com.empowerops.volition.ref_oasis

import kotlin.random.Random

interface Optimizer {
    fun addCompleteDesign(expensivePoint: Map<String, Double>)
    fun generateInputs(inputs: List<Input>): Map<String, Double>
}

class RandomNumberOptimizer : Optimizer {

    override fun addCompleteDesign(expensivePoint: Map<String, Double>) {
        TODO("Not yet implemented")
    }

    override fun generateInputs(inputs: List<Input>): Map<String, Double> = inputs.associate {
        it.name to Random.nextDouble(it.lowerBound, it.upperBound)
    }
}

class FixValueOptimizer(val value: Double) : Optimizer {
    override fun addCompleteDesign(expensivePoint: Map<String, Double>) {
        TODO("Not yet implemented")
    }

    override fun generateInputs(inputs: List<Input>): Map<String, Double> = inputs.associate {
        it.name to value
    }
}