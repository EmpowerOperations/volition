package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.dto.Logger
import com.empowerops.volition.dto.RequestQueryDTO
import com.empowerops.volition.ref_oasis.model.*
import com.google.common.eventbus.EventBus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.selects.select
import java.util.*

interface IEvaluationEngine {
    suspend fun handle(runs: ReceiveChannel<Run>)
}

data class EvaluationRequest(
        val inputVector: Map<String, Double>,
        val proxy: Proxy,
        val simulation: Simulation,
        val forceStopSignal: ForceStopSignal = ForceStopSignal(proxy.name)
)

data class Iteration(
        val number: Int,
        val evaluations: Channel<EvaluationRequest> = Channel(),
        val evaluationEnds : Channel<Unit> = Channel()
)
data class Run(
        val runID: UUID,
        val iterations: Channel<Iteration> = Channel(),
        val iterationEnds: Channel<Unit> = Channel(),
        val forceStopped: Channel<Unit> = Channel()
)


class EvaluationEngine(
        private val modelService: ModelService,
        private val eventBus: EventBus,
        private val logger: Logger
) : IEvaluationEngine {

    override suspend fun handle(runs: ReceiveChannel<Run>) = coroutineScope {
        for (run in runs) {
            for (iteration in run.iterations) {
                var pluginNumber = 1
                val requests = Channel<EvaluationRequest>()
                val results = Channel<EvaluationResult>()
                evaluate(requests, results)
                for (request in iteration.evaluations) {
                    requests.send(request)
                    eventBus.post(BasicStatusUpdateEvent("Evaluating: ${request.proxy.name} ($pluginNumber/${modelService.proxies.size})"))
                    val result = results.receive()
                    modelService.addNewResult(run.runID, result)
                    eventBus.post(BasicStatusUpdateEvent("Evaluation finished."))
                    iteration.evaluationEnds.send(Unit)
                    pluginNumber++
                }
                requests.close()
                results.close()
                run.iterationEnds.send(Unit)
            }
        }
    }

    /**
     * About cancel:
     * As we finishing the evaluation, results.send(simResult) will trigger the next request to be send.
     * Question is do we do that before or after we proceed when cancel. Ideally, cancel should be a async process but in order to
     * go ahead to do the next evaluation we need confirm we can do the next evaluation by asking the plugin readiness.
     * CheckIsReady seems the most logical way to do it but it will also make the implementation more complicated.
     *
     * The decision here is we treat it as a sequential process, even both actions are async ready.
     */
    private suspend fun CoroutineScope.evaluate(
            requests: ReceiveChannel<EvaluationRequest>,
            results: SendChannel<EvaluationResult>
    ) = launch{
        for (request in requests) {
            with(request) {
                val simResult = evaluateAsync(proxy, simulation, inputVector, forceStopSignal).await()
                if (simResult is EvaluationResult.TimeOut) {
                    eventBus.post(BasicStatusUpdateEvent("Timed out, Canceling..."))
                    val cancelResult = cancelCurrentEvaluationAsync(simulation, forceStopSignal).await()
                    val cancelMessage = when (cancelResult) {
                        is CancelResult.Canceled -> "Evaluation Canceled"
                        is CancelResult.CancelFailed -> "Cancellation Failed, Cause:\n${cancelResult.exception}"
                        is CancelResult.CancelTerminated -> "Cancellation Terminated, Cause:\nForce-stopped"
                    }
                    logger.log(cancelMessage, "Optimizer")
                    eventBus.post(BasicStatusUpdateEvent("Cancel finished. [$cancelResult]"))
                }
                results.send(simResult)
            }
        }
    }

    private fun CoroutineScope.evaluateAsync(
            proxy: Proxy,
            simulation : Simulation,
            inputVector: Map<String, Double>,
            forceStopSignal: ForceStopSignal
    ): Deferred<EvaluationResult> = async{
        val message = RequestQueryDTO.newBuilder().setEvaluationRequest(
                RequestQueryDTO.SimulationEvaluationRequest
                        .newBuilder()
                        .setName(simulation.name)
                        .putAllInputVector(inputVector)
        ).build()

        return@async try {
            simulation.input.onNext(message)
            select<EvaluationResult> {
                simulation.output.onReceive { EvaluationResult.Success(it.name, inputVector, it.outputVectorMap) }
                simulation.error.onReceive { EvaluationResult.Failed(it.name, inputVector, it.exception) }
                if (proxy.timeOut != null) {
                    onTimeout(proxy.timeOut.toMillis()) {
                        EvaluationResult.TimeOut(simulation.name, inputVector)
                    }
                }
                forceStopSignal.completableDeferred.onAwait {
                    EvaluationResult.Terminated(simulation.name, inputVector, "Evaluation is terminated during evaluation")
                }
            }
        } catch (exception: Exception) {
            EvaluationResult.Error(
                    simulation.name,
                    inputVector,
                    "Unexpected error happened when try to evaluate $inputVector though simulation ${simulation.name}. Cause: $exception"
            )
        }
    }

    private fun CoroutineScope.cancelCurrentEvaluationAsync(
            simulation: Simulation,
            forceStopSignal: ForceStopSignal) = async {
        val message = RequestQueryDTO.newBuilder().setCancelRequest(RequestQueryDTO.SimulationCancelRequest.newBuilder().setName(simulation.name)).build()

        simulation.input.onNext(message)
        return@async select<CancelResult> {
            simulation.output.onReceive { CancelResult.Canceled(it.name) }
            simulation.error.onReceive { CancelResult.CancelFailed(it.name, it.exception) }
            forceStopSignal.completableDeferred.onAwait {
                CancelResult.CancelTerminated(simulation.name, "Cancellation is terminated")
            }
        }
    }
}