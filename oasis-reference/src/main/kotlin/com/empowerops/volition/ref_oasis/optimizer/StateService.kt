package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.*
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.*

class StateService(
        val eventBus: EventBus,
        val modelService: ModelService,
        val stateMachine: RunStateMachine,
        val optimizerService: OptimizerService
) {
    val states = Channel<State>()

    val currentState: State
        get() = stateMachine.currentState

    fun canStart(modelService: ModelService): Boolean {
        if (modelService.findIssues().isNotEmpty()) return false
        if (currentState != State.Idle) return false
        if (!stateMachine.canTransferTo(State.StartPending)) return false
        return true
    }

    fun canStop(): Boolean {
        if (!stateMachine.canTransferTo(State.StopPending)) return false
        return true
    }

    private fun canPause(): Boolean {
        if (currentState != State.Running) return false
        if (!stateMachine.canTransferTo(State.PausePending)) return false
        return true
    }

    private fun canForceStop(): Boolean {
        if (currentState != State.StopPending) return false
        if (!stateMachine.canTransferTo(State.ForceStopPending)) return false
        return true
    }

    private fun canResume(): Boolean {
        if (stateMachine.currentState != State.Paused) return false
        if (!stateMachine.canTransferTo(State.Running)) return false
        return true
    }


    suspend fun start(modelService: ModelService) {
        if (!canStart(modelService)) return
        states.send(State.StartPending)
    }

    suspend fun stop() {
        if (!canStop()) return
        states.send(State.StopPending)
    }

    suspend fun pause() {
        if (!canPause()) return
        states.send(State.PausePending)
    }

    suspend fun forceStop() {
        if (!canForceStop()) return
        states.send(State.ForceStopPending)
    }

    suspend fun resume() {
        if (!canResume()) return
        states.send(State.Running)
    }

    var currentResource: RunResources? = null

    suspend fun processState() = coroutineScope {
        for (newState in states) {
            require(stateMachine.canTransferTo(newState)) { "Unknown state transform" }
            when (currentState) {
                State.Idle -> {
                    when (newState) {
                        State.StartPending -> {
                            stateMachine.transferTo(newState)
                            val currentRun = RunResources(UUID.randomUUID())
                            currentResource = currentRun
                            launch { optimizerService.processResources(currentRun, states) }
                            eventBus.post(StartRequestedEvent(currentResource!!.runID))
                        }
                        State.Idle, State.Running, State.PausePending, State.Paused, State.StopPending, State.ForceStopPending -> TODO()

                    }
                }
                State.StartPending -> {
                    when (newState) {
                        State.Running -> {
                            stateMachine.transferTo(newState)
                            eventBus.post(RunStartedEvent(currentResource!!.runID))
                        }
                        State.Idle -> {
                            onIdle(newState, currentResource!!.runID)
                        }
                        State.Idle, State.StartPending, State.PausePending, State.Paused, State.StopPending, State.ForceStopPending -> TODO()
                    }
                }
                State.Running -> {
                    when (newState) {
                        State.PausePending -> {
                            stateMachine.transferTo(newState)
                            eventBus.post(PausedRequestedEvent(currentResource!!.runID))
                        }
                        State.StopPending -> onStopPending(newState, currentResource!!.runID)
                        State.Idle, State.StartPending, State.Running, State.Paused, State.ForceStopPending -> TODO()
                    }
                }
                State.PausePending -> {
                    when (newState) {
                        State.Paused -> {
                            stateMachine.transferTo(newState)
                            eventBus.post(PausedEvent(currentResource!!.runID))
                        }
                        State.StopPending -> {
                            currentResource!!.resumes.send(Unit)
                            onStopPending(newState, currentResource!!.runID)
                        }
                        State.ForceStopPending -> {
                            stateMachine.transferTo(newState)
                            eventBus.post(ForceStopRequestedEvent(currentResource!!.runID))
                            currentResource!!.forceStops.send(Unit)
                        }
                        State.Idle, State.StartPending, State.Running, State.PausePending -> TODO()
                    }
                }
                State.Paused -> {
                    when (newState) {
                        State.Running -> {
                            stateMachine.transferTo(newState)
                            eventBus.post(RunResumedEvent(currentResource!!.runID))
                            currentResource!!.resumes.send(Unit)
                        }
                        State.StopPending -> {
                            currentResource!!.resumes.send(Unit)
                            onStopPending(newState, currentResource!!.runID)
                        }
                        State.Idle, State.StartPending, State.PausePending, State.Paused, State.ForceStopPending -> TODO()
                    }
                }
                State.StopPending -> {
                    when (newState) {
                        State.Idle -> onIdle(newState, currentResource!!.runID)
                        State.ForceStopPending -> {
                            stateMachine.transferTo(newState)
                            eventBus.post(ForceStopRequestedEvent(currentResource!!.runID))
                            currentResource!!.forceStops.send(Unit)
                        }
                        State.StartPending, State.Running, State.PausePending, State.Paused, State.StopPending -> TODO()
                    }
                }
                State.ForceStopPending -> {
                    when (newState) {
                        State.Idle -> {
                            onIdle(newState, currentResource!!.runID)
                            //TODO force stop clean up
                            //idea 1: Close channel, abandon the plugin.
                            //idea 2: Notify plugin Forcestop
                        }
                        State.StartPending, State.Running, State.PausePending, State.Paused, State.StopPending, State.ForceStopPending -> TODO()
                    }
                }
            }
        }
    }

    private fun onStopPending(newState: State, runID: UUID) {
        stateMachine.transferTo(newState)
        eventBus.post(StopRequestedEvent(runID))
    }

    private fun onIdle(newState: State, runID: UUID) {
        stateMachine.transferTo(newState)
        eventBus.post(RunStoppedEvent(runID))
        currentResource = null
    }

}