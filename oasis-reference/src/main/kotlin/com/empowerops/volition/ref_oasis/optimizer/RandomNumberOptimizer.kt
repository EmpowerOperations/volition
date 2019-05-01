package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.Input
import kotlin.random.Random

interface InputGenerator{
    fun generateInputs(inputs: List<Input>): Map<String, Double>
}

class RandomNumberOptimizer : InputGenerator {
    override fun generateInputs(inputs: List<Input>): Map<String, Double> = inputs.associate {
        it.name to Random.nextDouble(it.lowerBound, it.upperBound)
    }
}

class FixValueOptimizer(val value: Double) : InputGenerator {
    override fun generateInputs(inputs: List<Input>): Map<String, Double> = inputs.associate {
        it.name to value
    }
}