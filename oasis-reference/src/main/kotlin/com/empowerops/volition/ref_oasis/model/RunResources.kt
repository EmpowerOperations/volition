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
    val forceStops : Channel<Unit> = Channel()
}

