package com.empowerops.volition.ref_oasis

import com.empowerops.volition.BetterExceptionsInterceptor
import com.empowerops.volition.DEFAULT_MAX_HEADER_SIZE
import com.empowerops.volition.LoggingInterceptor
import com.empowerops.volition.dto.RecommendedMaxHeaderSize
import com.empowerops.volition.dto.RecommendedMaxMessageSize
import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.*
import picocli.CommandLine.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.jar.Manifest
import kotlin.collections.LinkedHashMap

fun main(args: Array<String>) = runBlocking<Unit> { mainAsync(args)?.job?.join() }

fun mainAsync(args: Array<String>): OptimizerCLI? {
    val console: PrintStream = if(System.getProperty("com.empowerops.volition.ref_oasis.useConsoleAlt")?.lowercase() == "true") consoleAlt else System.out
    val cli = OptimizerCLI(console)
    val result = call(cli, console, *args)
    return if(result != null) cli else null
}

class OptimizerCLICoroutineScope: CoroutineScope {
    override val coroutineContext = Dispatchers.IO + SupervisorJob()
}

@Command(
        name = "Optimizer(Run Option)",
        mixinStandardHelpOptions = true,
        versionProvider = MetaINFVersionProvider::class,
        description = ["Reference optimizer using Volition API"]
)
class OptimizerCLI(val console: PrintStream) : Callable<Job> {

    @Option(
        names = ["-p", "--port"],
        paramLabel = "PORT",
        description = ["Run optimizer with specified port, when not specified, port number will default to 27016"]
    )
    var port: Int = 27016

    private val scope: CoroutineScope = OptimizerCLICoroutineScope()

    val modelService = LinkedHashMap<UUID, RunResult>()

    private val optimizerEndpoint by lazy {
        OptimizerEndpoint(
            modelService,
            OptimizationActorFactory(scope, RandomNumberOptimizer(), modelService)
        )
    }

    private val server by lazy {

//        val maxMetadataSize = 10_000_000;

        val services = ServerInterceptors.intercept(
            optimizerEndpoint,
            LoggingInterceptor(System.out::println),
            BetterExceptionsInterceptor(DEFAULT_MAX_HEADER_SIZE)
        )

        NettyServerBuilder
            .forPort(port)
            .keepAliveTime(12, TimeUnit.HOURS)
            .maxInboundMetadataSize(RecommendedMaxHeaderSize)
            .maxInboundMessageSize(RecommendedMaxMessageSize)
            .addService(services)
            .build()
    }

    val job = scope.launch(start = CoroutineStart.LAZY) {
        try {
            // TBD: I'm not sure why I cant simply use coroutineScope {} here,
            // the key that im looking for is that if this job is cancelled
            // I want the finally block to be triggered,
            // but if I use coroutineScope {}, when this job is cancelled, it doesnt throw,
            // it seems to be waiting for awaitTermination()
            async(Dispatchers.IO) {
                server.start()
                console.println("Volition Server running on port $port")
                server.awaitTermination()
            }.await()
        }
        catch (ex: Throwable){
//            fail; //here, i get an exception "parent job is cancelling", but i dont know why.
            throw ex;
        }
        finally {
            server.shutdownNow()
            server.awaitTermination()
        }
    }

    override fun call(): Job = job.also { it.start() }

    suspend fun stop(){
        server.shutdownNow()
    }
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