package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.ForceStopRequestedEvent
import com.empowerops.volition.ref_oasis.model.RunResources
import com.google.common.eventbus.EventBus

interface IForceStopAction {
    fun canForceStop(): Boolean
    suspend fun forceStop()
}

class ForceStopAction(
        private val sharedResource: RunResources
) : IForceStopAction {
    override fun canForceStop(): Boolean {
        return true
    }
//    override fun canForceStop(): Boolean = with(sharedResource){
//        if (stateMachine.currentState != State.StopPending) return false
//        if (! stateMachine.canTransferTo(State.ForceStopPending)) return false
//        if (sessionForceStopSignals.isEmpty()) return false
//        return true
//    }

    override suspend fun forceStop() {
        if (!canForceStop()) return
        sharedResource.states.send(State.ForceStopPending)
    }
}