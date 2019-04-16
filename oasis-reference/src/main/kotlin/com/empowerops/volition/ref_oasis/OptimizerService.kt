package com.empowerops.volition.ref_oasis

import com.empowerops.volition.ref_oasis.State.*
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.funktionale.either.Either
import java.util.*
import kotlin.collections.HashMap

class OptimizerService(
        private val optimizer: RandomNumberOptimizer,
        private val modelService: DataModelService,
        private val eventBus: EventBus,
        private val pluginEndpoint: PluginService) {
    var state = OptimizerStateMachine()
        private set
    private var currentlyEvaluatedProxy: Proxy? = null  // This is for testing action on cancel current
    private var resumed: CompletableDeferred<Unit>? = null

    private var currentRunID: UUID? = null

    fun canStop(): Boolean {
        return (currentRunID != null) && state.canTransferTo(StopPending)
    }

    fun stopOptimization() {
        require(canStop()) { "Illegal state to stop" }
        state.transferTo(StopPending)
        resumed?.complete(Unit)
        eventBus.post(StopRequestedEvent())
    }

    fun forceStop(): Boolean {
        val stopResult = state.transferTo(Idle)
        if (stopResult) eventBus.post(ForceStopRequestedEvent())
        pluginEndpoint.forceStopAll()
        return stopResult
    }

    fun pauseOptimization(): Boolean {
        val transferResult = state.transferTo(PausePending)
        if (transferResult) eventBus.post(PausedRequestedEvent())
        return transferResult
    }

    fun resumeOptimization(): Boolean {
        val transferResult = state.transferTo(Running)
        resumed!!.complete(Unit)
        if (transferResult) eventBus.post(RunResumedEvent())
        return transferResult
    }

    /**
     * This is a debugging feature
     */
    suspend fun cancelCurrent() {
        val proxy = currentlyEvaluatedProxy
        if (proxy != null) {
            pluginEndpoint.cancelCurrentEvaluation(proxy)
        }
    }

    /**
     * This is a debugging feature
     */
    suspend fun cancelStop() {
        stopOptimization()
        cancelCurrent()
    }

    fun requestStart(): Either<List<String>, UUID> {
        var issues = modelService.findIssue()
        if (!state.canTransferTo(StartPending)) {
            issues += "Optimization is not ready to start: current state ${state.currentState}"
        }
        if (issues.isEmpty()) {
            currentRunID = UUID.randomUUID()
            return Either.right(currentRunID!!)
        } else {
            return Either.left(issues)
        }
    }

    suspend fun startOptimization() {
        require(currentRunID != null && modelService.findIssue().isEmpty() && state.canTransferTo(StartPending))
        state.transferTo(StartPending)
        eventBus.post(StartRequestedEvent())

        try {
            state.transferTo(Running)
            eventBus.post(RunStartedEvent(currentRunID!!))
            pluginEndpoint.notifyStart(currentRunID!!)
            while (state.currentState == Running) {
                var pluginNumber = 1
                for (proxy in modelService.proxies) {
                    eventBus.post(StatusUpdateEvent("Evaluating: ${proxy.name} ($pluginNumber/${modelService.proxies.size})"))
                    val inputVector = optimizer.generateInputs(proxy.inputs)
                    evaluate(inputVector, proxy, currentRunID!!)
                    pluginNumber++
                }
                if (state.currentState == PausePending) {
                    state.transferTo(Paused)
                    eventBus.post(PausedEvent(currentRunID!!))
                    resumed = CompletableDeferred()
                    select<Unit> {
                        resumed!!.onAwait { Unit }
                    }
                }
            }
        } finally {
            state.transferTo(Idle)
            eventBus.post(RunStoppedEvent(currentRunID!!))
            pluginEndpoint.notifyStop(currentRunID!!)
        }
    }

    private suspend fun evaluate(inputVector: Map<String, Double>, proxy: Proxy, runID: UUID) {
        currentlyEvaluatedProxy = proxy
        val simResult = pluginEndpoint.evaluate(proxy, inputVector)
        currentlyEvaluatedProxy = null
        modelService.addNewResult(runID, simResult)
        eventBus.post(StatusUpdateEvent("Evaluation finished."))
        if (simResult is EvaluationResult.TimeOut) {
            eventBus.post(StatusUpdateEvent("Timed out, Canceling..."))
            pluginEndpoint.cancelCurrentEvaluation(proxy)
            eventBus.post(StatusUpdateEvent("Cancel finished."))
        }
    }

}