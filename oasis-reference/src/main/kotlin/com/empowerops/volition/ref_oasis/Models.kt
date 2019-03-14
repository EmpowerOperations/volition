package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.ErrorResponseDTO
import com.empowerops.volition.dto.NodeStatusCommandOrResponseDTO
import com.empowerops.volition.dto.OASISQueryDTO
import com.empowerops.volition.dto.SimulationResponseDTO
import io.grpc.stub.StreamObserver
import javafx.collections.ObservableMap
import kotlinx.coroutines.channels.Channel
import java.time.Duration
import java.time.LocalDateTime

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
        val receiveTime: LocalDateTime = LocalDateTime.now()
)

data class Result(
        val name: String,
        val resultType: String,
        val inputs: String,
        val outputs: String
)

interface Nameable{
    val name: String
}

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
