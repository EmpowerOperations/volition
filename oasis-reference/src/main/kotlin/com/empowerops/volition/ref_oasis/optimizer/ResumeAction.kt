package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.RunResources

interface IResumeAction {
    fun canResume(): Boolean
    suspend fun resume()
}

class ResumeAction(
        private val runResources: RunResources
) : IResumeAction {
    override fun canResume(): Boolean {
        return true
    }

    override suspend fun resume() {
        if (!canResume()) return
        runResources.states.send(State.Running)
    }
}