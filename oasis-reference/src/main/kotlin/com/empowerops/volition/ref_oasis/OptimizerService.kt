package com.empowerops.volition.ref_oasis

import com.empowerops.volition.ref_oasis.State.*
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.funktionale.either.Either
import java.util.*

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

    var forceStopSignals = emptyList<ForceStopSignal>()

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
        forceStopAll()
        return stopResult
    }

    fun forceStopAll(){
        forceStopSignals.forEach{ it.completableDeferred.complete(Unit) }
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
            forceStopSignals.singleOrNull { it.name == proxy.name }?.let {
                pluginEndpoint.cancelCurrentEvaluationAsync(proxy, it)
            } ?: TODO("force stop signal is missing, make sure proxy are being evaluated")
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
//        var issues = modelService.findIssues()
//        if (!state.canTransferTo(StartPending)) {
//            issues += Issue("Optimization is not ready to start: current state ${state.currentState}")
//        }
//        if (issues.isEmpty()) {
//            currentRunID = UUID.randomUUID()
//            return Either.right(currentRunID!!)
//        } else {
//            return Either.left(issues)
//        }
        TODO()
    }

    suspend fun startOptimization() {
        require(currentRunID != null && modelService.findIssues().isEmpty() && state.canTransferTo(StartPending))
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
        val forceStopSignal = ForceStopSignal(proxy.name)

        currentlyEvaluatedProxy = proxy
        forceStopSignals += forceStopSignal
        val simResult = pluginEndpoint.evaluateAsync(proxy, inputVector, forceStopSignal).await()
        currentlyEvaluatedProxy = null
        forceStopSignals -= forceStopSignal

        modelService.addNewResult(runID, simResult)
        eventBus.post(StatusUpdateEvent("Evaluation finished."))
        if (simResult is EvaluationResult.TimeOut) {
            eventBus.post(StatusUpdateEvent("Timed out, Canceling..."))
            pluginEndpoint.cancelCurrentEvaluationAsync(proxy, forceStopSignal).await()
            eventBus.post(StatusUpdateEvent("Cancel finished."))
        }
    }

}
