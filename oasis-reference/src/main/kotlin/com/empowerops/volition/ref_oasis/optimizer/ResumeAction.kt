package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.RunResources
import com.empowerops.volition.ref_oasis.model.RunResumedEvent
import com.google.common.eventbus.EventBus

interface IResumeAction {
    fun canResume(): Boolean
    fun resume()
}

class ResumeAction(
        private val eventBus: EventBus,
        private val sharedResource: RunResources
) : IResumeAction {
    override fun canResume(): Boolean = with(sharedResource) {
        if (stateMachine.currentState != State.Paused) return false
        if (!stateMachine.canTransferTo(State.Running)) return false
        if (resumeSignal == null) return false
        return true
    }

    override fun resume() {
        if (!canResume()) return
        with(sharedResource) {
            stateMachine.transferTo(State.Running)
            resumeSignal!!.complete(Unit)
            eventBus.post(RunResumedEvent(runID))
        }
    }
}