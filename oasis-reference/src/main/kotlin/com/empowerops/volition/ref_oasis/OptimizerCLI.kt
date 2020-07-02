package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.LoggingInterceptor
import com.google.common.eventbus.EventBus
import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.*
import picocli.CommandLine.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.Callable
import java.util.jar.Manifest
import kotlin.collections.LinkedHashMap
import kotlin.coroutines.CoroutineContext

fun main(args: Array<String>) = runBlocking<Unit> { mainAsync(args)?.join() }

fun mainAsync(args: Array<String>): Job? {
    val console: PrintStream = if(System.getProperty("com.empowerops.volition.ref_oasis.useConsoleAlt")?.toLowerCase() == "true") consoleAlt else System.out
    val cli = OptimizerCLI(console)
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
class OptimizerCLI(val console: PrintStream) : Callable<Job> {

    @Option(names = ["-p", "--port"], paramLabel = "PORT", description = ["Run optimizer with specified port, when not specified, port number will default to 5550"])
    var port: Int = 5550

    private val eventBus = EventBus()
    private val logger: ConsoleOutput = ConsoleOutput(eventBus)
    private val scope: CoroutineScope = OptimizerCLICoroutineScope()

    private val job = scope.launch(start = CoroutineStart.LAZY) {

        val modelService = LinkedHashMap<UUID, RunResult>()
        val optimizerEndpoint = OptimizerEndpoint(
                modelService,
                OptimizationActorFactory(this@launch, RandomNumberOptimizer(), modelService, eventBus)
        )
        val server = NettyServerBuilder
                .forPort(port)
                .addService(ServerInterceptors.intercept(optimizerEndpoint, LoggingInterceptor(logger)))
                .build()

        try {
            // TBD: I'm not sure why I cant simply use coroutineScope {} here,
            // the key that im looking for is that if this job is cancelled
            // I want the finally block to be triggered,
            // but if I use coroutineScope {}, when this job is cancelled, it doesnt throw,
            // it seems to be waiting for awaitTermination()
            async(Dispatchers.IO) {
                server.start()
                console.println("Volition Server running")
                server.awaitTermination()
            }.await()
        }
        finally {
            server.shutdownNow()
            server.awaitTermination()
        }
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