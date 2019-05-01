package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.ForceStopRequestedEvent
import com.empowerops.volition.ref_oasis.model.RunResources
import com.google.common.eventbus.EventBus

interface IForceStopAction {
    fun canForceStop(): Boolean
    fun forceStop()
}

class ForceStopAction(
        private val eventBus: EventBus,
        private val sharedResource: RunResources
) : IForceStopAction {
    override fun canForceStop(): Boolean = with(sharedResource){
        if (stateMachine.currentState != State.StopPending) return false
        if (! stateMachine.canTransferTo(State.ForceStopPending)) return false
        if (sessionForceStopSignals.isEmpty()) return false
        return true
    }

    override fun forceStop() {
        if (!canForceStop()) return
        with(sharedResource) {
            stateMachine.transferTo(State.ForceStopPending)
            sessionForceStopSignals.forEach { it.completableDeferred.complete(Unit) }
            eventBus.post(ForceStopRequestedEvent(runID))
        }
    }
}