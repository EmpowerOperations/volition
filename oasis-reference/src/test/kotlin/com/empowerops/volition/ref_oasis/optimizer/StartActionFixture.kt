//package com.empowerops.volition.ref_oasis.optimizer
//
//import com.empowerops.volition.ref_oasis.model.Helpers.Companion.NullUUID
//import com.empowerops.volition.ref_oasis.model.Issue
//import com.empowerops.volition.ref_oasis.model.IssueFinder
//import com.empowerops.volition.ref_oasis.model.RunResources
//import com.empowerops.volition.ref_oasis.model.StartRequestedEvent
//import com.empowerops.volition.ref_oasis.plugin.PluginService
//import com.google.common.eventbus.EventBus
//import com.nhaarman.mockitokotlin2.*
//import org.assertj.core.api.Assertions.assertThat
//import org.junit.jupiter.api.Test
//
//internal class StartActionFixture{
//
//    @Test
//    fun `when check can start when not ready should return false`(){
//        //setup
//        val evaluationEngine = mock<IEvaluationEngine>()
//        val pluginService = mock<PluginService>()
//        val issueFinder = mock<IssueFinder> {
//            on{findIssues()} doReturn emptyList()
//        }
//        val sharedResource = RunResources().apply{
//            stateMachine.currentState = State.Running
//        }
//
//        val actor = OptimizationStartAction(mock(), sharedResource, evaluationEngine, pluginService, issueFinder)
//
//        //act
//        val canStart = actor.canStart()
//
//        //assert
//        assertThat(canStart).isFalse()
//    }
//
//    @Test
//    fun `when check can start and there are issues should return false`(){
//        //setup
//        val evaluationEngine = mock<IEvaluationEngine>()
//        val pluginService = mock<PluginService>()
//        val issueFinder = mock<IssueFinder> {
//            on{findIssues()} doReturn listOf(Issue("issue1"))
//        }
//        val sharedResource = RunResources().apply{
//            stateMachine.currentState = State.Idle
//        }
//
//        val actor = OptimizationStartAction(mock(), sharedResource, evaluationEngine, pluginService, issueFinder)
//
//
//        //act
//        val canStart = actor.canStart()
//
//        //verify
//        assertThat(canStart).isFalse()
//
//    }
//
//    @Test
//    fun `when try start and it is ready to start should do start actions`(){
//        //setup
//        val evaluationEngine = mock<IEvaluationEngine>()
//        val pluginService = mock<PluginService>()
//        val issueFinder = mock<IssueFinder> {
//            on{findIssues()} doReturn emptyList()
//        }
//        val sharedResource = RunResources().apply{
//            stateMachine.currentState = State.Idle
//        }
//        val eventBus = mock<EventBus>()
//        val actor = OptimizationStartAction(
//                eventBus, sharedResource, evaluationEngine, pluginService, issueFinder
//        )
//
//        //act
//        actor.start()
//
//        //assert
//        with(sharedResource){
//            assertThat(stateMachine.currentState).isEqualTo(State.StartPending)
//            assertThat(runID).isNotEqualTo(NullUUID)
//        }
//        verify(pluginService, times(1)).notifyStart(eq(sharedResource.runID))
//        verify(eventBus, times(1)).post(check{it is StartRequestedEvent })
//    }
//}