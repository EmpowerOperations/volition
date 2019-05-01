package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.PausedRequestedEvent
import com.empowerops.volition.ref_oasis.model.RunResources
import com.google.common.eventbus.EventBus
import com.nhaarman.mockitokotlin2.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class PauseActionFixture {
    private fun create(): Triple<EventBus, RunResources, OptimizerPauseAction>{
        val eventBus = mock<EventBus>()
        val runResources = RunResources()
        val optimizerPauseActor = OptimizerPauseAction(eventBus, runResources)
        return Triple(eventBus, runResources, optimizerPauseActor)
    }

    @Test
    fun `when is running check can pause should return true`() {
        //setup
        val (_, runResources, optimizerPauseActor) = create()
        runResources.stateMachine.currentState = State.Running
        //act
        val canPause = optimizerPauseActor.canPause()

        //verify
        assertThat(canPause).isTrue()
    }

    @Test
    fun `when is running check can pause should return false`() {
        //setup
        val (_, runResources, optimizerPauseActor) = create()
        runResources.stateMachine.currentState = State.Idle

        //act
        val canPause = optimizerPauseActor.canPause()

        //verify
        assertThat(canPause).isFalse()
    }

    @Test
    fun `when try pause in sate that can not pause should do nothing`() {
        //setup
        val (eventBus, runResources, optimizerPauseActor) = create()
        runResources.stateMachine.currentState = State.Running

        //act
        optimizerPauseActor.pause()

        with(runResources) {
            assertThat(stateMachine.currentState).isEqualTo(State.PausePending)
            assertThat(resumeSignal).isNotNull
        }

        verify(eventBus, times(1)).post(check{it is PausedRequestedEvent })
    }

    @Test
    fun `when try pause in sate that can pause should do pause`() {
        //setup
        val (eventBus, runResources, optimizerPauseActor) = create()
        runResources.stateMachine.currentState = State.Idle

        //act
        optimizerPauseActor.pause()

        with(runResources) {
            assertThat(stateMachine.currentState).isEqualTo(State.Idle)
            assertThat(resumeSignal).isNull()
        }

        verify(eventBus, never()).post(any())
    }
}
