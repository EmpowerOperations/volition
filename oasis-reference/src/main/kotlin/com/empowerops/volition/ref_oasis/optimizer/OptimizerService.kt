package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.*
import com.empowerops.volition.ref_oasis.optimizer.State.*
import com.empowerops.volition.ref_oasis.plugin.PluginService
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

class OptimizerService(
        private val eventBus: EventBus,
        private val modelService: ModelService,
        private val inputGenerator: InputGenerator,
        private val pluginService: PluginService,
        private val stateMachine: RunStateMachine,
        private val evaluationEngine: IEvaluationEngine
) {
    suspend fun startProcess() = GlobalScope.launch {
        var currentResource: RunResources? = null
        for (newState in stateMachine.states) {
            when (stateMachine.currentState) {
                Idle -> {
                    when (newState) {
                        StartPending -> {
                            stateMachine.transferTo(newState)
                            currentResource = stateMachine.runResources.receive()
                            pluginService.notifyStart(currentResource.runID)
                            launch { evaluationEngine.handleRun(currentResource.runs) }
                            eventBus.post(StartRequestedEvent(currentResource.runID))
                            launch { stateMachine.states.send(Running) }
                        }
                        Idle, Running, PausePending, Paused, StopPending, ForceStopPending -> TODO()

                    }
                }
                StartPending -> {
                    when (newState) {
                        Running -> onRunning(newState, currentResource)
                        Idle, StartPending, PausePending, Paused, StopPending, ForceStopPending -> TODO()
                    }
                }
                Running -> {
                    when (newState) {
                        PausePending -> onPausePending(newState, currentResource)
                        StopPending -> onStopPending(newState, currentResource)
                        Idle, StartPending, Running, Paused, ForceStopPending -> TODO()
                    }
                }
                PausePending -> {
                    when (newState) {
                        Paused -> onPaused(newState, currentResource)
                        StopPending -> {
                            currentResource!!.resumes.send(Unit)
                            onStopPending(newState, currentResource)
                        }
                        ForceStopPending -> onForceStopPending(newState, currentResource)
                        Idle, StartPending, Running, PausePending -> TODO()
                    }
                }
                Paused -> {
                    when (newState) {
                        Running -> onUnpause(newState, currentResource)
                        StopPending -> {
                            currentResource!!.resumes.send(Unit)
                            onStopPending(newState, currentResource)
                        }
                        Idle, StartPending, PausePending, Paused, ForceStopPending -> TODO()
                    }
                }
                StopPending -> {
                    when (newState) {
                        Idle -> onToIdle(newState, currentResource)
                        ForceStopPending -> onForceStopPending(newState, currentResource)
                        StartPending, Running, PausePending, Paused, StopPending -> TODO()
                    }
                }
                ForceStopPending -> {
                    when (newState) {
                        Idle -> {
                            onToIdle(newState, currentResource)
                            //TODO force stop clean up
                            //idea 1: Close channel, abandon the plugin.
                            //idea 2: Notify plugin Forcestop
                        }

                        StartPending, Running, PausePending, Paused, StopPending, ForceStopPending -> TODO()
                    }
                }
            }
        }
    }

    private suspend fun CoroutineScope.onRunning(newState: State, runResources: RunResources?) {
        require(runResources != null)
        stateMachine.transferTo(newState)
        runT(runResources)
        eventBus.post(RunStartedEvent(runResources.runID))
    }

    private fun onPausePending(newState: State, runResources: RunResources?) {
        require(runResources != null)
        stateMachine.transferTo(newState)
        eventBus.post(PausedRequestedEvent(runResources.runID))
    }

    private fun onPaused(newState: State, runResources: RunResources?) {
        require(runResources != null)
        stateMachine.transferTo(newState)
        eventBus.post(PausedEvent(runResources.runID))
    }

    private fun onStopPending(newState: State, runResources: RunResources?) {
        require(runResources != null)
        stateMachine.transferTo(newState)
        eventBus.post(StopRequestedEvent(runResources.runID))
    }

    private suspend fun onUnpause(newState: State, runResources: RunResources?) {
        require(runResources != null)
        stateMachine.transferTo(newState)
        runResources.resumes.send(Unit)
        eventBus.post(RunResumedEvent(runResources.runID))
    }

    private suspend fun onForceStopPending(newState: State, runResources: RunResources?) {
        require(runResources != null)
        eventBus.post(ForceStopRequestedEvent(runResources.runID))
        stateMachine.transferTo(newState)
        runResources.forceStops.send(Unit)
    }

    private fun onToIdle(newState: State, runResources: RunResources?) {
        require(runResources != null)
        stateMachine.transferTo(newState)
        eventBus.post(RunStoppedEvent(runResources.runID))
        pluginService.notifyStop(runResources.runID)
        //dispose
    }

    suspend fun CoroutineScope.runT(runResources: RunResources?) = launch {
        require(runResources != null)
        val currentRun = Run(runResources.runID, 1)
        val forceStopSignals = mutableListOf<ForceStopSignal>()
        runResources.runs.send(currentRun)
        while (stateMachine.currentState == Running) {
            var iterationCount = 1
            val currentIteration = Iteration(iterationCount)
            currentRun.iterations.send(currentIteration)
            for ((index, proxy) in modelService.proxies.withIndex()) {
                val inputVector = inputGenerator.generateInputs(proxy.inputs)
                val forceStopSignal = ForceStopSignal(proxy.name)
                forceStopSignals += forceStopSignal
                currentIteration.evaluations.send(
                        EvaluationRequest(
                                proxy,
                                modelService.simulations.getValue(proxy.name),
                                inputVector,
                                forceStopSignal
                        )
                )
                eventBus.post(BasicStatusUpdateEvent("Evaluating: ${proxy.name} (${index+1}/${modelService.proxies.size})"))
                select<Unit> {
                    currentIteration.evaluationResult.onReceive { result ->
                        modelService.addNewResult(runResources.runID, result)
                    }
                    runResources.forceStops.onReceive {
                        forceStopSignals.forEach {
                            it.forceStopped.complete(Unit)
                            val result = currentIteration.evaluationResult.receive()
                            modelService.addNewResult(runResources.runID, result)
                        }
                    }
                }
                eventBus.post(BasicStatusUpdateEvent("Evaluation finished."))
                if (stateMachine.currentState == PausePending) {
                    stateMachine.states.send(Paused)
                    select<Unit> {
                        runResources.resumes.onReceive {}
                        runResources.forceStops.onReceive {
                            forceStopSignals.forEach {
                                it.forceStopped.complete(Unit)
                                val result = currentIteration.evaluationResult.receive()
                                modelService.addNewResult(runResources.runID, result)
                            }
                        }
                    }
                    if (stateMachine.currentState == StopPending || stateMachine.currentState == ForceStopPending) {
                        stateMachine.states.send(Idle)
                    }
                } else if (stateMachine.currentState == ForceStopPending) {
                    stateMachine.states.send(Idle)
                }
            }
            currentIteration.evaluations.close()
            forceStopSignals.clear()
            select<Unit> {
                currentRun.iterationResults.onReceive {
                    //NOOP, we are not grouping iteration in the model service yet
                }
                runResources.forceStops.onReceive {
                    forceStopSignals.forEach {
                        it.forceStopped.complete(Unit)
                        currentRun.iterationResults.receive()
                    }
                }
            }
            if (stateMachine.currentState == StopPending || stateMachine.currentState == ForceStopPending) {
                stateMachine.states.send(Idle)
            }
            iterationCount += 1
        }
        currentRun.iterations.close()
        runResources.runs.close()
    }


}
