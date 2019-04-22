package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.Logger
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.util.*
import kotlin.coroutines.coroutineContext

interface Actor

interface StartActor : Actor{
    fun canStart(): Boolean
    fun start()
}

interface StopActor : Actor{
    fun canStop(): Boolean
    fun stop(): Job
}

interface PauseActor : Actor{
    fun canPause() : Boolean
    fun pause()
}

interface ResumeActor : Actor{
    fun canResume(): Boolean
    fun resume()
}

interface ForceStopActor : Actor{
    fun canForceStop(): Boolean
    suspend fun forceStop()
}

interface OptimizerEngine {
    fun startRunLoopAsync(runId: UUID): Deferred<Unit>
}

class OptimizationService2(
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

class OptimizerPauseActor(private val sharedResource: RunResources) : PauseActor{
    override fun canPause(): Boolean {
        return sharedResource.stateMachine.canTransferTo(State.PausePending)
    }

    override fun pause() {
        if( ! canPause()) return
        sharedResource.apply {
            stateMachine.transferTo(State.PausePending)
            resumeSigngal = CompletableDeferred()
            eventBus.post(PausedRequestedEvent())
        }
    }
}

class OptimizerResumeActor(private val sharedResource: RunResources): ResumeActor{
    override fun canResume(): Boolean {
        if(sharedResource.stateMachine.currentState != State.Paused) return false
        if(! sharedResource.stateMachine.canTransferTo(State.Running)) return false
        return true
    }

    override fun resume() {
       if(! canResume()) return
        sharedResource.run {
            stateMachine.transferTo(State.Running)
            resumeSigngal!!.complete(Unit)
            eventBus.post(RunResumedEvent())
        }
    }
}


class OptimizerForceStopActor(private val sharedResource: RunResources): ForceStopActor{
    override fun canForceStop(): Boolean {
        if(sharedResource.stateMachine.currentState != State.StopPending) return false
        return true
    }

    override suspend fun forceStop() {
        sharedResource.run {
            eventBus.post(ForceStopRequestedEvent())
            sharedResource.sessionForceStopSignals.forEach { it.completableDeferred.complete(Unit) }
            //we dont need this since force stop is on top of stop, it will free up unresponsive actions
//            stopSignal!!.await()
//
//            stateMachine.transferTo(State.Idle)
//            eventBus.post(RunStoppedEvent(runID))
//            pluginService.notifyStop(runID)
//            //TODO maybe clear the run ID?
        }
    }

}

class OptimizationStartActor(private val sharedResource: RunResources, private val optimizerEngine : OptimizerEngine) : StartActor{
    override fun canStart(): Boolean {
        if(sharedResource.issueFinder.findIssues().isNotEmpty()) return false
        if( ! sharedResource.stateMachine.canTransferTo(State.StartPending)) return false
        return true
    }

    override fun start() {
        if(! canStart()) return
        sharedResource.run {
            runID = UUID.randomUUID()
            stateMachine.transferTo(State.StartPending)
            pluginService.notifyStart(sharedResource.runID)
            stopSignal = optimizerEngine.startRunLoopAsync(sharedResource.runID)
            eventBus.post(StartRequestedEvent())
        }
    }
}

class OptimizationStopActor(val sharedResource: RunResources): StopActor{
    override fun canStop(): Boolean {
        if( ! sharedResource.stateMachine.canTransferTo(State.StopPending)) return false
        return true
    }

    override fun stop() = GlobalScope.launch{
        if(! canStop()) return@launch
        sharedResource.run {
            stateMachine.transferTo(State.StopPending)
            resumeSigngal?.complete(Unit)
            eventBus.post(StopRequestedEvent())

            stopSignal!!.await()

            stateMachine.transferTo(State.Idle)
            eventBus.post(RunStoppedEvent(runID))
            pluginService.notifyStop(runID)
            //TODO maybe clear the run ID?
        }
    }

}


class OptimizerEngineImpl(private val runResources : RunResources) : OptimizerEngine{
    private suspend fun evaluate(inputVector: Map<String, Double>, proxy: Proxy, runID: UUID) {
        val forceStopSignal = ForceStopSignal(proxy.name)
        runResources.run {
            try {
                currentlyEvaluatedProxy = proxy
                sessionForceStopSignals += forceStopSignal
                val simResult = pluginService.evaluateAsync(proxy, inputVector, forceStopSignal).await()
                dataModelService.addNewResult(runID, simResult)
                eventBus.post(StatusUpdateEvent("Evaluation finished."))
                if (simResult is EvaluationResult.TimeOut) {
                    eventBus.post(StatusUpdateEvent("Timed out, Canceling..."))
                    val cancelResult = pluginService.cancelCurrentEvaluationAsync(proxy, forceStopSignal).await()

                    val cancelMessage = when (cancelResult) {
                        is CancelResult.Canceled -> {
                            "Evaluation Canceled"
                        }
                        is CancelResult.CancelFailed -> {
                            "Cancellation Failed, Cause:\n${cancelResult.exception}"
                        }
                        is CancelResult.CancelTerminated -> {
                            "Cancellation Terminated, Cause:\nForce-stopped"
                        }
                    }
                    logger.log(cancelMessage, "Optimizer")
                    eventBus.post(StatusUpdateEvent("Cancel finished. [$cancelResult]"))
                }
            }finally {
                currentlyEvaluatedProxy = null
                sessionForceStopSignals -= forceStopSignal
            }
        }
    }

    override fun startRunLoopAsync(currentRunID: UUID) = GlobalScope.async{
        runResources.run {
            try {
                stateMachine.transferTo(State.Running)
                eventBus.post(RunStartedEvent(currentRunID))
                while (stateMachine.currentState == State.Running) {
                    var pluginNumber = 1
                    for (proxy in dataModelService.proxies) {
                        eventBus.post(StatusUpdateEvent("Evaluating: ${proxy.name} ($pluginNumber/${dataModelService.proxies.size})"))
                        val inputVector = runResources.optimizer.generateInputs(proxy.inputs)
                        evaluate(inputVector, proxy, currentRunID)
                        pluginNumber++
                    }
                    if (stateMachine.currentState == State.PausePending) {
                        stateMachine.transferTo(State.Paused)
                        eventBus.post(PausedEvent(currentRunID))
                        select<Unit> {
                            resumeSigngal!!.onAwait { Unit }
                        }
                    }
                }
            } finally {
               logger.log("Run finished", "Optimizer")
            }
        }

    }

}

interface RunResources {
    val stateMachine: OptimizerStateMachine
    var runID: UUID
    var resumeSigngal: CompletableDeferred<Unit>?
    var stopSignal: Deferred<Unit>?
    val pluginService: PluginService
    val issueFinder: IssueFinder
    val eventBus: EventBus
    val dataModelService: DataModelService
    var currentlyEvaluatedProxy: Proxy?
    val optimizer: RandomNumberOptimizer
    val logger : Logger
    var sessionForceStopSignals : List<ForceStopSignal>
}

class RunResourceImpl(override val pluginService: PluginService,
                      override val dataModelService: DataModelService,
                      override val eventBus: EventBus,
                      override val optimizer: RandomNumberOptimizer,
                      override val logger: Logger
) : RunResources {
    override val stateMachine: OptimizerStateMachine = OptimizerStateMachine()
    override var runID: UUID = UUID(0, 0)
    override val issueFinder: IssueFinder = dataModelService
    override var resumeSigngal: CompletableDeferred<Unit>? = null
    override var stopSignal: Deferred<Unit>? = null
    override var currentlyEvaluatedProxy: Proxy? = null
    override var sessionForceStopSignals: List<ForceStopSignal> = emptyList()
}

data class Issue(
    val message: String
)

interface IssueFinder{
    fun findIssues() : List<Issue>
}