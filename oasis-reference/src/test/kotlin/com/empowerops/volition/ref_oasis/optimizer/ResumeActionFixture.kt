//package com.empowerops.volition.ref_oasis.optimizer
//
//import com.empowerops.volition.ref_oasis.model.RunResources
//import com.empowerops.volition.ref_oasis.model.RunResumedEvent
//import com.google.common.eventbus.EventBus
//import com.nhaarman.mockitokotlin2.*
//import kotlinx.coroutines.CompletableDeferred
//import org.assertj.core.api.Assertions.assertThat
//import org.junit.jupiter.api.Test
//
//internal class ResumeActionFixture {
//
//    @Test
//    fun `when able to resume can resume should return false`() {
//        //setup
//        val sharedResource = RunResources()
//        val eventBus = mock<EventBus>()
//        val optimizerPauseActor = ResumeAction(eventBus, sharedResource)
//        sharedResource.stateMachine.stateMachine = State.Paused
//        sharedResource.resumeSignal = CompletableDeferred(Unit)
//
//        //act
//        val canResume = optimizerPauseActor.canResume()
//
//        //assert
//        assertThat(canResume).isTrue()
//    }
//
//    @Test
//    fun `when not in the resume state can resume should return false`() {
//        //setup
//        val sharedResource = RunResources()
//        val eventBus = mock<EventBus>()
//        val optimizerPauseActor = ResumeAction(eventBus, sharedResource)
//        sharedResource.stateMachine.stateMachine = State.PausePending
//
//        //act
//        val canResume = optimizerPauseActor.canResume()
//
//        //assert
//        assertThat(canResume).isFalse()
//
//    }
//
//    @Test
//    fun `when try resume and able to resume should do resume`() {
//        //setup
//        val sharedResource = RunResources()
//        val eventBus = mock<EventBus>()
//        val optimizerPauseActor = ResumeAction(eventBus, sharedResource)
//        sharedResource.stateMachine.stateMachine = State.Paused
//        sharedResource.resumeSignal = CompletableDeferred(Unit)
//
//        //act
//        optimizerPauseActor.resume()
//
//        //assert
//        with(sharedResource){
//            assertThat(resumeSignal!!.isCompleted).isTrue()
//            assertThat(stateMachine.stateMachine).isEqualTo(State.Running)
//        }
//        verify(eventBus, times(1)).post(check{it is RunResumedEvent })
//    }
//
//    @Test
//    fun `when try resume and not able to resume should do nothing`() {
//        //setup
//        val sharedResource = RunResources()
//        val eventBus = mock<EventBus>()
//        val optimizerPauseActor = ResumeAction(eventBus, sharedResource)
//        sharedResource.stateMachine.stateMachine = State.Idle
//
//        //act
//        optimizerPauseActor.resume()
//
//        //assert
//        with(sharedResource){
//            assertThat(resumeSignal).isNull()
//            assertThat(stateMachine.stateMachine).isEqualTo(State.Idle)
//        }
//        verify(eventBus, never()).post(check{it is RunResumedEvent })
//    }
//}