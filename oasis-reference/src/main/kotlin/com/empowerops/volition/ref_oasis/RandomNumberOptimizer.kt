package com.empowerops.volition.ref_oasis

import com.empowerops.babel.BabelExpression
import kotlin.random.Random

interface Optimizer {
    fun generateInputs(inputs: List<Input>, constraints: List<BabelExpression>): Map<String, Double>
}

class RandomNumberOptimizer : Optimizer {
    override fun generateInputs(inputs: List<Input>, constraints: List<BabelExpression>): Map<String, Double> {
        var result: Map<String, Double>

        //just guess & check, works well enough for cheap constraints at roughly ~100k evals/sec
        do {
            result = inputs.associate {
                it.name to when(it) {
                    is Input.Continuous -> Random.nextDouble(it.lowerBound, it.upperBound)
                    is Input.DiscreteRange -> {
                        val maxSteps = ((it.upperBound - it.lowerBound) / it.stepSize).toInt()
                        val steps = Random.nextInt(maxSteps+1)
                        it.lowerBound + (steps * it.stepSize)
                    }
                    is Input.ValueSet -> {
                        val pickedIndex = Random.nextInt(it.valuesSet.size)
                        it.valuesSet[pickedIndex]
                    }
                }
            }
        } while(constraints.any { it.evaluate(result) > 0.0 })

        return result;
    }
}

class FixValueOptimizer(val value: Double) : Optimizer {

    override fun generateInputs(inputs: List<Input>, constraints: List<BabelExpression>): Map<String, Double> = inputs.associate {
        it.name to value
    }
}