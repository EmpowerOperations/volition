package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.LoggingInterceptor
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerInterceptors
import javafx.beans.property.SimpleStringProperty
import javafx.beans.property.StringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.scene.control.SelectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.javafx.*
import kotlinx.coroutines.launch
import tornadofx.*

class OptimizerApp : App(OptimizerScreen::class)

class OptimizerController : Controller() {
    val list: ObservableList<String> = FXCollections.observableArrayList()
    val messageList: ObservableList<OptimizerEndpoint.Message> = FXCollections.observableArrayList()
    val optimizerEndpoint: OptimizerEndpoint = OptimizerEndpoint(list, messageList)
    private val server : Server = ServerBuilder.forPort(5550)
            .addService(ServerInterceptors.intercept(optimizerEndpoint, LoggingInterceptor(System.out)))
            .build()

    init {
        start()
    }

    fun start() {
        server.start()
        println("reference optimizer is running")
    }

    fun close(){
        server.shutdown()
    }
}

class OptimizerScreen : View("Optimizer") {

    val controller: OptimizerController by inject()
    val selected: StringProperty = SimpleStringProperty()


    override val root = borderpane {
        bottom {
            listview(controller.messageList) {
                selectionModel.selectionMode = SelectionMode.SINGLE
            }
        }
        center {
            vbox {
                listview(controller.list) {
                    bindSelected(selected)
                    selectionModel.selectionMode = SelectionMode.SINGLE
                }
                vbox {
                    textflow {
                        text {
                            bind(selected.stringBinding { "Name:\n${selected.get()}" })
                        }
                    }
                    textflow {
                        text {
                            bind(selected.stringBinding { "Inputs:\n${controller.optimizerEndpoint.simulationsByName[it]?.inputs?.joinToString("\n")}" })
                        }
                    }
                    textflow {
                        text {
                            bind(selected.stringBinding { "Outputs:\n${controller.optimizerEndpoint.simulationsByName[it]?.outputs?.joinToString("\n")}" })
                        }
                    }


                }
            }
        }
        right {
            form {
                fieldset {
                    button("Sync") {
                        action { GlobalScope.launch(Dispatchers.JavaFx) { controller.optimizerEndpoint.syncAll() } }
                    }
                    button("Start Optimization") {
                        action { GlobalScope.launch(Dispatchers.JavaFx) { controller.optimizerEndpoint.startOptimization() } }
                    }
                    button("Stop Optimization") {
                        action { GlobalScope.launch(Dispatchers.JavaFx) { controller.optimizerEndpoint.stopOptimization() } }
                    }
                    button("Cancel All Current Load"){
                        action { GlobalScope.launch(Dispatchers.JavaFx) { controller.optimizerEndpoint.cancelAll() } }
                    }
                    button("Disconnect All"){
                        action { GlobalScope.launch(Dispatchers.JavaFx) { controller.optimizerEndpoint.disconnectAll() } }
                    }
                    button("Cancel And Stop"){
                        action { GlobalScope.launch(Dispatchers.JavaFx) { controller.optimizerEndpoint.cancelAndStop() } }
                    }
                    button("Unregister All"){
                        action { GlobalScope.launch(Dispatchers.JavaFx) { controller.optimizerEndpoint.unregisterAll() } }
                    }
                }
            }
        }
    }
}