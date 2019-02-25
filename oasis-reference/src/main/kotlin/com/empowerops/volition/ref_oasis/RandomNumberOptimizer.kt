package com.empowerops.volition.ref_oasis

import kotlin.random.Random

class RandomNumberOptimizer {
    fun generateInputs(inputs: List<Input>): Map<String, Double> = inputs.associate {
        it.name to Random.nextDouble(it.lowerBound, it.upperBound)
    }
}