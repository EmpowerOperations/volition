package com.empowerops.volition.ref_oasis.front_end

import com.empowerops.volition.ref_oasis.model.ModelService
import com.empowerops.volition.ref_oasis.model.RunResources
import com.empowerops.volition.ref_oasis.optimizer.Actions
import com.empowerops.volition.ref_oasis.optimizer.OptimizerService
import com.google.common.eventbus.EventBus
import javafx.fxml.FXMLLoader
import javafx.scene.Parent

class OptimizerGUIRootController(
        modelService: ModelService,
        optimizerService: OptimizerService,
        eventBus: EventBus,
        actions: Actions) {
    val root: Parent

    init {
        val fxmlLoader = FXMLLoader()
        val resourceAsStream = this.javaClass.classLoader.getResourceAsStream("com.empowerops.volition.ref_oasis/OptimizerView.fxml")
        root = fxmlLoader.load<Parent>(resourceAsStream)

        val controller = fxmlLoader.getController<OptimizerController>()
        val connectionView = ConnectionView(modelService, eventBus)
        controller.attachToModel(modelService, eventBus, connectionView.root, optimizerService, actions)
    }
}