package com.empowerops.volition.ref_oasis

import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import com.sun.javafx.binding.StringConstant
import javafx.beans.property.Property
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.fxml.FXML
import javafx.scene.control.*
import javafx.scene.control.cell.TextFieldTreeTableCell
import javafx.scene.control.cell.TreeItemPropertyValueFactory
import javafx.scene.input.KeyCode
import javafx.scene.layout.AnchorPane
import javafx.scene.layout.VBox
import javafx.util.converter.DoubleStringConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import tornadofx.*
import java.time.Duration

class OptimizerController {

    /**
     * This is backing view object for the tree table view, Not intended to use for any data model
     */
    internal class Parameter(
            val name: String,
            val type: OptimizerController.Type,
            val value: Double? = null
    ) {
        var upperBound: Double? by property()
        fun upperBoundProperty(): Property<Double?> = getProperty(Parameter::upperBound)

        var lowerBound: Double? by property()
        fun lowerBoundProperty(): Property<Double?> = getProperty(Parameter::lowerBound)
    }

    @FXML lateinit var view: AnchorPane
    @FXML lateinit var nodesList: ListView<String>
    @FXML lateinit var messageTableView: TableView<Message>
    @FXML lateinit var resultTableView: TableView<Result>
    @FXML lateinit var messageSenderColumn: TableColumn<Message, String>
    @FXML lateinit var timeColumn: TableColumn<Message, String>
    @FXML lateinit var messageColumn: TableColumn<Message, String>
    @FXML lateinit var resultSenderColumn: TableColumn<Result, String>
    @FXML lateinit var typeColumn: TableColumn<Result, String>
    @FXML lateinit var inputColumn: TableColumn<Result, String>
    @FXML lateinit var outputColumn: TableColumn<Result, String>
    @FXML internal lateinit var paramTreeView: TreeTableView<Parameter>
    @FXML internal lateinit var nameColumn: TreeTableColumn<Parameter, String>
    @FXML internal lateinit var valueColumn: TreeTableColumn<Parameter, String>
    @FXML internal lateinit var lbColumn: TreeTableColumn<Parameter, Double>
    @FXML internal lateinit var upColumn: TreeTableColumn<Parameter, Double>
    @FXML lateinit var optimizerStatusLabel: Label
    @FXML lateinit var descriptionLabel: Label
    @FXML lateinit var selectedNodeInfoBox: VBox
    @FXML lateinit var timeOutTextField: TextField
    @FXML lateinit var useTimeout: CheckBox
    @FXML lateinit var connectionListContainer: VBox
    @FXML lateinit var issuesTextArea: TextArea
    @FXML lateinit var pauseButton: Button

    private val list: ObservableList<String> = FXCollections.observableArrayList()
    private val messageList: ObservableList<Message> = FXCollections.observableArrayList()
    private val resultList: ObservableList<Result> = FXCollections.observableArrayList()
    private val currentEvaluationStatus = SimpleStringProperty()
    private val issuesText = SimpleStringProperty()

    private lateinit var endpoint: OptimizerEndpoint
    private lateinit var modelService: DataModelService
    private lateinit var inputRoot: TreeItem<Parameter>
    private lateinit var outputRoot: TreeItem<Parameter>

    enum class Type {
        Input, Output, Root
    }

    private fun buildTree(config: Proxy?): TreeItem<Parameter> {
        val root = TreeItem<Parameter>(Parameter("root", Type.Root))
        inputRoot = TreeItem(Parameter("Inputs", Type.Root))
        outputRoot = TreeItem(Parameter("Outputs", Type.Root))
        if(config==null) return root

        val inputs: List<TreeItem<Parameter>> = config.inputs.map {
            val value = Parameter(it.name, Type.Input, it.currentValue)
            value.lowerBound = it.lowerBound
            value.upperBound = it.upperBound
            TreeItem(value)
        }
        val outputs: List<TreeItem<Parameter>> = config.outputs.map {
            TreeItem(Parameter(it.name, Type.Input))
        }

        inputRoot.isExpanded = true
        outputRoot.isExpanded = true

        inputRoot.children.addAll(inputs)
        outputRoot.children.addAll(outputs)

        root.children.addAll(inputRoot, outputRoot)
        return root
    }

    @FXML fun initialize() {
        nodesList.items = list
        messageTableView.items = messageList
        resultTableView.items = resultList
        optimizerStatusLabel.textProperty().bind(currentEvaluationStatus)
        issuesTextArea.textProperty().bind(issuesText)

        setupMessageTable()
        setupResultTable()
        setupParameterTableTree()

        nodesList.selectionModel.selectedItemProperty().addListener { src, oldV, newV -> showNode(newV) }

        timeOutTextField.setOnKeyPressed { event ->
            if (event.code == KeyCode.ESCAPE) {
                //discard
                timeOutTextField.text = modelService.proxies.single { it.name == nodesList.selectedItem!! }.timeOut!!.toMillis().toString()
                view.requestFocus()
            } else if (event.code == KeyCode.ENTER) {
                //commit
                val duration = timeOutTextField.text.toLongOrNull()
                if (duration == null) {
                    timeOutTextField.text = ""
                } else {
                    modelService.setDuration(nodesList.selectedItem, Duration.ofMillis(duration))
                }
                view.requestFocus()
            }
        }

        useTimeout.selectedProperty().addListener { s, oldV, newV ->
            if (newV) {
                modelService.setDuration(nodesList.selectedItem, Duration.ZERO)
                timeOutTextField.text = "0"
            } else {
                modelService.setDuration(nodesList.selectedItem, null)
                timeOutTextField.text = ""
            }
        }

        timeOutTextField.disableProperty().bind(useTimeout.selectedProperty().not())


    }

    private fun setupMessageTable() {
        messageSenderColumn.setCellValueFactory { dataFeatures -> StringConstant.valueOf(dataFeatures.value.sender) }
        timeColumn.setCellValueFactory { dataFeatures -> StringConstant.valueOf(dataFeatures.value.receiveTime.toString()) }
        messageColumn.setCellValueFactory { dataFeatures -> StringConstant.valueOf(dataFeatures.value.message) }
    }

    private fun setupResultTable() {
        resultSenderColumn.setCellValueFactory { dataFeatures -> StringConstant.valueOf(dataFeatures.value.name) }
        typeColumn.setCellValueFactory { dataFeatures -> StringConstant.valueOf(dataFeatures.value.resultType) }
        inputColumn.setCellValueFactory { dataFeatures -> StringConstant.valueOf(dataFeatures.value.inputs) }
        outputColumn.setCellValueFactory { dataFeatures -> StringConstant.valueOf(dataFeatures.value.outputs) }
    }

    private fun setupParameterTableTree() {
        nameColumn.cellValueFactory = TreeItemPropertyValueFactory<Parameter, String>("name")
        valueColumn.cellValueFactory = TreeItemPropertyValueFactory<Parameter, String>("value")
        lbColumn.cellFactory = TextFieldTreeTableCell.forTreeTableColumn(DoubleStringConverter())
        lbColumn.cellValueFactory = TreeItemPropertyValueFactory<Parameter, Double>("lowerBound")
        upColumn.cellFactory = TextFieldTreeTableCell.forTreeTableColumn(DoubleStringConverter())
        upColumn.cellValueFactory = TreeItemPropertyValueFactory<Parameter, Double>("upperBound")
        lbColumn.setOnEditCommit {
            it.rowValue.value!!.lowerBound = it.newValue
            generateAndUpdateNewProxy()
        }
        upColumn.setOnEditCommit {
            it.rowValue.value!!.upperBound = it.newValue
            generateAndUpdateNewProxy()
        }
    }

    private fun generateAndUpdateNewProxy() : Boolean{
        val selectedItem = nodesList.selectionModel.selectedItem
        if (selectedItem == null) {
            return false
        } else {
            val proxy = modelService.proxies.single { it.name == selectedItem }
            val rebuildInputList: List<Input> = inputRoot.children.map {
                val parameter = it.value
                Input(parameter.name, parameter.lowerBound ?: Double.NaN, parameter.upperBound ?: Double.NaN, 0.0)
            }
            val newProxy = proxy.copy(inputs = rebuildInputList)
            modelService.syncConfiguration(newProxy)
            return true
        }
    }

    private fun showNode(newV: String?) {
        selectedNodeInfoBox.isDisable = newV == null
        displayConfiguration(modelService.proxies.singleOrNull{it.name == newV})
    }

    private fun displayConfiguration(proxy: Proxy?) {
        descriptionLabel.text = proxy?.name
        paramTreeView.root = buildTree(proxy)
        paramTreeView.isShowRoot = false
        if (proxy?.timeOut != null) {
            useTimeout.isSelected = true
            timeOutTextField.text = proxy.timeOut.toMillis().toString()
        } else {
            useTimeout.isSelected = false
            timeOutTextField.text = ""
        }
    }

    fun setData(dataModelService: DataModelService, endpoint: OptimizerEndpoint, eventBus: EventBus, connectionView: ListView<String>) {
        eventBus.register(this)
        modelService = dataModelService
        connectionListContainer.children.add(connectionView)
        this.endpoint = endpoint
        showNode(null)
    }

    @FXML
    fun startRun() = GlobalScope.launch {
        endpoint.startOptimization(RandomNumberOptimizer())
    }

    @FXML
    fun stopRun() = GlobalScope.launch(Dispatchers.JavaFx) {
        endpoint.stopOptimization()
        pauseButton.text = "Pause"
    }

    @FXML
    fun pauseRun() = GlobalScope.launch(Dispatchers.JavaFx) {
        val paused = endpoint.pauseOptimization()
        pauseButton.text = if (paused) "Resume" else "Pause"
    }

    @FXML
    fun cancelAll() = GlobalScope.launch {
        endpoint.cancelAll()
    }

    @FXML
    fun cancelAndStop() = GlobalScope.launch {
        endpoint.cancelAndStop()
    }

    @FXML
    fun removeSelectedSetup() = GlobalScope.launch {
        val selectedItem = nodesList.selectedItem
        if (selectedItem != null) modelService.removeConfiguration(selectedItem)
    }

    @Subscribe fun onNewResult(event: NewResultEvent) = GlobalScope.launch(Dispatchers.JavaFx) {
        resultList.add(event.result)
    }

    @Subscribe fun onStateChange(event: StatusUpdateEvent) = GlobalScope.launch(Dispatchers.JavaFx) {
        currentEvaluationStatus.value = event.status
    }

    @Subscribe fun onNewMessage(event: NewMessageEvent) = GlobalScope.launch(Dispatchers.JavaFx) {
        messageList.add(event.message)
    }

    @Subscribe fun whenProxyAdded(event: ProxyAddedEvent) = GlobalScope.launch(Dispatchers.JavaFx) {
        list.add(event.name)
    }

    @Subscribe fun whenProxyRemoved(event: ProxyRemovedEvent) = GlobalScope.launch(Dispatchers.JavaFx) {
        list.remove(event.name)
    }

    @Subscribe fun whenProxyRenamed(event: ProxyRenamedEvent) = GlobalScope.launch(Dispatchers.JavaFx) {
        list.remove(event.oldName)
        list.add(event.newName)
    }

    @Subscribe fun whenProxyUpdated(event: ProxyUpdatedEvent) = GlobalScope.launch(Dispatchers.JavaFx) {
        nodesList.refresh()
        showNode(null)
        showNode(nodesList.selectedItem)
    }

    @Subscribe fun whenIssueUpdated(event: ModelEvent) = GlobalScope.launch(Dispatchers.JavaFx) {
        val issueList = modelService.findIssue()
        if (issueList.isNotEmpty()) {
            issuesText.set(issueList.joinToString("\n"))
        } else {
            issuesText.set(null)
        }
    }

}