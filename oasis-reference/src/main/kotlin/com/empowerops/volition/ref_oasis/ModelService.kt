package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.ErrorResponseDTO
import com.empowerops.volition.dto.NodeStatusCommandOrResponseDTO
import com.empowerops.volition.dto.OASISQueryDTO
import com.empowerops.volition.dto.SimulationResponseDTO
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.channels.Channel
import java.time.Duration
import java.time.LocalDateTime

interface ModelService {
    var simByName: Map<String, Simulation>
    fun updateStatusMessage(message: String)
    fun addMessage(message: Message)
    fun addResult(result: Result)
    fun setDuration(nodeName: String?, timeOut: Duration?)

    fun updateSim(newNode: Simulation)
    fun addNewSim(simulation: Simulation)
    fun removeSim(name: String)
    fun renameSim(target: Simulation, newName: String, oldName: String)
}

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

data class Simulation(
        val name: String,
        val inputs: List<Input>,
        val outputs: List<Output>,
        val description: String,
        val input: StreamObserver<OASISQueryDTO>,
        val output: Channel<SimulationResponseDTO>,
        val update: Channel<NodeStatusCommandOrResponseDTO>,
        val error: Channel<ErrorResponseDTO>,
        val timeOut: Duration? = null
)