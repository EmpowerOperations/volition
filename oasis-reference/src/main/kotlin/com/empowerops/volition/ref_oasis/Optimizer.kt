package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.LoggingInterceptor
import com.google.common.eventbus.EventBus
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerInterceptors
import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.stage.Stage

fun main(args: Array<String>) {
    Application.launch(Optimizer::class.java)
}

class Optimizer : Application() {
    lateinit var server: Server
    lateinit var endpoint: OptimizerEndpoint
    lateinit var modelService: DataModelService
    val eventBus: EventBus = EventBus()

    override fun start(primaryStage: Stage) {
        val fxmlLoader = FXMLLoader()
        val resourceAsStream = this.javaClass.classLoader.getResourceAsStream("com.empowerops.volition.ref_oasis/OptimizerView.fxml")
        val root = fxmlLoader.load<Parent>(resourceAsStream)
        val controller = fxmlLoader.getController<OptimizerController>()
        primaryStage.title = "Volition Reference Optimizer"
        primaryStage.scene = Scene(root)
        primaryStage.show()

        setupService()
        val connectionView = ConnectionView(modelService, endpoint, eventBus)

        controller.setData(modelService, endpoint, eventBus, connectionView.root)
    }

    fun setupService() {
        modelService = DataModelService(eventBus)
        endpoint = OptimizerEndpoint(modelService, eventBus)
        server = ServerBuilder
                .forPort(5550)
                .addService(ServerInterceptors.intercept(endpoint, LoggingInterceptor(System.out)))
                .build()
        server.start()
    }
}
