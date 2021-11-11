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
                it.name to Random.nextDouble(it.lowerBound, it.upperBound)
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