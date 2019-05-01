package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.Helpers
import com.empowerops.volition.ref_oasis.model.RunResources
import com.empowerops.volition.ref_oasis.model.RunStoppedEvent
import com.empowerops.volition.ref_oasis.model.StopRequestedEvent
import com.empowerops.volition.ref_oasis.plugin.PluginService
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.*

interface IStopAction {
    fun canStop(runID: UUID?): Boolean
    fun stop(): Job
}

class StopAction(
        private val eventBus: EventBus,
        private val sharedResource: RunResources,
        private val pluginService: PluginService
) : IStopAction {
    override fun canStop(runID: UUID?): Boolean = with(sharedResource){
        if (runID != runID) return false
        if (runID == Helpers.NullUUID) return false
        if (!stateMachine.canTransferTo(State.StopPending)) return false
        return true
    }

    override fun stop() = GlobalScope.launch {
        with(sharedResource) {
            if (!canStop(runID)) return@launch

            stateMachine.transferTo(State.StopPending)
            stopPending!!.complete(Unit)
            resumeSignal?.complete(Unit)
            eventBus.post(StopRequestedEvent(runID))

            runLoopFinished!!.await()

            stateMachine.transferTo(State.Idle)
            eventBus.post(RunStoppedEvent(runID))
            pluginService.notifyStop(runID)
            runID = Helpers.NullUUID
        }
    }
}