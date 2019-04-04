package com.empowerops.volition.ref_oasis

import com.google.common.eventbus.EventBus
import javafx.fxml.FXMLLoader
import javafx.scene.Parent

class OptimizerGUIRootController (modelService: DataModelService, optimizerService: OptimizerService, eventBus: EventBus){
    val root : Parent
    init {
        val fxmlLoader = FXMLLoader()
        val resourceAsStream = this.javaClass.classLoader.getResourceAsStream("com.empowerops.volition.ref_oasis/OptimizerView.fxml")
        root = fxmlLoader.load<Parent>(resourceAsStream)

        val controller = fxmlLoader.getController<OptimizerController>()
        val connectionView = ConnectionView(modelService, eventBus)
        controller.attachToModel(modelService, eventBus, connectionView.root, optimizerService)
    }
}