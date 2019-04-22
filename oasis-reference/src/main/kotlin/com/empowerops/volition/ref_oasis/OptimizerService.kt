package com.empowerops.volition.ref_oasis

import com.google.common.eventbus.EventBus
import kotlinx.coroutines.*
import java.util.*

interface Actor

interface StartActor : Actor {
    fun canStart(): Boolean
    fun start()
}

interface StopActor : Actor {
    fun canStop(): Boolean
    fun stop(): Job
}

interface PauseActor : Actor {
    fun canPause(): Boolean
    fun pause()
}

interface ResumeActor : Actor {
    fun canResume(): Boolean
    fun resume()
}

interface ForceStopActor : Actor {
    fun canForceStop(): Boolean
    fun forceStop()
}

class OptimizationService(
        private val startActor: StartActor,
        private val stopActor: StopActor,
        private val forceStopActor: ForceStopActor,
        private val pauseActor: PauseActor,
        private val resumeActor: ResumeActor
) : StartActor by startActor,
        StopActor by stopActor,
        ForceStopActor by forceStopActor,
        PauseActor by pauseActor,
        ResumeActor by resumeActor

class OptimizerPauseActor(
        private val eventBus: EventBus,
        private val sharedResource: RunResources
) : PauseActor {
    override fun canPause(): Boolean {
        return sharedResource.stateMachine.canTransferTo(State.PausePending)
    }

    override fun pause() {
        if (!canPause()) return
        sharedResource.apply {
            stateMachine.transferTo(State.PausePending)
            resumeSignal = CompletableDeferred()
            eventBus.post(PausedRequestedEvent())
        }
    }
}

class OptimizerResumeActor(
        private val eventBus: EventBus,
        private val sharedResource: RunResources
) : ResumeActor {
    override fun canResume(): Boolean {
        if (sharedResource.stateMachine.currentState != State.Paused) return false
        if (!sharedResource.stateMachine.canTransferTo(State.Running)) return false
        return true
    }

    override fun resume() {
        if (!canResume()) return
        sharedResource.run {
            stateMachine.transferTo(State.Running)
            resumeSignal!!.complete(Unit)
            eventBus.post(RunResumedEvent())
        }
    }
}

class OptimizerForceStopActor(
        private val eventBus: EventBus,
        private val sharedResource: RunResources
) : ForceStopActor {
    override fun canForceStop(): Boolean {
        if (sharedResource.stateMachine.currentState != State.StopPending) return false
        if (sharedResource.stateMachine.canTransferTo(State.ForceStopPending)) return false
        if (sharedResource.sessionForceStopSignals.isEmpty()) return false
        return true
    }

    override fun forceStop() {
        if (!canForceStop()) return
        sharedResource.run {
            sharedResource.sessionForceStopSignals.forEach { it.completableDeferred.complete(Unit) }
            eventBus.post(ForceStopRequestedEvent())
        }
    }
}

class OptimizationStartActor(
        private val eventBus: EventBus,
        private val sharedResource: RunResources,
        private val evaluationEngine: IEvaluationEngine,
        private val pluginService: PluginService,
        private val issueFinder: IssueFinder
) : StartActor {
    override fun canStart(): Boolean {
        if (issueFinder.findIssues().isNotEmpty()) return false
        if (sharedResource.stateMachine.currentState != State.Idle) return false
        if (!sharedResource.stateMachine.canTransferTo(State.StartPending)) return false
        return true
    }

    override fun start() {
        if (!canStart()) return
        sharedResource.run {
            runID = UUID.randomUUID()
            stateMachine.transferTo(State.StartPending)
            pluginService.notifyStart(sharedResource.runID)
            runLoopFinished = evaluationEngine.startRunLoopAsync(sharedResource.runID)
            eventBus.post(StartRequestedEvent())
        }
    }
}

class OptimizationStopActor(
        private val eventBus: EventBus,
        private val sharedResource: RunResources,
        private val pluginService: PluginService
) : StopActor {
    override fun canStop(): Boolean {
        if (!sharedResource.stateMachine.canTransferTo(State.StopPending)) return false
        return true
    }

    override fun stop() = GlobalScope.launch {
        if (!canStop()) return@launch
        sharedResource.run {
            stateMachine.transferTo(State.StopPending)
            resumeSignal?.complete(Unit)
            eventBus.post(StopRequestedEvent())

            runLoopFinished!!.await()

            stateMachine.transferTo(State.Idle)
            eventBus.post(RunStoppedEvent(runID))
            pluginService.notifyStop(runID)
        }
    }
}
