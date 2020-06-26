package com.empowerops.volition.ref_oasis.front_end

import com.empowerops.volition.ref_oasis.*
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
import kotlinx.coroutines.*
import kotlinx.coroutines.javafx.JavaFx
import tornadofx.*
import java.time.Duration

class OptimizerController {
    /**
     * This is backing view object for the tree table view, Not intended to use for any data model
     */
    internal class Parameter(
            val name: String,
            val type: Type,
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
    @FXML lateinit var resultTableView: TableView<EvaluationResult>
    @FXML lateinit var messageSenderColumn: TableColumn<Message, String>
    @FXML lateinit var timeColumn: TableColumn<Message, String>
    @FXML lateinit var messageColumn: TableColumn<Message, String>
    @FXML lateinit var resultSenderColumn: TableColumn<EvaluationResult, String>
    @FXML lateinit var typeColumn: TableColumn<EvaluationResult, String>
    @FXML lateinit var inputColumn: TableColumn<EvaluationResult, String>
    @FXML lateinit var outputColumn: TableColumn<EvaluationResult, String>
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
    @FXML lateinit var startButton: Button

    private val list: ObservableList<String> = FXCollections.observableArrayList()
    private val messageList: ObservableList<Message> = FXCollections.observableArrayList()
    private val resultList: ObservableList<EvaluationResult> = FXCollections.observableArrayList()
    private val currentEvaluationStatus = SimpleStringProperty()
    private val issuesText = SimpleStringProperty()
    private lateinit var modelService: ModelService
    private lateinit var inputRoot: TreeItem<Parameter>
    private lateinit var outputRoot: TreeItem<Parameter>

    enum class Type {
        Input, Output, Root
    }

    private fun buildTree(config: Simulation?): TreeItem<Parameter> {
        val root = TreeItem<Parameter>(Parameter("root", Type.Root))
        inputRoot = TreeItem(Parameter("Inputs", Type.Root))
        outputRoot = TreeItem(Parameter("Outputs", Type.Root))
        if (config == null) return root

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

    @FXML
    fun initialize() {
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
            val simulation = modelService.findSimulationName(nodesList.selectedItem!!)
            if (event.code == KeyCode.ESCAPE) {
                //discard
                timeOutTextField.text = simulation!!.timeOut?.toMillis()?.toString() ?: ""
                view.requestFocus()
            } else if (event.code == KeyCode.ENTER) {
                //commit
                val duration = timeOutTextField.text.toLongOrNull()
                if (duration == null) {
                    timeOutTextField.text = ""
                } else {
                    modelService.updateSimulation(nodesList.selectedItem!!) { sim -> sim.copy(
                            timeOut = Duration.ofMillis(duration)
                    )}
                }
                view.requestFocus()
            }
        }

        useTimeout.setOnAction {
            val selectedItem = nodesList.selectedItem!!
            if (useTimeout.isSelected) {
                modelService.updateSimulation(selectedItem) { sim -> sim.copy(
                        timeOut = Duration.ZERO
                )}
                timeOutTextField.text = "0"
            } else {
                modelService.updateSimulation(selectedItem) { sim -> sim.copy(
                        timeOut = null
                )}
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
        typeColumn.setCellValueFactory { dataFeatures -> StringConstant.valueOf(dataFeatures.value.getTypeDisplayString()) }
        inputColumn.setCellValueFactory { dataFeatures -> StringConstant.valueOf(dataFeatures.value.inputs.toString()) }
        outputColumn.setCellValueFactory { dataFeatures -> StringConstant.valueOf(dataFeatures.value.getResultDisplay()) }
    }


    private fun EvaluationResult.getTypeDisplayString() = when (this) {
        is EvaluationResult.Success -> "Success"
        is EvaluationResult.TimeOut -> "Timeout"
        is EvaluationResult.Failed -> "Failed"
        is EvaluationResult.Error -> "Error"
        is EvaluationResult.Terminated -> "Terminated"
    }

    private fun EvaluationResult.getResultDisplay() = when (this) {
        is EvaluationResult.Success -> result.toString()
        is EvaluationResult.TimeOut -> "Timed out: N/A"
        is EvaluationResult.Failed -> "Evaluation Failed: \n$exception"
        is EvaluationResult.Error -> "Error:\n$exception"
        is EvaluationResult.Terminated -> "Stopped by request"
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

    private fun generateAndUpdateNewProxy(): Boolean {
        val selectedItem = nodesList.selectionModel.selectedItem

        if (selectedItem == null) {
            return false
        }
        else {
            modelService.updateSimulation(selectedItem){ sim -> sim.copy(
                inputs = inputRoot.children.map { uiParam ->
                    Input(
                            uiParam.value.name,
                            uiParam.value.lowerBound ?: Double.NaN,
                            uiParam.value.upperBound ?: Double.NaN,
                            0.0
                    )
                }
            )}

            return true
        }
    }

    private fun showNode(newV: String?) {
        selectedNodeInfoBox.isDisable = newV == null
        if(newV != null) displayConfiguration(modelService.findSimulationName(newV))
    }

    private fun displayConfiguration(proxy: Simulation?) {
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

    fun attachToModel(
            modelService: ModelService,
            eventBus: EventBus,
            connectionView: ListView<String>
    ) {
        this.modelService = modelService
        eventBus.register(this)
        connectionListContainer.children += connectionView
        showNode(null)
        rebindViewToState(TODO())
    }

    @FXML fun startStopClicked() = GlobalScope.launch(Dispatchers.JavaFx) {
        TODO()
//        when (runStateMachine.currentState){
//            State.Idle -> {
//                val startResult = CompletableDeferred<RunStateMachine.StartResult>()
//                runStateMachine.start(startResult)
//                when(startResult.await()){
//                    is RunStateMachine.StartResult.Success -> {
//                        //NOOP
//                    }
//                    is RunStateMachine.StartResult.Failed -> {
//                        val alert = Alert(Alert.AlertType.ERROR)
//                        alert.title = "Error"
//                        alert.headerText = "Can not start"
//                        alert.contentText = buildStartIssuesMessage(modelService.findIssues())
//                        alert.showAndWait()
//                    }
//                }
//            }
//            State.Running, State.PausePending, State.Paused -> runStateMachine.stop()
//            State.StopPending -> runStateMachine.forceStop()
//            else -> throw IllegalStateException("Start/Stop Button is not an actionable state. Current State:${runStateMachine.currentState}")
//        }
    }

    @FXML fun pauseResumeRun() = GlobalScope.launch(Dispatchers.JavaFx) {
//        when (runStateMachine.currentState){
//            State.Running -> runStateMachine.pause()
//            State.Paused -> runStateMachine.resume()
//            else -> throw IllegalStateException("Pause/Resume Button is not an actionable state. Current State:${startButton.text}")
//        }
    }

    @FXML fun removeSelectedSetup() = GlobalScope.launch {
        val selectedItem = nodesList.selectedItem
        if (selectedItem != null) modelService.removeSim(selectedItem)
    }

    @Subscribe
    fun onNewResultAsync(event: NewResultEvent) = GlobalScope.launch(Dispatchers.JavaFx) {
        resultList.add(event.result)
    }

    @Subscribe
    fun onStateChangeAsync(event: StatusUpdateEvent) = GlobalScope.launch(Dispatchers.JavaFx) {
        currentEvaluationStatus.value = event.status
    }

    @Subscribe
    fun onNewMessageAsync(event: NewMessageEvent) = GlobalScope.launch(Dispatchers.JavaFx) {
        messageList.add(event.message)
    }

    @Subscribe
    fun whenIssueUpdatedAsync(event: OptimizationModelEvent) = GlobalScope.launch(Dispatchers.JavaFx) {
        val issueList = modelService.findIssues()
        if (issueList.isNotEmpty()) {
            issuesText.set(issueList.joinToString("\n"))
        } else {
            issuesText.set(null)
        }
    }

    @Subscribe
    fun onStateChangedAsync(event : StatusUpdateEvent) = GlobalScope.launch(Dispatchers.JavaFx){
//        rebindViewToState(runStateMachine.currentState)
    }

    private fun rebindViewToState(currentState: State) {
        TODO()
//        val buttonState = when (currentState) {
//            State.Idle -> Idle
//            State.StartPending -> Starting
//            State.Running -> Running
//            State.PausePending -> Pausing
//            State.Paused -> Paused
//            State.StopPending -> Stopping
//            State.ForceStopPending -> ForceStopping
//        }
//
//        startButton.text = buttonState.start
//        startButton.isDisable = buttonState.startDisabled
//        pauseButton.text = buttonState.pause
//        pauseButton.isDisable = buttonState.pauseDisabled
    }

    enum class ButtonState(
            val start: String,
            val pause: String,
            val startDisabled : Boolean,
            val pauseDisabled : Boolean
    ) {
        Idle("Start", "Pause", false, true),
        Starting("Starting..", "Pause", true, true),
        Running("Stop", "Pause", false, false),
        Stopping("Stopping...(Force Stop)", "Pause", false, true),
        ForceStopping("ForceStopping...", "Pause", true, true),
        Paused("Stop", "Resume", false, false),
        Pausing("Stop","Pausing...", false, true)
    }
}

