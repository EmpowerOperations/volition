package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.optimizer.State.*

enum class State {
    Idle,
    StartPending,
    Running,
    PausePending,
    Paused,
    StopPending,
    ForceStopPending,
}

class RunStateMachine : IStateMachine {
    override var currentState: State = Idle
    override val stateTable: Map<State, List<State>> = mapOf(
            Idle to listOf(StartPending),
            StartPending to listOf(Running, Idle),
            Running to listOf(PausePending, StopPending),
            PausePending to listOf(Paused, StopPending),
            Paused to listOf(Running, StopPending),
            StopPending to listOf(Idle, ForceStopPending),
            ForceStopPending to listOf(Idle)
    )
}

interface IStateMachine{
    var currentState: State
    val stateTable : Map<State, List<State>>

    fun transferTo(newState: State): Boolean = if (newState in stateTable.getValue(currentState)) {
        currentState = newState
        true
    } else {
        false
    }

    fun canTransferTo(newState: State) : Boolean = newState in stateTable.getValue(currentState)
}