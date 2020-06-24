package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import com.empowerops.volition.ref_oasis.front_end.ConsoleOutput
import com.google.common.eventbus.EventBus
import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.*
import picocli.CommandLine.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.Appendable
import java.lang.Runnable
import java.lang.StringBuilder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.jar.Manifest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

suspend fun main(args: Array<String>) = mainAsync(args)?.join()

fun mainAsync(args: Array<String>): Job? {
    val cli = OptimizerCLI()
    val console: PrintStream = if(System.getProperty("com.empowerops.volition.ref_oasis.useConsoleAlt").toLowerCase() == "true") consoleAlt else System.out
    val result = call(cli, console, *args)
    return result
}

class OptimizerCLICoroutineScope: CoroutineScope {
    override val coroutineContext: CoroutineContext get() = Dispatchers.IO + SupervisorJob()
}

@Command(
        name = "Optimizer(Run Option)",
        mixinStandardHelpOptions = true,
        versionProvider = MetaINFVersionProvider::class,
        description = ["Reference optimizer using Volition API"]
)
class OptimizerCLI : Callable<Job> {

    @Option(names = ["-p", "--port"], paramLabel = "PORT", description = ["Run optimizer with specified port, when not specified, port number will default to 5550"])
    var port: Int = 5550

    @Option(names = ["-o", "--overwrite"], description = ["Enable register overwrite when duplication happens"])
    var overwrite: Boolean = false

    private val eventBus = EventBus()
    private val logger: ConsoleOutput = ConsoleOutput(eventBus)
    private val scope: CoroutineScope = OptimizerCLICoroutineScope()

    private val job = scope.launch(start = CoroutineStart.LAZY) {
        val modelService = ModelService(eventBus, overwrite)
        val optimizerEndpoint = OptimizerEndpoint(
                ConfigurationActorFactory(this@launch, modelService),
                OptimizationActorFactory(this@launch, RandomNumberOptimizer(), modelService, eventBus)
        )
        val server = NettyServerBuilder.forPort(port).addService(ServerInterceptors.intercept(optimizerEndpoint, LoggingInterceptor(logger))).build()
        server.start()
        server.awaitTermination()
    }

    override fun call(): Job = job.also { it.start() }
}

class MetaINFVersionProvider: IVersionProvider {
    override fun getVersion(): Array<String> {

        val manifests = javaClass.classLoader.getResources("META-INF/MANIFEST.MF").asSequence().toList()

        val versions = manifests.map { Manifest(it.openStream()) }
                .filter { "Empower Op" in (it.mainAttributes.getValue("Specification-Vendor") ?: "") }
                .map { it.mainAttributes.getValue("Specification-Title") + " " + it.mainAttributes.getValue("Specification-Version") }
                .takeUnless { it.isEmpty() } ?: listOf("volition-temp-build 0.0.0")

        return versions.toTypedArray()
    }
}

val consoleAltBytes = ByteArrayOutputStream()
val consoleAlt: PrintStream = run {
    PrintStream(consoleAltBytes, true, StandardCharsets.UTF_8.name())
}