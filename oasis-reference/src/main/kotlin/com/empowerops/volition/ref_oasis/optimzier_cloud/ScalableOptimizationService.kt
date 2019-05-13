package com.empowerops.volition.ref_oasis.optimzier_cloud

import com.empowerops.volition.ref_oasis.model.EvaluationResult
import com.empowerops.volition.ref_oasis.model.ForceStopSignal
import com.empowerops.volition.ref_oasis.model.Proxy
import com.empowerops.volition.ref_oasis.optimizer.EvaluationEngine
import com.empowerops.volition.ref_oasis.optimizer.EvaluationRequest
import com.empowerops.volition.ref_oasis.optimizer.RunConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random

typealias DesignPoint = Map<String, Double>

data class Optimizer(val problemDefinition: RunConfiguration){
    val points: MutableList<DesignPoint> = mutableListOf()

    fun addOutput(designInput: DesignPoint, designOutput: DesignPoint){
        points += (designInput + designOutput)
    }
    fun getInput(proxy: Proxy) : DesignPoint{
        return proxy.inputs.associate { it.name to Random.nextDouble(it.lowerBound, it.upperBound) }
    }
}

/**
 * Scalable configuration, stateless, in and out
 * Most basic runner, no state, no UI
 * Issues start, force stop, with configuraion
 */
class Runner(val evaluationEngine : EvaluationEngine){
    suspend fun run(job : RunConfiguration) = coroutineScope{
        driveRun(job, Channel(), Channel())
    }

    private fun CoroutineScope.driveRun(
            configuration : RunConfiguration,
            requests: Channel<EvaluationRequest>,
            results: Channel<EvaluationResult>
    ) = launch {
        when (configuration) {
            is RunConfiguration.SingleSimluationConfiguraion -> {
                launch { evaluationEngine.handleEvaluation(requests, results) }
                startRun(configuration, requests, results)
            }
        }
    }

    private fun CoroutineScope.startRun(
            configuration: RunConfiguration.SingleSimluationConfiguraion,
            requests: SendChannel<EvaluationRequest>,
            results: ReceiveChannel<EvaluationResult>
    ) = launch {
        val optimizer = Optimizer(configuration)
        try {
            for (runNumber in 1..(configuration.run ?: Int.MAX_VALUE)) {
                for (iterationNumber in 1..(configuration.iteration ?: Int.MAX_VALUE)) {
                    val nextInput = optimizer.getInput(configuration.proxy)
                    val evaluationRequest = EvaluationRequest(
                            configuration.proxy,
                            configuration.simulation,
                            nextInput,
                            ForceStopSignal(configuration.proxy.name)
                    )
                    requests.send(evaluationRequest)
                    val result = results.receive()
                    optimizer.addOutput(result.inputs, result.result)
                    configuration.results.send(result)
                }
            }
        } finally {
            requests.close()
        }
    }

}
