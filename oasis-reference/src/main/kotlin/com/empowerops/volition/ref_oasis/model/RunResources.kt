package com.empowerops.volition.ref_oasis.model

import com.empowerops.volition.ref_oasis.model.Helpers.Companion.NullUUID
import com.empowerops.volition.ref_oasis.optimizer.Run
import com.empowerops.volition.ref_oasis.optimizer.RunStateMachine
import com.empowerops.volition.ref_oasis.optimizer.State
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import java.util.*

class RunResources {
    var runID: UUID = NullUUID
    val states: Channel<State> = Channel()
    val runs: Channel<Run> = Channel()
    val resumes: Channel<Unit> = Channel(Channel.CONFLATED)
}

