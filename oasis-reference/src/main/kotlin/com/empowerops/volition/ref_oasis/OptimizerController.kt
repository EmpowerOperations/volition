package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.LoggingInterceptor
import javafx.application.Application
import javafx.beans.value.ObservableObjectValue
import javafx.beans.value.ObservableStringValue
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ListView
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.layout.AnchorPane
import javafx.stage.Stage
import java.io.FileInputStream
import java.time.LocalDateTime
import java.util.*
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerInterceptors
import javafx.beans.property.SimpleStringProperty

fun main(args: Array<String>) {
    Application.launch(RefOptimizer::class.java)
}
class RefOptimizer : Application (){
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
        controller.setData(viewData)
    }

    fun start() {
        server.start()
        println("reference optimizer is running")
    }

    fun close(){
        server.shutdown()
    }
}


data class ViewData(
        val nodes: ObservableList<String>,
        val allMessages: ObservableList<OptimizerEndpoint.Message>
)

class OptimizerController() {
    @FXML lateinit var view : AnchorPane
    @FXML lateinit var listView : ListView<String>
    @FXML lateinit var messageTableView : TableView<OptimizerEndpoint.Message>

    @FXML lateinit var senderColumn : TableColumn<OptimizerEndpoint.Message, String>
    @FXML lateinit var timeColumn : TableColumn<OptimizerEndpoint.Message, String>
    @FXML lateinit var messageColumn : TableColumn<OptimizerEndpoint.Message, String>

    @FXML fun initialize() {
        senderColumn.setCellValueFactory { dataFeatures -> SimpleStringProperty(dataFeatures.value.sender) }
        timeColumn.setCellValueFactory { dataFeatures -> SimpleStringProperty(dataFeatures.value.receiveTime.toString()) }
        messageColumn.setCellValueFactory { dataFeatures -> SimpleStringProperty(dataFeatures.value.message)}
    }

    fun setData(viewData: ViewData){
        listView.items = viewData.nodes
        messageTableView.items = viewData.allMessages
    }
}