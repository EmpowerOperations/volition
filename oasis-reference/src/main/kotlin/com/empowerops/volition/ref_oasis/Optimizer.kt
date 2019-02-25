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

fun main(args: Array<String>) {
    Application.launch(Optimizer::class.java)
}

class Optimizer : Application(){
    lateinit var server : Server
    lateinit var endpoint : OptimizerEndpoint
    lateinit var modelService : DataModelService
    private val list: ObservableList<String> = FXCollections.observableArrayList()
    private val messageList: ObservableList<Message> = FXCollections.observableArrayList()
    private val resultList: ObservableList<Result> = FXCollections.observableArrayList()
    private val updateList: ObservableList<String> = FXCollections.observableArrayList()
    private val currentEvaluationStatus = SimpleStringProperty()
    private val viewData = ViewData(list, messageList, currentEvaluationStatus, resultList, updateList)

    override fun start(primaryStage: Stage) {
        val fxmlLoader = FXMLLoader()
        val resourceAsStream = this.javaClass.classLoader.getResourceAsStream("com.empowerops.volition.ref_oasis/OptimizerView.fxml")
        val root = fxmlLoader.load<Parent>(resourceAsStream)
        val controller = fxmlLoader.getController<OptimizerController>()
        primaryStage.title = "Volition Reference Optimizer"
        primaryStage.scene = Scene(root)
        primaryStage.show()

        setupService(controller)

        controller.setData(modelService, endpoint)
    }

    fun setupService(controller : OptimizerController) {
        modelService = DataModelService(viewData) { controller.rebindView() }
        endpoint = OptimizerEndpoint(modelService)
        server = ServerBuilder
                .forPort(5550)
                .addService(ServerInterceptors.intercept(endpoint, LoggingInterceptor(System.out)))
                .build()
        server.start()
    }

}