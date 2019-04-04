package com.empowerops.volition.ref_oasis

import com.empowerops.volition.ref_oasis.State.*
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.funktionale.either.Either
import java.util.*

class OptimizerService(
        private val optimizer: RandomNumberOptimizer,
        private val modelService: DataModelService,
        private val eventBus: EventBus,
        private val pluginEndpoint: PluginEndPoint) {
    private var state = OptimizerStateMachine()
    var currentlyEvaluatedProxy: Proxy? = null  // This is for testing action on cancel current

    fun stopOptimization(): Boolean {
        val stopResult = state.transferTo(StopPending)
        if(stopResult) eventBus.post(StopRequestedEvent())
        return stopResult
    }

    fun pauseOptimization(): Boolean {
        val transferResult = state.transferTo(PausePending)
        if(transferResult) eventBus.post(PausedRequestedEvent())
        return transferResult
    }

    fun resumeOptimization(): Boolean {
        val transferResult = state.transferTo(Running)
        if(transferResult) eventBus.post(RunResumedEvent())
        return transferResult
    }

    /**
     * This is a debugging feature
     */
    suspend fun cancelCurrent() {
        val proxy = currentlyEvaluatedProxy
        if (proxy != null) { pluginEndpoint.cancelCurrentEvaluation(proxy) }
    }

    /**
     * This is a debugging feature
     */
    suspend fun cancelStop() {
        stopOptimization()
        cancelCurrent()
    }

    suspend fun startOptimization() : Either<List<String>, UUID>{
        val issues = modelService.findIssue()
        if( ! issues.isEmpty()){
            return Either.left(issues)
        }
        val runID = UUID.randomUUID()
        if( ! state.transferTo(StartPending)){
            return Either.left(listOf("Optimization is not ready to start: current state ${state.currentState}"))
        }
        eventBus.post(StartRequestedEvent())
        GlobalScope.async { startRunLoop(runID) }
        return Either.right(runID)
    }

    private suspend fun startRunLoop(runID: UUID) {
        state.transferTo(Running)
        eventBus.post(RunStartedEvent(runID))
        while (state.currentState == Running) {
            var pluginNumber = 1
            for (proxy in modelService.proxies) {
                eventBus.post(StatusUpdateEvent("Evaluating: ${proxy.name} ($pluginNumber/${modelService.proxies.size})"))
                val inputVector = optimizer.generateInputs(proxy.inputs)
                evaluate(inputVector, proxy, runID)
                pluginNumber++
            }
            if (state.currentState == PausePending) {
                state.transferTo(Paused)
                eventBus.post(PausedEvent(runID))
                while (state.currentState == Paused && state.currentState != StopPending) {
                    delay(500)
                }
            }
        }
        state.transferTo(Idle)
        eventBus.post(RunStoppedEvent(runID))
    }

    private suspend fun evaluate(inputVector: Map<String, Double>, proxy: Proxy, runID: UUID) {
        currentlyEvaluatedProxy = proxy
        val simResult = pluginEndpoint.evaluate(proxy, inputVector)
        currentlyEvaluatedProxy = null
        modelService.addNewResult(runID, makeResult(simResult, inputVector))
        eventBus.post(StatusUpdateEvent("Evaluation finished."))
        if (simResult is EvaluationResult.TimeOut) {
            eventBus.post(StatusUpdateEvent("Timed out, Canceling..."))
            pluginEndpoint.cancelCurrentEvaluation(proxy)
            eventBus.post(StatusUpdateEvent("Cancel finished."))
        }

    }

    private fun makeResult(evaluationResult: EvaluationResult, inputVector: Map<String, Double>): Result = when (evaluationResult) {
        is EvaluationResult.Success -> {
            Result(evaluationResult.name, "Success", inputs = inputVector, outputs = evaluationResult.result)
        }
        is EvaluationResult.Failed -> {
            Result(evaluationResult.name, "Failed", inputs = inputVector, message = "Evaluation Failed: \n${evaluationResult.exception}")
        }
        is EvaluationResult.TimeOut -> {
            Result(evaluationResult.name, "Timeout", inputs = inputVector, message = "N/A")
        }
        is EvaluationResult.Error -> {
            Result(evaluationResult.name, "Error", inputs = inputVector, message = "Error:\n${evaluationResult.exception}")
        }
    }
}
