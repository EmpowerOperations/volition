package com.empowerops.volition.ref_oasis

import com.nhaarman.mockitokotlin2.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.IllegalArgumentException
import java.lang.NullPointerException
import java.util.*

class KotlinLanguageFixture{

    @Test
    fun `when calling with a null on fromString uuid`(){
        assertThrows<NullPointerException> {
            val fromString = UUID.fromString(null)
        }
    }

    @Test
    fun `when calling with a empty on fromString on uuid`(){
        assertThrows<IllegalArgumentException> {
            val fromString = UUID.fromString("")
        }
    }

    @Test
    fun `when stub a parameter should return the stub`(){
        //setup
        val test = mock<TestClass>()

        //act
        whenever(test.a) doReturn (listOf("A", "B"))

        //assert
        assertThat(test.a).isEqualTo(listOf("A", "B"))
    }


    @Test
    fun `when use real`() {
        val real = TestClass(emptyList())
        val mapOf = mapOf("T" to real)
        var failResult = false

        //act
        val result: Any = mapOf["T"]?.doThing()?:failResult

        //assert
        assertTrue(real.done)
        assertThat(result).isNotEqualTo(failResult)
    }

    @Test
    fun `when use fake`() {
        val mock = mock<TestClass>()
        val mapOf = mapOf("T" to mock)
        var failResult = false

        //act
        val result: Any = mapOf["T"]?.doThing()?:failResult

        //assert
        verify(mock, times(1)).doThing()
        assertThat(result).isNotEqualTo(failResult)
    }

    @Test
    fun `when use real with suspend`() = runBlocking<Unit>{
        //setup
        val real = TestClass(emptyList())
        val mapOf = mapOf("T" to real)
        var failResult = false

        //act
        val result: Any = mapOf["T"]?.doThingDelayed()?:failResult

        //assert
        assertTrue(real.doneAsync)
        assertThat(result).isNotEqualTo(failResult)
    }

    @Test
    fun `when use fake with suspend`()= runBlocking<Unit>{
        //setup
        val mock = mock<TestClass> {
            onBlocking { doThingDelayed() } doReturn Unit
        }
        val mapOf = mapOf("T" to mock)
        var failResult = "null path"

        //act
        val result: Any = mapOf["T"]?.doThingDelayed()?:failResult

        //assert
        verify(mock, times(1)).doThingDelayed()
        assertThat(result).isNotEqualTo(failResult)
    }

    class TestClass(val a : List<String>){
        var doneAsync = false
        var done = false
        suspend fun doThingDelayed(){
            delay(50)
            doneAsync = true
        }

        suspend fun doThingDelayedThenReturn():String{
            delay(50)
            doneAsync = true
            return "method path"
        }

        fun doThing(){
            done = true
        }
    }
}

