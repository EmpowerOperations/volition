package com.empowerops.volition.ref_oasis

import com.empowerops.volition.ref_oasis.State.*

enum class State {
    Idle,
    StartPending,
    Running,
    PausePending,
    Paused,
    StopPending
}

class OptimizerStateMachine {
    var currentState: State = Idle
    private val stateTable: Map<State, List<State>> = mapOf(
            Idle to listOf(StartPending),
            StartPending to listOf(Running, Idle),
            Running to listOf(PausePending, StopPending),
            PausePending to listOf(Paused, StopPending),
            Paused to listOf(Running, StopPending),
            StopPending to listOf(Idle)
    )

    fun transferTo(newState: State): Boolean = if (newState in stateTable.getValue(currentState)) {
        currentState = newState
        true
    } else {
        //do log
        false
    }

}