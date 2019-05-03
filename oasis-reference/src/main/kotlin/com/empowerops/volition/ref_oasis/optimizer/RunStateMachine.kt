package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.ModelService
import com.empowerops.volition.ref_oasis.model.RunResources
import com.empowerops.volition.ref_oasis.optimizer.State.*
import kotlinx.coroutines.channels.Channel
import java.util.*

sealed class State {
    object Idle : State()
    object StartPending : State()
    object Running : State()
    object PausePending : State()
    object Paused : State()
    object StopPending : State()
    object ForceStopPending : State()
}



class RunStateMachine {
    var currentState: State = Idle
        private set
    val states : Channel<State> = Channel()
    val runResources: Channel<RunResources> = Channel()

    suspend fun initService(optimizerService: OptimizerService) {
        optimizerService.startProcess()
    }

    private val stateTable: Map<State, List<State>> = mapOf(
            Idle to listOf(StartPending),
            StartPending to listOf(Running, Idle),
            Running to listOf(PausePending, StopPending),
            PausePending to listOf(Paused, StopPending),
            Paused to listOf(Running, StopPending),
            StopPending to listOf(Idle, ForceStopPending),
            ForceStopPending to listOf(Idle)
    )

    fun transferTo(newState: State): Boolean = if (newState in stateTable.getValue(currentState)) {
        currentState = newState
        true
    } else {
        false
    }

    private fun canTransferTo(newState: State): Boolean = newState in stateTable.getValue(currentState)

    fun canStart(modelService: ModelService): Boolean {
        if (modelService.findIssues().isNotEmpty()) return false
        if (currentState != Idle) return false
        if (!canTransferTo(StartPending)) return false
        return true
    }

    fun canStop(): Boolean {
        if (!canTransferTo(StopPending)) return false
        return true
    }

    fun canPause(): Boolean {
        if(currentState != Running) return false
        if(! canTransferTo(PausePending)) return false
        return true
    }

    fun canForceStop(): Boolean {
        if (currentState != StopPending) return false
        if (!canTransferTo(ForceStopPending)) return false
        return true
    }

    fun canResume(): Boolean {
        if (currentState != Paused) return false
        if (!canTransferTo(Running)) return false
        return true
    }


    suspend fun start(modelService: ModelService){
        if (!canStart(modelService)) return
        states.send(StartPending)
        runResources.send(RunResources(UUID.randomUUID()))
    }

    suspend fun stop() {
        if (!canStop()) return
        states.send(StopPending)
    }

    suspend fun pause() {
        if( ! canPause()) return
        states.send(PausePending)
    }
    suspend fun forceStop() {
        if (!canForceStop()) return
        states.send(ForceStopPending)
    }
    suspend fun resume() {
        if (!canResume()) return
        states.send(Running)
    }
}
