package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.PausedRequestedEvent
import com.empowerops.volition.ref_oasis.model.RunResources
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.CompletableDeferred

interface IPauseAction {
    fun canPause(): Boolean
    fun pause()
}

class OptimizerPauseAction(
        private val eventBus: EventBus,
        private val sharedResource: RunResources
) : IPauseAction {
    override fun canPause(): Boolean {
        return sharedResource.stateMachine.canTransferTo(State.PausePending)
    }

    override fun pause() {
        if (!canPause()) return
        sharedResource.apply {
            stateMachine.transferTo(State.PausePending)
            resumeSignal = CompletableDeferred()
            eventBus.post(PausedRequestedEvent(runID))
        }
    }
}