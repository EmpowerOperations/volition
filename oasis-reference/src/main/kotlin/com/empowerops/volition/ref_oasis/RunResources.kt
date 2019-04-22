package com.empowerops.volition.ref_oasis

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.util.*

class RunResources {
    val stateMachine: OptimizerStateMachine = OptimizerStateMachine()
    var runID: UUID = UUID(0, 0)
    var resumeSignal: CompletableDeferred<Unit>? = null
    var runLoopFinished: Deferred<Unit>? = null
    var currentlyEvaluatedProxy: Proxy? = null
    var sessionForceStopSignals: List<ForceStopSignal> = emptyList()
}

