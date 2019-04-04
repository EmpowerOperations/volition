package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.LoggingInterceptor
import com.google.common.eventbus.EventBus
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.IOException

/**
 * TODO: Add a main not using javafx for headless
 *
 * Note:
 * The only reason we are not having a another main calling console.run is I haven't figure out the release engineering part of this
 */
fun main(args: Array<String>) {
    Application.launch(OptimizerStarter::class.java, *args)
}

class OptimizerStarter : Application(){
    private val optimizer = Optimizer()

    override fun start(primaryStage: Stage) {
        val commandLine = CommandLine(optimizer)
        try {
            commandLine.parse(*parameters.raw.toTypedArray<String>())
            when {
                commandLine.isUsageHelpRequested -> {
                    commandLine.usage(System.out)
                    Platform.exit()
                }
                commandLine.isVersionHelpRequested -> {
                    commandLine.printVersionHelp(System.out)
                    Platform.exit()
                }
                else -> optimizer.start(primaryStage) //question: how should we handle close when in server mode
            }
        } catch (e: CommandLine.ParameterException) {
            System.err.println(e.message)
            commandLine.usage(System.out)
            Platform.exit()
        }
    }

    override fun stop() {
        optimizer.stop()
    }
}

@Command(name = "Optimizer",
        mixinStandardHelpOptions = true,
        version = ["Reference version: 1.0", "Volition API version: 1.0"],
        description = ["Reference optimizer using Volition API"])
class Optimizer {
    private var optimizerEndpoint: OptimizerEndpoint
    private var modelService: DataModelService
    private var optimizerService : OptimizerService
    private var pluginEndPoint : PluginEndPoint
    private val eventBus: EventBus = EventBus()
    private val logger : ConsoleOutput = ConsoleOutput(eventBus)
    private var server: Server

    @Option(names = ["-l", "--headless"], description = ["Run Optimizer in Headless Mode"])
    var headless: Boolean = false

    @Option(names = ["-p", "--port"], paramLabel = "PORT", description = ["Run optimizer with specified port, when not specified, port number will default to 5550"])
    var port: Int = 5550

    init {
        modelService = DataModelService(eventBus)
        pluginEndPoint = PluginEndPoint(modelService, logger, eventBus)
        optimizerService = OptimizerService(RandomNumberOptimizer(), modelService, eventBus, pluginEndPoint)
        optimizerEndpoint = OptimizerEndpoint(modelService, optimizerService)
        server = NettyServerBuilder.forPort(port).addService(ServerInterceptors.intercept(optimizerEndpoint, LoggingInterceptor(logger))).build()
    }

    fun start(primaryStage: Stage) {
        try {
            logger.log("Server started at: localhost:$port", "Optimizer")
            server.start()
        } catch (e: IOException) {
            logger.log("Error encountered when try to start the optimizer server.", "Optimizer")
            return
        }

        if(headless){
            //TODO splash screen etc.
        }
        else{
            val optimizerGUI = OptimizerGUIRootController(modelService, optimizerService, eventBus)
            primaryStage.title = "Volition Reference Optimizer"
            primaryStage.scene = Scene(optimizerGUI.root)
            primaryStage.show()
        }
    }

    fun stop() {
        logger.outChannel.close()
        server?.shutdown()
    }

}


