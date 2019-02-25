package com.empowerops.volition.ref_oasis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import java.time.Duration

class DataModelService(
        val viewData: ViewData,
        private val updateCallback: () -> Unit
) : ModelService {
    override var simByName: Map<String, Simulation> = emptyMap()

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

    override fun renameSim(target: Simulation, newName: String, oldName: String) {
        require(simByName.containsKey(oldName) && !simByName.containsKey(newName))
        simByName += newName to target
        simByName -= oldName
        removeNodeFromView(oldName)
        addNodeToView(newName)
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