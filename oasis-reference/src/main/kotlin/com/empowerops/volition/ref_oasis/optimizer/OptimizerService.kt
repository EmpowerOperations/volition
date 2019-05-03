package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.*
import com.empowerops.volition.ref_oasis.model.Helpers.Companion.NullUUID
import com.empowerops.volition.ref_oasis.optimizer.State.*
import com.empowerops.volition.ref_oasis.plugin.PluginService
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.launch
import java.util.*

class OptimizerService(
        private val eventBus: EventBus,
        private val modelService: ModelService,
        private val inputGenerator: InputGenerator,
        private val pluginService: PluginService,
        private val stateMachine: RunStateMachine,
        private val evaluationEngine: IEvaluationEngine
) {
    suspend fun startProcess() = GlobalScope.launch {
        var currentResource : RunResources? = null
        for (newState in stateMachine.states) {
            when (stateMachine.currentState) {
                Idle -> {
                    when (newState) {
                        StartPending -> {
                            stateMachine.transferTo(newState)
                            currentResource = stateMachine.runResources.receive()
                            pluginService.notifyStart(currentResource.runID)
                            launch { evaluationEngine.handle(currentResource.runs) }
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
                        StopPending -> onStopPending(newState, currentResource)
                        ForceStopPending -> onForceStopPending(newState, currentResource)
                        Idle, StartPending, Running, PausePending -> TODO()
                    }
                }
                Paused -> {
                    when (newState) {
                        Running -> onUnpause(newState, currentResource)
                        StopPending -> onStopPending(newState, currentResource)
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
                        Idle -> onToIdle(newState, currentResource)
                        StartPending, Running, PausePending, Paused, StopPending, ForceStopPending -> TODO()
                    }
                }
            }

        }
    }

    private suspend fun CoroutineScope.onRunning(newState: State, runResources: RunResources?) {
        require(runResources!=null)
        stateMachine.transferTo(newState)
        runT(runResources)
        eventBus.post(RunStartedEvent(runResources.runID))
    }

    private fun onPausePending(newState: State, runResources: RunResources?) {
        require(runResources!=null)
        stateMachine.transferTo(newState)
        eventBus.post(PausedRequestedEvent(runResources.runID))
    }

    private fun onPaused(newState: State, runResources: RunResources?) {
        require(runResources!=null)
        stateMachine.transferTo(newState)
        eventBus.post(PausedEvent(runResources.runID))
    }

    private suspend fun onStopPending(newState: State, runResources: RunResources?) {
        require(runResources!=null)
        stateMachine.transferTo(newState)
        runResources.resumes.send(Unit)
        eventBus.post(StopRequestedEvent(runResources.runID))
    }

    private suspend fun onUnpause(newState: State, runResources: RunResources?) {
        require(runResources!=null)
        stateMachine.transferTo(newState)
        runResources.resumes.send(Unit)
        eventBus.post(RunResumedEvent(runResources.runID))
    }

    private suspend fun onForceStopPending(newState: State, runResources: RunResources?) {
        require(runResources!=null)
        stateMachine.transferTo(newState)
        runResources.forceStopSignals.close()// force stop issued, no more evaluation will be accepted
        for(signal in runResources.forceStopSignals){
            signal.completableDeferred.complete(Unit)
        }
        eventBus.post(ForceStopRequestedEvent(runResources.runID))
    }

    private fun onToIdle(newState: State, runResources: RunResources?) {
        require(runResources!=null)
        stateMachine.transferTo(newState)
        eventBus.post(RunStoppedEvent(runResources.runID))
        pluginService.notifyStop(runResources.runID)
        //dispose
    }

    suspend fun CoroutineScope.runT(runResources: RunResources?) = launch {
        require(runResources!=null)
        val currentRun = Run(runResources.runID)
        runResources.runs.send(currentRun)
        while (stateMachine.currentState == Running) {
            var iterationCount = 1
            val currentIteration = Iteration(iterationCount)
            currentRun.iterations.send(currentIteration)
            for (proxy in modelService.proxies) {
                val inputVector = inputGenerator.generateInputs(proxy.inputs)
                val forceStopSignal = ForceStopSignal(proxy.name)
                runResources.forceStopSignals.send(forceStopSignal)
                currentIteration.evaluations.send(
                        EvaluationRequest(
                                inputVector,
                                proxy,
                                modelService.simulations.getValue(proxy.name),
                                forceStopSignal
                        )
                )

                //we are blocking on evaluation, remove this we need a newer evaluation block, so this can consider as the implementation for sequential evaluation
                // when considering parallel, we need a list of channel/deffered to block at the end, figuring out dependency, put a worker pool limit
                currentIteration.evaluationEnds.receive()
                if (stateMachine.currentState == PausePending) {
                    stateMachine.states.send(Paused)
                    runResources.resumes.receive()
                    if (stateMachine.currentState == StopPending || stateMachine.currentState == ForceStopPending) {
                        stateMachine.states.send(Idle)
                    }
                }
            }
            currentIteration.evaluations.close()
            currentRun.iterationEnds.receive()
            if (stateMachine.currentState == StopPending || stateMachine.currentState == ForceStopPending) {
                stateMachine.states.send(Idle)
            }
            iterationCount += 1
        }
        currentRun.iterations.close()
        runResources.runs.close()
    }


}
