package com.empowerops.volition.ref_oasis

import com.sun.javafx.binding.StringConstant
import javafx.beans.property.SimpleStringProperty
import javafx.collections.ObservableList
import javafx.fxml.FXML
import javafx.scene.control.*
import javafx.scene.control.cell.TreeItemPropertyValueFactory
import javafx.scene.input.KeyCode
import javafx.scene.layout.AnchorPane
import javafx.scene.layout.VBox
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import tornadofx.selectedItem
import java.time.Duration

data class ViewData(
        val nodes: ObservableList<String>,
        val allMessages: ObservableList<Message>,
        val currentEvaluationStatus: SimpleStringProperty,
        val resultList: ObservableList<Result>,
        val updateList : ObservableList<String>){
}

class OptimizerController {
    @FXML lateinit var view : AnchorPane
    @FXML lateinit var nodesList : ListView<String>
    @FXML lateinit var messageTableView : TableView<Message>
    @FXML lateinit var resultTableView : TableView<Result>

    @FXML lateinit var messageSenderColumn : TableColumn<Message, String>
    @FXML lateinit var timeColumn : TableColumn<Message, String>
    @FXML lateinit var messageColumn : TableColumn<Message, String>

    @FXML lateinit var resultSenderColumn : TableColumn<Result, String>
    @FXML lateinit var typeColumn : TableColumn<Result, String>
    @FXML lateinit var inputColumn  : TableColumn<Result, String>
    @FXML lateinit var outputColumn : TableColumn<Result, String>

    @FXML lateinit var paramTreeView : TreeTableView<Parameter>
    @FXML lateinit var nameColumn : TreeTableColumn<Parameter, String>
    @FXML lateinit var valueColumn : TreeTableColumn<Parameter, String>
    @FXML lateinit var lbColumn : TreeTableColumn<Parameter, String>
    @FXML lateinit var upColumn : TreeTableColumn<Parameter, String>


    @FXML lateinit var statusLabel : Label
    @FXML lateinit var optimizerStatusLabel : Label
    @FXML lateinit var descriptionLabel : Label
    @FXML lateinit var selectedNodeInfoBox : VBox
    @FXML lateinit var timeOutTextField : TextField
    @FXML lateinit var useTimeout : CheckBox

    lateinit var control : OptimizerEndpoint
    lateinit var modelService : ModelService

    enum class Type {
        Input, Output, Root
    }

    data class Parameter(
            val name: String,
            val type: Type,
            val value : Double? = null,
            val lowerBound: Double? = null,
            val upperBound: Double? = null
    )

    fun buildTree(config: Simulation): TreeItem<Parameter> {
        val root = TreeItem<Parameter>(Parameter("root", Type.Root))
        val root1 = TreeItem<Parameter>(Parameter("Inputs", Type.Root))
        val root2 = TreeItem<Parameter>(Parameter("Outputs", Type.Root))

        val inputs: List<TreeItem<Parameter>> = config.inputs.map {
            TreeItem(Parameter(it.name, Type.Input, it.currentValue, it.lowerBound, it.upperBound))
        }
        val outputs: List<TreeItem<Parameter>> = config.outputs.map {
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
        messageSenderColumn.setCellValueFactory { dataFeatures ->  StringConstant.valueOf(dataFeatures.value.sender) }
        timeColumn.setCellValueFactory { dataFeatures ->  StringConstant.valueOf(dataFeatures.value.receiveTime.toString()) }
        messageColumn.setCellValueFactory { dataFeatures ->  StringConstant.valueOf(dataFeatures.value.message) }

        resultSenderColumn.setCellValueFactory { dataFeatures ->  StringConstant.valueOf(dataFeatures.value.name) }
        typeColumn.setCellValueFactory { dataFeatures ->  StringConstant.valueOf(dataFeatures.value.resultType) }
        inputColumn.setCellValueFactory { dataFeatures ->  StringConstant.valueOf(dataFeatures.value.inputs) }
        outputColumn.setCellValueFactory { dataFeatures ->  StringConstant.valueOf(dataFeatures.value.outputs) }

        nameColumn.cellValueFactory = TreeItemPropertyValueFactory<Parameter, String>("name")
        valueColumn.cellValueFactory = TreeItemPropertyValueFactory<Parameter, String>("value")
        lbColumn.cellValueFactory = TreeItemPropertyValueFactory<Parameter, String>("lowerBound")
        upColumn.cellValueFactory = TreeItemPropertyValueFactory<Parameter, String>("upperBound")

        nodesList.selectionModel.selectedItemProperty().addListener { src, oldV, newV -> showNode(newV) }

        timeOutTextField.setOnKeyPressed{ event ->
            if(event.code == KeyCode.ESCAPE){
                //discard
                timeOutTextField.text = modelService.simByName.getValue(nodesList.selectedItem!!).timeOut!!.toMillis().toString()
                view.requestFocus()
            }
            else if (event.code == KeyCode.ENTER) {
                //commit
                val duration = timeOutTextField.text.toLongOrNull()
                if(duration == null){
                    timeOutTextField.text = ""
                }
                else{
                    modelService.setDuration(nodesList.selectedItem, Duration.ofMillis(duration))
                }
                view.requestFocus()
            }
        }

        useTimeout.selectedProperty().addListener{s, oldV, newV ->
            if(newV){
                modelService.setDuration(nodesList.selectedItem, Duration.ZERO)
                timeOutTextField.text = "0"
            }
            else{
                modelService.setDuration(nodesList.selectedItem, null)
                timeOutTextField.text = ""
            }
        }

        timeOutTextField.disableProperty().bind(useTimeout.selectedProperty().not())
    }

    private fun showNode(newV: String?) {
        if(newV == null){
            selectedNodeInfoBox.isDisable = true
        }
        else{
            selectedNodeInfoBox.isDisable = false
            val sim = modelService.simByName.getValue(newV)
            descriptionLabel.text = newV
            val buildTree = buildTree(sim)
            paramTreeView.root = buildTree
            paramTreeView.isShowRoot = false
            statusLabel.text = sim.description
            if(sim.timeOut!= null){
                useTimeout.isSelected = true
                timeOutTextField.text = sim.timeOut.toMillis().toString()
            }
            else{
                useTimeout.isSelected = false
                timeOutTextField.text = ""
            }
        }
    }

    fun setData(dataModelService: DataModelService, control: OptimizerEndpoint){
        nodesList.items = dataModelService.viewData.nodes
        messageTableView.items = dataModelService.viewData.allMessages
        resultTableView.items = dataModelService.viewData.resultList
        modelService = dataModelService
        optimizerStatusLabel.textProperty().bind(dataModelService.viewData.currentEvaluationStatus)
        this.control = control
    }

    @FXML fun startRun() = GlobalScope.launch{
        control.startOptimization(RandomNumberOptimizer())
    }

    @FXML fun stopRun()= GlobalScope.launch{
        control.stopOptimization()
    }

    @FXML fun cancelRun()= GlobalScope.launch{
        val selectedItem = nodesList.selectedItem
        if(selectedItem != null){
            control.cancel(selectedItem)
        }
    }

    @FXML fun cancelAll() = GlobalScope.launch{
        control.cancelAll()
    }

    @FXML fun cancelAndStop()= GlobalScope.launch{
        control.cancelAndStop()
    }

    @FXML fun syncAll()= GlobalScope.launch{
        control.syncAll()
    }

    @FXML fun refresh()= GlobalScope.launch{
        val selectedItem = nodesList.selectedItem
        if(selectedItem != null){
            control.updateNode(selectedItem)
        }
    }

    fun rebindView() {
        showNode(nodesList.selectedItem)
    }
}