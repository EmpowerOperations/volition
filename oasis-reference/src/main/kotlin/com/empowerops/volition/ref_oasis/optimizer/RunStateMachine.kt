package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.Issue
import com.empowerops.volition.ref_oasis.model.ModelService
import com.empowerops.volition.ref_oasis.model.RunResources
import com.empowerops.volition.ref_oasis.optimizer.State.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

interface StateMachineControl{
    suspend fun start(completableDeferred: CompletableDeferred<RunStateMachine.StartResult>)
    suspend fun stop(): Boolean
}

// todo ho man, this is not a good hting. OASIS has this for legacy.
// But literally this is exactly what coroutines were designed to _avoid_
// replace this with an interactive in-flight coroutine
class RunStateMachine(val modelService: ModelService) : StateMachineControl {
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

    fun canStart(): Boolean {
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

    sealed class StartResult {
        data class Success(val runID : UUID) : StartResult()
        data class Failed(val issues : List<Issue>) : StartResult()
    }

    override suspend fun start(result : CompletableDeferred<StartResult>) {
        if (!canStart()) {
            result.complete(StartResult.Failed(modelService.findIssues()))
        }

        states.send(StartPending)
        val runID = UUID.randomUUID()
        result.complete(StartResult.Success(runID))
        runResources.send(RunResources(runID))
    }

    override suspend fun stop() : Boolean {
        if (!canStop()) return false
        states.send(StopPending)
        return true
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
