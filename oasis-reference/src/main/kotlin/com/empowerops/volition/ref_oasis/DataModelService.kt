package com.empowerops.volition.ref_oasis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Duration

class DataModelService(
        val viewData: ViewData,
        private val updateCallback: () -> Unit
) : ModelService {

    override var simByName: Map<String, Simulation> = emptyMap()


    public override val onSimulationNameChanged = EventHandler<SimulationNameChangedEvent>()

    override fun updateStatusMessage(message: String) {
        GlobalScope.launch(Dispatchers.JavaFx) {
            viewData.currentEvaluationStatus.value = message
        }
    }

    override fun addMessage(message: Message) {
        GlobalScope.launch(Dispatchers.JavaFx) {
            viewData.allMessages.add(message)
        }
    }

    override fun addResult(result: Result) {
        GlobalScope.launch(Dispatchers.JavaFx) {
            viewData.resultList.add(result)
        }
    }

    override fun setDuration(nodeName: String?, timeOut: Duration?) {
        if (nodeName == null || !simByName.containsKey(nodeName)) {
            return
        }
        val node: Simulation = simByName.getValue(nodeName)
        val newNode = node.copy(timeOut = timeOut)
        simByName += nodeName to newNode
    }

    class EventHandler<E> {
        private var handlers: List<suspend (E) -> Unit> = emptyList() // we can make this an atomic reference as an excercise.
        suspend fun fire(event: E): Unit = handlers.forEach { it.invoke(event) }
        operator fun plusAssign(handler: suspend (E) -> Unit): Unit { handlers += handler }
    }

    data class SimulationNameChangedEvent(val target: Simulation, val newName: String, val oldName: String)

    override fun renameSim(target: Simulation, newName: String, oldName: String) = runBlocking {
        require(oldName in simByName && newName !in simByName)
        simByName += newName to target
        simByName -= oldName

//        view.simulations.replace(oldName, newName)
        onSimulationNameChanged.fire(SimulationNameChangedEvent(target, newName, oldName))
//        removeNodeFromView(oldName)
//        addNodeToView(newName)
    }

    override fun removeSim(name: String) {
        require(simByName.containsKey(name))
        simByName -= name
        removeNodeFromView(name)
    }

    override fun addNewSim(simulation: Simulation) {
        require( ! simByName.containsKey(simulation.name))
        simByName += simulation.name to simulation
        addNodeToView(simulation.name)
    }

    override fun updateSim(newNode: Simulation) {
        require(simByName.containsKey(newNode.name))
        simByName += newNode.name to newNode
        rebindView()
    }

    private fun addNodeToView(name: String) {
        GlobalScope.launch(Dispatchers.JavaFx) {
            viewData.nodes.add(name)
        }
    }

    private fun removeNodeFromView(name: String) {
        GlobalScope.launch(Dispatchers.JavaFx) {
            viewData.nodes.remove(name)
        }
    }

    private fun removeAllNodesFromView() {
        GlobalScope.launch(Dispatchers.JavaFx) {
            viewData.nodes.clear()
        }
    }

    private fun rebindView() {
        GlobalScope.launch(Dispatchers.JavaFx) {
            updateCallback.invoke()
        }
    }

}