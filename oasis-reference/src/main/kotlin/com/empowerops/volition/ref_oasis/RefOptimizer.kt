package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.LoggingInterceptor
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerInterceptors
import javafx.application.Application
import javafx.beans.property.SimpleStringProperty
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
    val resultList: ObservableList<OptimizerEndpoint.Result> = FXCollections.observableArrayList()
    val updateList: ObservableList<String> = FXCollections.observableArrayList()
    val currentEvaluationStatus = SimpleStringProperty()
    val viewData = ViewData(list, messageList, currentEvaluationStatus, resultList, updateList)

    init {
        optimizerEndpoint = OptimizerEndpoint(viewData)
        server = ServerBuilder
                    .forPort(5550)
                    .addService(ServerInterceptors.intercept(optimizerEndpoint, LoggingInterceptor(System.out)))
                    .build()
        server.start()
        println("reference optimizer is running")
    }

    override fun start(primaryStage: Stage) {
        val fxmlLoader = FXMLLoader()
        val root = fxmlLoader.load<Parent>(FileInputStream("oasis-reference/src/main/kotlin/com/empowerops/volition/ref_oasis/OptimizerView.fxml"))
        val controller = fxmlLoader.getController<OptimizerController>();
        primaryStage.scene = Scene(root)
        primaryStage.show()

        controller.setData(viewData, optimizerEndpoint)
    }

    fun close(){
        server.shutdown()
    }
}