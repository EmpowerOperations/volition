package com.empowerops.volition.ref_oasis.model

import com.empowerops.volition.ref_oasis.optimizer.Run
import kotlinx.coroutines.channels.Channel
import java.util.*

/**
 * Pre run meta data used for run-internal shared object
 *
 * Notice: Make sure everything is immutable
 */
data class RunResources(val runID: UUID) {
    val runs: Channel<Run> = Channel()
    val resumes: Channel<Unit> = Channel(Channel.CONFLATED)
    val forceStopSignals : Channel<ForceStopSignal> = Channel(Channel.UNLIMITED)
    // This is a bit lacking on granularity which on we are force stopping, for each evaluation, we are putting these signal
    // there are multiple place need to receive this signal (but actually only one since we are running sequential) so the fan out nature of channel doesn't work
    // There is a difference between the Channel<ForceStopSig> which is more for shared resource
    // What could do better, I heard about broad cast channel then we only need one of those
}

