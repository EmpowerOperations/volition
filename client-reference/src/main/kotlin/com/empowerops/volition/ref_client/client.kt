package com.empowerops.volition.ref_client

import com.empowerops.volition.dto.*
import com.google.protobuf.DoubleValue
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import javafx.application.Application
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.value.ObservableBooleanValue
import kotlinx.coroutines.*
import kotlinx.coroutines.javafx.JavaFx
import tornadofx.*

fun main(args: Array<String>){
    Application.launch(SimulatorApp::class.java)
}

class SimulatorApp: App(SimulatorScreen::class)


object Simulator {
    val status: String = "Idle"
    fun onStatusChanged(action: (String) -> Unit): Unit {}

}

object Client {

    //note: https://stackoverflow.com/questions/51284460/go-c-grpc-client-channel-and-stub-lifecycle
    // this object will automagically-re-connect if the server is restarted.
    // of course, the `register` stream will be terminated...
    // yeah, any open streams will have `onError` called when one side disconnects.
    private val service: OptimizerGrpc.OptimizerStub = run {
        val channel = ManagedChannelBuilder
                .forAddress("localhost", 5550)
                .usePlaintext()
                .build()

        val another = OptimizerGrpc.newBlockingStub(channel)

        OptimizerGrpc.newStub(channel)
    }

    private var previousResult = 42.0

    private val registeredProp = SimpleBooleanProperty(false)
    var registered: Boolean by property { registeredProp }; private set
    fun registeredProperty(): ObservableBooleanValue = registeredProp

    private val optimizingProp = SimpleBooleanProperty(false)
    var optimizing: Boolean by property { optimizingProp }; private set
    fun optimizingProperty(): ObservableBooleanValue = optimizingProp

    private val simulatingProp = SimpleBooleanProperty(false)
    var simulating: Boolean by property { simulatingProp }; private set
    fun simulatingProperty(): ObservableBooleanValue = simulatingProp

    suspend fun register() = withContext(Dispatchers.JavaFx) {

        try {
            val channel = wrapToServerSideChannel(service::registerRequest, makeRegisterRequest())
            registered = true

            //yeah so, this is an excellent demonstration of why coroutines matter..
            try {
                for (workRequest in channel) {
                    optimizing = true

                    val result = try {
                        simulating = true
                        makeWorkResult()
                    }
                    finally {
                        simulating = false
                    }

                    val response = wrapToSuspend(service::offerSimulationResult, result)
                }
            }
            finally {
                optimizing = false
            }
        }
        finally {
            registered = false
        }
    }

    suspend fun addNode() {
        wrapToSuspend(service::updateNode, makeUpdateRequest())
    }

    suspend fun startOptimization(){
        wrapToSuspend(service::startOptimization, makeOptimizationRequest())
    }

    private fun makeUpdateRequest() = NodeStatusCommandOrResponseDTO.newBuilder()
                .addInputs(NodeStatusCommandOrResponseDTO.PrototypeInputParameter.newBuilder()
                        .setName("first_var")
                        .setLowerBound(-6.0)
                        .setUpperBound(+6.0)
                )
                .addOutputs(NodeStatusCommandOrResponseDTO.PrototypeOutputParameter.newBuilder()
                        .setName("output")
                )
                .build()

    private fun makeOptimizationRequest() = StartOptimizationCommandDTO.newBuilder()
            .build()

    private fun makeRegisterRequest() = RequestRegistrationCommandDTO.newBuilder()
            .setName("cansys")
            .build()

    private fun makeWorkResult() = SimulationResponseDTO.newBuilder()
            .setName("cansys")
            .putAllOutputVector(mapOf("output" to previousResult--))
            .build()
}

class SimulatorController(): Controller() {

    val simulator = Simulator
    val client = Client
}

class SimulatorScreen(): View("CANSYS simulator") {

    val controller: SimulatorController by inject()

    override val root = borderpane {
        center {
            vbox {
                prefWidth = 400.0
                prefHeight = 400.0
            }
        }
        right {
            form{
                fieldset {

                    field("registered:"){
                        label(controller.client.registeredProperty())
                    }
                    button("Register"){
                        action { GlobalScope.launch(Dispatchers.JavaFx) { controller.client.register() } }
                    }
                    field("optimizing:"){
                        label(controller.client.optimizingProperty())
                    }
                    button("Start Optimization"){
                        action { GlobalScope.launch(Dispatchers.JavaFx) { controller.client.startOptimization() } }
                    }
                }
            }
        }
    }
}
