package com.empowerops.volition.ref_oasis

import javafx.beans.property.SimpleStringProperty
import javafx.collections.ObservableList
import javafx.fxml.FXML
import javafx.scene.control.*
import javafx.scene.control.cell.TreeItemPropertyValueFactory
import javafx.scene.layout.AnchorPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import tornadofx.selectedItem

data class ViewData(
        val nodes: ObservableList<String>,
        val allMessages: ObservableList<OptimizerEndpoint.Message>
)

class OptimizerController {
    @FXML lateinit var view : AnchorPane
    @FXML lateinit var nodesList : ListView<String>
    @FXML lateinit var messageTableView : TableView<OptimizerEndpoint.Message>

    @FXML lateinit var senderColumn : TableColumn<OptimizerEndpoint.Message, String>
    @FXML lateinit var timeColumn : TableColumn<OptimizerEndpoint.Message, String>
    @FXML lateinit var messageColumn : TableColumn<OptimizerEndpoint.Message, String>

    @FXML lateinit var paramTreeView : TreeTableView<Parameter>
    @FXML lateinit var nameColumn : TreeTableColumn<Parameter, String>
    @FXML lateinit var valueColumn : TreeTableColumn<Parameter, String>
    @FXML lateinit var lbColumn : TreeTableColumn<Parameter, String>
    @FXML lateinit var upColumn : TreeTableColumn<Parameter, String>

    @FXML lateinit var endpoint : OptimizerEndpoint
    @FXML lateinit var statusLabel : Label
    @FXML lateinit var descriptionLabel : Label

    enum class Type {
        Input, Output, Root
    }

    data class Parameter(
        val name: String,
        val type: Type,
        val value : Double? = null,
        val lb: Double? = null,
        val ub: Double? = null
    )

    fun buildTree(config: OptimizerEndpoint.Simulation): TreeItem<Parameter> {
        val root = TreeItem<Parameter>(Parameter("root", Type.Root))
        val root1 = TreeItem<Parameter>(Parameter("Inputs", Type.Root))
        val root2 = TreeItem<Parameter>(Parameter("Outputs", Type.Root))

        val inputs: List<TreeItem<Parameter>> = config.inputs.map { it ->
            TreeItem(Parameter(it.name, Type.Input, it.currentValue, it.lowerBound, it.upperBound))
        }
        val outputs: List<TreeItem<Parameter>> = config.outputs.map { it ->
            TreeItem(Parameter(it.name, Type.Input))
        }

        root1.isExpanded = true
        root2.isExpanded = true

        root1.children.addAll(inputs)
        root2.children.addAll(outputs)

        root.children.addAll(root1, root2)
        return root
    }

    @FXML fun initialize() {
        senderColumn.setCellValueFactory { dataFeatures -> SimpleStringProperty(dataFeatures.value.sender) }
        timeColumn.setCellValueFactory { dataFeatures -> SimpleStringProperty(dataFeatures.value.receiveTime.toString()) }
        messageColumn.setCellValueFactory { dataFeatures -> SimpleStringProperty(dataFeatures.value.message)}

        nameColumn.cellValueFactory = TreeItemPropertyValueFactory<Parameter, String>("name")
        valueColumn.cellValueFactory = TreeItemPropertyValueFactory<Parameter, String>("value")
        lbColumn.cellValueFactory = TreeItemPropertyValueFactory<Parameter, String>("lb")
        upColumn.cellValueFactory = TreeItemPropertyValueFactory<Parameter, String>("ub")

        nodesList.selectionModel.selectedItemProperty().addListener { src, oldV, newV ->
            showNode(newV)
        }
    }

    private fun showNode(newV: String?) {
        if(newV == null){
            //disableView
        }
        else{
            //fill in name, status
            val sim = endpoint.simulationsByName.getValue(newV)
            //fill in status
            val buildTree = buildTree(sim)
            paramTreeView.root = buildTree
            paramTreeView.isShowRoot = false
            statusLabel.text = newV
        }
    }

    fun setData(viewData: ViewData, optimizerEndpoint: OptimizerEndpoint){
        nodesList.items = viewData.nodes
        messageTableView.items = viewData.allMessages
        endpoint = optimizerEndpoint
    }

    @FXML fun startRun() = GlobalScope.launch(Dispatchers.JavaFx){
        endpoint.startOptimization()
    }

    @FXML fun stopRun(){
        endpoint.stopOptimization()
    }

    @FXML fun cancelRun(){
        val selectedItem = nodesList.selectedItem
        if(selectedItem != null){
            endpoint.cancel(selectedItem)
        }
    }

    @FXML fun cancelAll(){
        endpoint.cancelAll()
    }

    @FXML fun cancelAndStop(){
        endpoint.cancelAndStop()
    }

    @FXML fun syncAll(){
        endpoint.syncAll()
    }

    @FXML fun refresh(){
        showNode( nodesList.selectionModel.selectedItem)
    }
}