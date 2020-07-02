package com.empowerops.volition.ref_oasis.front_end

import com.empowerops.volition.ref_oasis.*
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
        val model: ModelService,
        val eventBus: EventBus
) : View("My View") {

    private val regList: ObservableList<String> = FXCollections.observableArrayList()

    override val root = listview<String> {
        vgrow = Priority.ALWAYS
        //TODO make this good looking item
        isEditable = false
        cellFormat { name ->
            val node = model.findSimulationName(name)
            graphic = vbox {
                hbox {
                    label("Name: ")
                    label(name)
                }
                hbox {
                    label("Description: ")
                    textflow {
                        text("TODO")
                    }
                }
                hbox {
                    label("Inputs: ")
                    vbox {
                        node?.inputs?.forEach { input ->
                            add(label("$input "))
                        }
                    }
                }
                hbox {
                    label("Outputs: ")
                    vbox {
                        node?.outputs?.forEach { output ->
                            add(label(output))
                        }
                    }
                }
                hbox {
                    spacing = 5.0
                    button("refresh") {
                        action {
                            GlobalScope.launch {
                                eventBus.post(SimulationUpdateRequestedEvent(name))
                            }
                        }
                    }
                    button("delete") {
                        action {
                            model?.removeSim(name)
                        }
                    }
                    button("add setup") {
                        action {
                            val simulation = Simulation(name)
                            TODO("model.addSim(simulation)")
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
        regList.add(event.name)
    }

    @Subscribe
    fun whenNodeUnRegistered(event : PluginUnRegisteredEvent){
        regList.remove(event.name)
    }

    @Subscribe
    fun whenNodeUnRegistered(event : PluginRenamedEvent){
        regList.remove(event.oldName)
        regList.add(event.newName)
    }

    @Subscribe
    fun whenNodeRefreshed(event: PluginUpdatedEvent) {
        root.refresh()
    }

}

