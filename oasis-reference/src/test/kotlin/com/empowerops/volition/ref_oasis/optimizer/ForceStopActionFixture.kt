//package com.empowerops.volition.ref_oasis.optimizer
//
//import com.empowerops.volition.ref_oasis.model.ForceStopRequestedEvent
//import com.empowerops.volition.ref_oasis.model.ForceStopSignal
//import com.empowerops.volition.ref_oasis.model.RunResources
//import com.google.common.eventbus.EventBus
//import com.nhaarman.mockitokotlin2.check
//import com.nhaarman.mockitokotlin2.mock
//import com.nhaarman.mockitokotlin2.times
//import com.nhaarman.mockitokotlin2.verify
//import org.assertj.core.api.Assertions.assertThat
//import org.junit.jupiter.api.Test
//
//internal class ForceStopActionFixture{
//    @Test fun `when check can force stop and not ready should return false`(){
//        val eventBus = mock<EventBus>()
//        val sharedResource = RunResources().apply {
//            stateMachine.stateMachine = State.Running
//        }
//        val actor = ForceStopAction(eventBus, sharedResource)
//
//        //act
//        val canForceStop = actor.canForceStop()
//
//        //assert
//        assertThat(canForceStop).isFalse()
//    }
//
//    @Test fun `when check can force stop there is nothing to force stop should return false`(){
//        val eventBus = mock<EventBus>()
//        val sharedResource = RunResources().apply {
//            stateMachine.stateMachine = State.StopPending
//        }
//        val actor = ForceStopAction(eventBus, sharedResource)
//
//        //act
//        val canForceStop = actor.canForceStop()
//
//        //assert
//        assertThat(canForceStop).isFalse()
//    }
//
//    @Test fun `when try force stop when ready should do force stop`(){
//        //setup
//        val eventBus = mock<EventBus>()
//        val sharedResource = RunResources().apply {
//            stateMachine.stateMachine = State.StopPending
//            sessionForceStopSignals = listOf(ForceStopSignal("tool1"), ForceStopSignal("tool2"))
//        }
//        val actor = ForceStopAction(eventBus, sharedResource)
//
//        //act
//        actor.forceStop()
//
//        //assert
//        with(sharedResource){
//            assertThat(stateMachine.stateMachine).isEqualTo(State.ForceStopPending)
//            assertThat(sessionForceStopSignals.all { it.completableDeferred.isCompleted }).isTrue()
//        }
//        verify(eventBus, times(1)).post(check{it is ForceStopRequestedEvent })
//    }
//}