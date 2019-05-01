package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.Event
import com.empowerops.volition.ref_oasis.model.Helpers.Companion.NullUUID
import com.empowerops.volition.ref_oasis.model.RunResources
import com.empowerops.volition.ref_oasis.model.RunStoppedEvent
import com.empowerops.volition.ref_oasis.model.StopRequestedEvent
import com.empowerops.volition.ref_oasis.plugin.PluginService
import com.google.common.eventbus.EventBus
import com.nhaarman.mockitokotlin2.*
import kotlinx.coroutines.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.*

internal class StopActionFixture{
    @Test
    fun `when try stop and able to stop should do stop`() = runBlocking<Unit>{
        val currentRunID = UUID.randomUUID()
        val runLoopFinishedSignal = CompletableDeferred<Unit>()
        val sharedResource = RunResources().apply{
            runID = currentRunID
            stateMachine.currentState = State.Running
            runLoopFinished = runLoopFinishedSignal
            stopPending = CompletableDeferred()
        }
        val pluginService = mock<PluginService>()
        val eventBus = mock<EventBus>()
        val actor = StopAction(eventBus, sharedResource, pluginService)

        //act
        async {
            delay(50)
            runLoopFinishedSignal.complete(Unit)
        }
        actor.stop().join()

        with(sharedResource){
            assertThat(runID).isEqualTo(NullUUID)
            assertThat(stateMachine.currentState).isEqualTo(State.Idle)
        }
        verify(pluginService, times(1)).notifyStop(currentRunID)


        argumentCaptor<Event>().apply {
            verify(eventBus, times(2)).post(capture())
            assertThat(firstValue is StopRequestedEvent).isTrue()
            assertThat(secondValue is RunStoppedEvent).isTrue()
            assertThat((secondValue as RunStoppedEvent).id).isEqualTo(currentRunID)
        }

    }
}