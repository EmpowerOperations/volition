package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.PausedRequestedEvent
import com.empowerops.volition.ref_oasis.model.RunResources
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.SendChannel
import java.nio.channels.Channel

interface IPauseAction {
    fun canPause(): Boolean
    suspend fun pause()
}

class OptimizerPauseAction(
        private val sharedResource: RunResources
) : IPauseAction {
    override fun canPause(): Boolean {
        TODO()
    }

    override suspend fun pause(){
        sharedResource.states.send(State.PausePending)
    }
}