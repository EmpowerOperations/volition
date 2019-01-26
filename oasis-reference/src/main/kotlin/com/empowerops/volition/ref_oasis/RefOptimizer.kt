package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.LoggingInterceptor
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerInterceptors
import javafx.application.Application
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.stage.Stage
import java.io.FileInputStream

fun main(args: Array<String>) {
    Application.launch(RefOptimizer::class.java)
}

class RefOptimizer : Application(){
    var server : Server
    val optimizerEndpoint: OptimizerEndpoint
    val list: ObservableList<String> = FXCollections.observableArrayList()
    val messageList: ObservableList<OptimizerEndpoint.Message> = FXCollections.observableArrayList()

    init {
        optimizerEndpoint = OptimizerEndpoint(list, messageList)
        server = ServerBuilder.forPort(5550)
        .addService(ServerInterceptors.intercept(optimizerEndpoint, LoggingInterceptor(System.out)))
                .build()
        start()
    }

    override fun start(primaryStage: Stage) {
        val fxmlLoader = FXMLLoader()
        val root = fxmlLoader.load<Parent>(FileInputStream("oasis-reference/src/main/kotlin/com/empowerops/volition/ref_oasis/OptimizerView.fxml"))
        val controller = fxmlLoader.getController<OptimizerController>();
        primaryStage.scene = Scene(root)
        primaryStage.show()
        val viewData = ViewData(list, messageList)
        controller.setData(viewData, optimizerEndpoint)
    }

    fun start() {
        server.start()
        println("reference optimizer is running")
    }

    fun close(){
        server.shutdown()
    }
}