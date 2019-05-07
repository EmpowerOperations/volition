package com.empowerops.volition.ref_oasis.model

import com.empowerops.volition.dto.*
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
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

sealed class EvaluationResult(
        open val name: String,
        open val inputs: Map<String, Double> = emptyMap(),
        open val result: Map<String, Double> = emptyMap()) {
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

    data class Terminated(
            override val name: String,
            override val inputs: Map<String, Double>,
            val message: String
    ): EvaluationResult(name, inputs)
}



sealed class CancelResult {
    data class Canceled(val name: String) : CancelResult()
    data class CancelFailed(val name: String, val exception: String) : CancelResult()
    data class CancelTerminated(val name: String, val exception: String) : CancelResult()
}

interface Nameable {
    val name: String
}

fun <T : Nameable> List<T>.getValue(name: String): T = single { it.name == name }
fun <T : Nameable> List<T>.getNamed(name: String?): T? = singleOrNull { it.name == name }
fun <T : Nameable> List<T>.hasName(name: String?): Boolean = any { it.name == name }
fun <T : Nameable> List<T>.replace(old: T, new: T): List<T> = this - old + new
fun <T : Nameable> List<T>.getNames(): List<String> = map { it.name }

data class Simulation(
        override val name: String,
        val input: StreamObserver<RequestQueryDTO>,
        val inputs: List<Input> = emptyList(),
        val outputs: List<Output> = emptyList(),
        val description: String= "",
        val output: Channel<SimulationResponseDTO> = Channel(Channel.RENDEZVOUS),
        val update: Channel<NodeStatusCommandOrResponseDTO> = Channel(Channel.RENDEZVOUS),
        val error: Channel<ErrorResponseDTO> = Channel(Channel.RENDEZVOUS)
) : Nameable

data class ForceStopSignal(
        override val name: String,
        val completableDeferred : CompletableDeferred<Unit> = CompletableDeferred()
): Nameable

data class Proxy(
        override val name: String,
        val inputs: List<Input> = emptyList(),
        val outputs: List<Output> = emptyList(),
        val timeOut: Duration? = null
) : Nameable

data class Issue(val message: String)


class Helpers{
    companion object {
        val NullUUID = UUID(0,0)
    }
}


