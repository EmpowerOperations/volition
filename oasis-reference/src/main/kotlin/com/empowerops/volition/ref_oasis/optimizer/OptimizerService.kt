package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.*
import com.empowerops.volition.ref_oasis.optimizer.State.*
import com.empowerops.volition.ref_oasis.plugin.PluginService
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

class OptimizerService(
        private val eventBus: EventBus,
        private val modelService: ModelService,
        private val inputGenerator: InputGenerator,
        private val pluginService: PluginService,
        private val evaluationEngine: IEvaluationEngine,
        private val runStateMachine: RunStateMachine
) {

    suspend fun processResources(runResources: RunResources, states: Channel<State>) = coroutineScope {
        pluginService.notifyStart(runResources.runID)
        launch { evaluationEngine.processRuns(runResources.runs) }
        states.send(Running)
        runLoop(runResources, states)
        pluginService.notifyStop(runResources.runID)
    }

    private suspend fun runLoop(runResources: RunResources, states: Channel<State>) {
        val currentRun = Run(runResources.runID)
        val forceStopSignals = mutableListOf<ForceStopSignal>()
        runResources.runs.send(currentRun)
        while (runStateMachine.currentState == Running) {
            var iterationCount = 1
            val currentIteration = Iteration(iterationCount)
            currentRun.iterations.send(currentIteration)
            for (proxy in modelService.proxies) {
                val inputVector = inputGenerator.generateInputs(proxy.inputs)
                val forceStopSignal = ForceStopSignal(proxy.name)
                forceStopSignals += forceStopSignal
                currentIteration.evaluations.send(
                        EvaluationRequest(
                                inputVector,
                                proxy,
                                modelService.simulations.getValue(proxy.name),
                                forceStopSignal
                        )
                )
                select<Unit>{
                    currentIteration.evaluationResults.onReceive{
                        eventBus.post(BasicStatusUpdateEvent("Evaluation finished."))
                    }
                    runResources.forceStops.onReceive{
                        forceStopSignals.forEach {
                            it.completableDeferred.complete(Unit)
                            currentIteration.evaluationResults.receive()
                            eventBus.post(BasicStatusUpdateEvent("Evaluation finished."))
                        }
                    }
                }
                if (runStateMachine.currentState == PausePending) {
                    states.send(Paused)
                    select<Unit> {
                        runResources.resumes.onReceive{}
                        runResources.forceStops.onReceive{
                            forceStopSignals.forEach {
                                it.completableDeferred.complete(Unit)
                                currentIteration.evaluationResults.receive()
                                eventBus.post(BasicStatusUpdateEvent("Evaluation finished."))
                            }
                        }
                    }
                    if (runStateMachine.currentState == StopPending || runStateMachine.currentState == ForceStopPending) {
                        break
                    }
                }
                else if (runStateMachine.currentState == ForceStopPending){
                    break
                }
            }
            currentIteration.evaluations.close()
            forceStopSignals.clear()
            select<Unit>{
                currentRun.iterationResults.onReceive{}
                runResources.forceStops.onReceive{
                    forceStopSignals.forEach {
                        it.completableDeferred.complete(Unit)
                        currentRun.iterationResults.receive()
                    }
                }
            }
            if (runStateMachine.currentState == StopPending || runStateMachine.currentState == ForceStopPending) {
                states.send(Idle)
            }
            iterationCount += 1
        }
        currentRun.iterations.close()
        runResources.runs.close()
    }






}
