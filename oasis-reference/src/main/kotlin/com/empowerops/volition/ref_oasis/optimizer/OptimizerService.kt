package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.ref_oasis.model.*
import com.empowerops.volition.ref_oasis.model.Helpers.Companion.NullUUID
import com.empowerops.volition.ref_oasis.optimizer.State.*
import com.empowerops.volition.ref_oasis.plugin.PluginService
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*

class Actions(
        private val startAction: IStartAction,
        private val stopAction: IStopAction,
        private val forceStopAction: IForceStopAction,
        private val pauseAction: IPauseAction,
        private val resumeAction: IResumeAction
) : IStartAction by startAction,
        IStopAction by stopAction,
        IForceStopAction by forceStopAction,
        IPauseAction by pauseAction,
        IResumeAction by resumeAction

class OptimizerService(
        private val eventBus: EventBus,
        private val modelService: ModelService,
        private val inputGenerator: InputGenerator,
        private val pluginService: PluginService,
        private val runResources: RunResources,
        private val evaluationEngine: IEvaluationEngine
)  {
    var currentState: State = Idle
    val forceStopSignals : MutableList<ForceStopSignal> = mutableListOf()

    suspend fun startProcess() = GlobalScope.launch{
        for (newState in runResources.states) {

            when (currentState) {
                Idle -> {
                    when (newState) {
                        StartPending -> {
                            currentState = newState
                            val runID = UUID.randomUUID()
                            runResources.runID = runID
                            pluginService.notifyStart(runID)
                            launch { evaluationEngine.handle(runResources.runs) }
                            eventBus.post(StartRequestedEvent(runID))
                            launch {runResources.states.send(Running) }
                        }
                        Idle, Running, PausePending, Paused, StopPending, ForceStopPending -> TODO()

                    }
                }
                StartPending -> {
                    when (newState) {
                        Running -> {
                            currentState = newState
                            runT()
                            eventBus.post(RunStartedEvent(runResources.runID)) }
                        Idle, StartPending, PausePending, Paused, StopPending, ForceStopPending -> TODO()
                    }
                }
                Running -> {
                    when (newState) {
                        PausePending -> {
                            currentState = newState
                            eventBus.post(PausedRequestedEvent(runResources.runID)) }
                        StopPending -> {
                            currentState = newState
                            runResources.resumes.send(Unit)
                            eventBus.post(StopRequestedEvent(runResources.runID))
                        }
                        Idle, StartPending, Running, Paused, ForceStopPending -> TODO()
                    }
                }
                PausePending -> {
                    when (newState) {
                        Paused ->{
                            currentState = newState
                            eventBus.post(PausedEvent(runResources.runID))
                        }
                        StopPending -> {
                            currentState = newState
                            runResources.resumes.send(Unit)
                            eventBus.post(StopRequestedEvent(runResources.runID))
                        }
                        ForceStopPending ->{
                            currentState = newState
                            forceStopSignals.forEach { it.completableDeferred.complete(Unit) }
                            eventBus.post(ForceStopRequestedEvent(runResources.runID))
                        }
                        Idle, StartPending, Running, PausePending -> TODO()
                    }
                }
                Paused -> {
                    when (newState) {
                        Running -> {
                            currentState = newState
                            runResources.resumes.send(Unit)
                            eventBus.post(RunResumedEvent(runResources.runID))
                        }
                        StopPending -> {
                            currentState = newState
                            runResources.resumes.send(Unit)
                            eventBus.post(StopRequestedEvent(runResources.runID))
                        }
                        Idle, StartPending, PausePending, Paused, ForceStopPending -> TODO()
                    }
                }
                StopPending -> {
                    when (newState) {
                        Idle -> {
                            currentState = newState
                            eventBus.post(RunStoppedEvent(runResources.runID))
                            pluginService.notifyStop(runResources.runID)
                            runResources.runID = NullUUID
                        }
                        ForceStopPending -> {
                            currentState = newState
                            forceStopSignals.forEach { it.completableDeferred.complete(Unit) }
                            eventBus.post(ForceStopRequestedEvent(runResources.runID))
                        }
                        StartPending, Running, PausePending, Paused, StopPending -> TODO()
                    }
                }
                ForceStopPending -> {
                    when (newState) {
                        Idle -> {
                            currentState = newState
                            eventBus.post(RunStoppedEvent(runResources.runID))
                            pluginService.notifyStop(runResources.runID)
                            runResources.runID = NullUUID
                        }
                        StartPending , Running , PausePending, Paused , StopPending , ForceStopPending -> TODO()
                    }
                }
            }

        }
    }

    suspend fun CoroutineScope.runT() = launch {
        val currentRun = Run(runResources.runID)
        runResources.runs.send(currentRun)
        while (currentState == Running) {
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

                //we are blocking on evaluation, remove this we need a newer evaluation block, so this can consider as the implementation for sequential evaluation
                // when considering parallel, we need a list of channel/deffered to block at the end, figuring out dependency, put a worker pool limit
                currentIteration.evaluationEnds.receive()
                if (currentState == PausePending) {
                    runResources.states.send(Paused)
                    runResources.resumes.receive()
                    if (currentState == StopPending || currentState == ForceStopPending) {
                        runResources.states.send(Idle)
                    }
                }
            }
            currentIteration.evaluations.close()
            currentRun.iterationEnds.receive()
            if (currentState == StopPending || currentState == ForceStopPending) {
                runResources.states.send(Idle)
            }
            iterationCount+=1
        }
        currentRun.iterations.close()
        runResources.runs.close()
    }
}
