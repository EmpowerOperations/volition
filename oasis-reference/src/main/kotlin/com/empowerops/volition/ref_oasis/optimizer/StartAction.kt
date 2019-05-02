package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.IssueFinder
import com.empowerops.volition.ref_oasis.model.RunResources
import com.empowerops.volition.ref_oasis.model.StartRequestedEvent
import com.empowerops.volition.ref_oasis.plugin.PluginService
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import java.util.*

interface IStartAction {
    fun canStart(): Boolean
    suspend fun start()
}

class OptimizationStartAction(
        private val issueFinder: IssueFinder,
        private val resources: RunResources
) : IStartAction {
//    override fun canStart(): Boolean = with(sharedResource){
//        if (issueFinder.findIssues().isNotEmpty()) return false
//        if (stateMachine.currentState != State.Idle) return false
//        if (!stateMachine.canTransferTo(State.StartPending)) return false
//        return true
//    }

    override fun canStart(): Boolean = issueFinder.findIssues().isEmpty()

    override suspend fun start(){
        if (!canStart()) return
        resources.states.send(State.StartPending)
    }
}