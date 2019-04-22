package com.empowerops.volition.ref_oasis

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis

class EvaluatorFixture{

    @Test
    fun `test1`() {
            //setup
            val a1Task = EvaluationTask(Evaluable(listOf("x1", "x2"), listOf("f1")))
            val b1Task = EvaluationTask(Evaluable(listOf("x1", "f1"), listOf("f2")))
            val b2Task = EvaluationTask(Evaluable(listOf("x2", "f1"), listOf("f3")))

            val sequentialDependencies = FakeDependencies(mapOf(a1Task to listOf(b1Task, b2Task)))
            val evaluator = Evaluator(sequentialDependencies)

            var result : Map<String, Double>? = null

            //act
            val time = measureTimeMillis {
                result = runBlocking { evaluator.start(mapOf("x1" to 1.0, "x2" to 1.0), listOf(a1Task)) }
            }

            //assert
            assertThat(result!!).hasSize(3)
            assertThat(time).isGreaterThan(1200)
    }



}
