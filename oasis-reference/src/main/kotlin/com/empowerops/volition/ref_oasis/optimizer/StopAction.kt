package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.Helpers
import com.empowerops.volition.ref_oasis.model.RunResources
import com.empowerops.volition.ref_oasis.model.RunStoppedEvent
import com.empowerops.volition.ref_oasis.model.StopRequestedEvent
import com.empowerops.volition.ref_oasis.plugin.PluginService
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import java.util.*

interface IStopAction {
    fun canStop(): Boolean
    suspend fun stop()
}

class StopAction(
        private val runResources: RunResources
) : IStopAction {
    override fun canStop(): Boolean {
        return true
    }

    override suspend fun stop()  {
        if (!canStop()) return
        runResources.states.send(State.StopPending)
    }
}