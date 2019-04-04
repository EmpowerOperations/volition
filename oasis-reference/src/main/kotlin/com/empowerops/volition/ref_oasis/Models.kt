package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import io.grpc.stub.StreamObserver
import javafx.collections.ObservableMap
import kotlinx.coroutines.channels.Channel
import java.time.Duration
import java.time.LocalDateTime
import java.util.logging.Level

data class Input(
        val name: String,
        val lowerBound: Double,
        val upperBound: Double,
        val currentValue: Double
)

data class Output(
        val name: String
)

data class Message(
        val sender: String,
        val message: String,
        val level: Level = Level.INFO,
        val receiveTime: LocalDateTime = LocalDateTime.now()
)

sealed class EvaluationResult {
    data class Success(val name: String, val result: Map<String, Double>) : EvaluationResult()
    data class TimeOut(val name: String) : EvaluationResult()
    data class Failed(val name: String, val exception: String) : EvaluationResult()
    data class Error(val name: String, val exception: String) : EvaluationResult()
}

sealed class CancelResult {
    data class Canceled(val name: String) : CancelResult()
    data class CancelFailed(val name: String, val exception: String) : CancelResult()
}

data class Result(
        val name: String,
        val resultType: String,
        val inputs: Map<String, Double> = emptyMap(),
        val outputs: Map<String, Double> = emptyMap(),
        val message: String = ""
)

interface Nameable{
    val name: String
}

fun <T : Nameable> List<T>.getValue(name: String) : T = single { it.name == name }
fun <T : Nameable> List<T>.getNamed(name: String?) : T? = singleOrNull { it.name == name }
fun <T : Nameable> List<T>.hasName(name: String?): Boolean = any { it.name == name }
fun <T : Nameable> List<T>.replace(old: T, new: T) : List<T> = this - old + new
fun <T : Nameable> List<T>.getNames(): List<String> = map{it.name}

data class Simulation(
        override val name: String,
        val inputs: List<Input>,
        val outputs: List<Output>,
        val description: String,
        val input: StreamObserver<OASISQueryDTO>,
        val output: Channel<SimulationResponseDTO>,
        val update: Channel<NodeStatusCommandOrResponseDTO>,
        val error: Channel<ErrorResponseDTO>
) : Nameable

data class Proxy(
        override val name : String,
        val inputs: List<Input> = emptyList(),
        val outputs: List<Output> = emptyList(),
        val timeOut: Duration? = null
) : Nameable
