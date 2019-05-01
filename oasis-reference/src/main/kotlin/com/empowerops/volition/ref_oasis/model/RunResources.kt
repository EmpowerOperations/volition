package com.empowerops.volition.ref_oasis.model

import com.empowerops.volition.ref_oasis.model.Helpers.Companion.NullUUID
import com.empowerops.volition.ref_oasis.optimizer.RunStateMachine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import java.util.*

class RunResources {
    val stateMachine: RunStateMachine = RunStateMachine()
    var runID: UUID = NullUUID
    var resumeSignal: CompletableDeferred<Unit>? = null
    var stopPending: CompletableDeferred<Unit>? = null
    var runLoopFinished: CompletableDeferred<Unit>? = null
    var currentlyEvaluatedProxy: Proxy? = null
    var sessionForceStopSignals: List<ForceStopSignal> = emptyList()
    val iterationFinished : Channel<Unit> = Channel(Channel.CONFLATED)
}

