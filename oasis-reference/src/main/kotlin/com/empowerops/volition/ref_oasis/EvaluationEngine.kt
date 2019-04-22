package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.Logger
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import java.util.*

interface IEvaluationEngine {
    fun startRunLoopAsync(runId: UUID): Deferred<Unit>
}

class EvaluationEngine(
        private val runResources : RunResources,
        private val optimizer : RandomNumberOptimizer,
        private val modelService: DataModelService,
        private val pluginService : PluginService,
        private val eventBus: EventBus,
        private val logger: Logger
        ) : IEvaluationEngine {
    private suspend fun evaluate(inputVector: Map<String, Double>, proxy: Proxy, runID: UUID) {
        val forceStopSignal = ForceStopSignal(proxy.name)
        runResources.run {
            try {
                currentlyEvaluatedProxy = proxy
                sessionForceStopSignals += forceStopSignal
                val simResult = pluginService.evaluateAsync(proxy, inputVector, forceStopSignal).await()
                modelService.addNewResult(runID, simResult)
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
                    for (proxy in modelService.proxies) {
                        eventBus.post(StatusUpdateEvent("Evaluating: ${proxy.name} ($pluginNumber/${modelService.proxies.size})"))
                        val inputVector = optimizer.generateInputs(proxy.inputs)
                        evaluate(inputVector, proxy, currentRunID)
                        pluginNumber++
                    }
                    if (stateMachine.currentState == State.PausePending) {
                        stateMachine.transferTo(State.Paused)
                        eventBus.post(PausedEvent(currentRunID))
                        select<Unit> {
                            resumeSignal!!.onAwait { Unit }
                        }
                    }
                }
            } finally {
               logger.log("Run finished", "Optimizer")
            }
        }
    }
}