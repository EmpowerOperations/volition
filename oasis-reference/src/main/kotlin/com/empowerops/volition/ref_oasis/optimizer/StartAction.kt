package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.IssueFinder
import com.empowerops.volition.ref_oasis.model.RunResources
import com.empowerops.volition.ref_oasis.model.StartRequestedEvent
import com.empowerops.volition.ref_oasis.plugin.PluginService
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.CompletableDeferred
import java.util.*

interface IStartAction {
    fun canStart(): Boolean
    fun start()
}

class OptimizationStartAction(
        private val eventBus: EventBus,
        private val sharedResource: RunResources,
        private val evaluationEngine: IEvaluationEngine,
        private val pluginService: PluginService,
        private val issueFinder: IssueFinder
) : IStartAction {
    override fun canStart(): Boolean = with(sharedResource){
        if (issueFinder.findIssues().isNotEmpty()) return false
        if (stateMachine.currentState != State.Idle) return false
        if (!stateMachine.canTransferTo(State.StartPending)) return false
        return true
    }

    override fun start() {
        if (!canStart()) return
        with(sharedResource) {
            runID = UUID.randomUUID()
            runLoopFinished = CompletableDeferred()
            stopPending = CompletableDeferred()
            stateMachine.transferTo(State.StartPending)
            pluginService.notifyStart(runID)
            evaluationEngine.startRunLoopAsync(runID)
            eventBus.post(StartRequestedEvent(runID))
        }
    }
}