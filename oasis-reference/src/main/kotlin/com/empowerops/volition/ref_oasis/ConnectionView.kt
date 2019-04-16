package com.empowerops.volition.ref_oasis

import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.scene.layout.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import tornadofx.*

class ConnectionView(
        val dataModel: DataModelService,
        val eventBus: EventBus) : View("My View") {


    private val regList: ObservableList<String> = FXCollections.observableArrayList()

    override val root = listview<String> {
        vgrow = Priority.ALWAYS
        //TODO make this good looking item
        isEditable = false
        cellFormat {name ->
            val node = dataModel.simulations.getValue(name)
            graphic = vbox {
                hbox {
                    label("Name: ")
                    label(name)
                }
                hbox {
                    label("Description: ")
                    textflow {
                        text(node.description)
                    }
                }
                hbox {
                    label("Inputs: ")
                    vbox {
                        node.inputs.forEach { input ->
                            add(label("${input.name} [${input.lowerBound}, ${input.upperBound}] "))
                        }
                    }
                }
                hbox {
                    label("Outputs: ")
                    vbox {
                        node.outputs.forEach { output ->
                            add(label(output.name))
                        }
                    }
                }
                hbox {
                    spacing = 5.0
                    button("refresh") {
                        action {
                            GlobalScope.launch {
                                dataModel.updateSimulation(name)
                            }
                        }
                    }
                    button("delete") {
                        action {
                            GlobalScope.launch {
                                dataModel.closeSim(name)
                            }
                        }
                    }
                    button("add setup") {
                        action {
                            GlobalScope.launch {
                                dataModel.addAndSyncConfiguration(name)
                            }
                        }
                    }
                    button("remove setup") {
                        action {
                            GlobalScope.launch {
                                dataModel.removeConfiguration(name)
                            }
                        }
                    }
                }
            }
        }
    }

    init {
        eventBus.register(this)
        root.items = regList
    }

    @Subscribe
    fun whenNewNodeRegistered(event : PluginRegisteredEvent){
        GlobalScope.launch(Dispatchers.JavaFx) {
            regList.add(event.name)
        }
    }

    @Subscribe
    fun whenNodeUnRegistered(event : PluginUnRegisteredEvent){
        GlobalScope.launch(Dispatchers.JavaFx) {
            regList.remove(event.name)
        }
    }

    @Subscribe
    fun whenNodeUnRegistered(event : PluginRenamedEvent){
        GlobalScope.launch(Dispatchers.JavaFx) {
            regList.remove(event.oldName)
            regList.add(event.newName)
        }
    }

    @Subscribe
    fun whenNodeRefreshed(event: PluginUpdatedEvent) {
        GlobalScope.launch(Dispatchers.JavaFx) {
            root.refresh()
        }
    }

}

