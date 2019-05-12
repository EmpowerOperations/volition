package com.empowerops.volition.ref_oasis.front_end

import com.empowerops.volition.dto.*
import com.empowerops.volition.ref_oasis.model.ModelService
import com.empowerops.volition.ref_oasis.model.getValue
import com.empowerops.volition.ref_oasis.optimizer.*
import com.empowerops.volition.ref_oasis.optimzier_cloud.Runner
import com.google.common.eventbus.EventBus
import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.*
import java.util.*

fun main(args: Array<String>) {
    run<Runnable>(OptimizationActor(), *args)
}

@Command(name = "Optimizer(Cloud)",
        mixinStandardHelpOptions = true,
        version = ["Reference version: ?.?", "Volition API version: ?.?"],
        description = ["Reference optimizer using Volition API"])
class OptimizationActor : Runnable{

    @Option(names = ["-p", "--port"], paramLabel = "PORT", description = ["Run optimizer with specified port, when not specified, port number will default to 5550"])
    var port: Int = 5550

    @Option(names = ["-o", "--overwrite"], description = ["Enable register overwrite when duplication happens"])
    var overwrite: Boolean = false

    val eventBus = EventBus()
    private val logger: ConsoleOutput = ConsoleOutput(eventBus)

    override fun run() {
        startServer()
    }

    private fun startServer() = runBlocking {
        val modelService = ModelService(eventBus, overwrite)
        val evaluationEngine = EvaluationEngine(eventBus, logger)
        val simpleStarterStopper = SimpleStarterStopper(evaluationEngine, modelService)
        val apiService = ApiService(modelService)
        val optimizerEndpoint = OptimizerEndpoint(apiService, simpleStarterStopper, modelService)
        val server = NettyServerBuilder.forPort(port).addService(ServerInterceptors.intercept(optimizerEndpoint, LoggingInterceptor(logger))).build()
        server.start()
    }
}

class SimpleStarterStopper(val evaluationEngine : EvaluationEngine, val modelService: ModelService) : IStaterStopper{
    val result : MutableMap<UUID, RunConfiguration.SingleSimluationConfiguraion> = mutableMapOf()
    override suspend fun stop(request: StopOptimizationCommandDTO): StopOptimizationResponseDTO {
        //DO stop via signal
        val fromString = UUID.fromString(request.id)
        require(fromString in result.keys)
        result.getValue(fromString).stopRequested.complete(Unit)
        return StopOptimizationResponseDTO.newBuilder().setMessage("").build()
    }

    override suspend fun start(request: StartOptimizationCommandDTO): StartOptimizationResponseDTO {
        //TODO start a new runner
        val configuration = RunConfiguration.SingleSimluationConfiguraion(
                simulation =  modelService.simulations.getValue(request.name),
                proxy =   modelService.proxies.getValue(request.name),
                run =  1,
                iteration = 15
        )

        GlobalScope.launch{Runner(evaluationEngine).run(configuration)}
        val randomUUID = UUID.randomUUID()
        result += randomUUID to configuration
        return StartOptimizationResponseDTO.newBuilder().setRunID(randomUUID.toString()).build()
    }
}