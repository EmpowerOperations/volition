package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import javafx.beans.property.SimpleStringProperty
import javafx.collections.ObservableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.funktionale.either.Either
import java.time.Duration
import java.time.LocalDateTime
import kotlin.random.Random

class OptimizerEndpoint(val list: ObservableList<String>,
                        val messages: ObservableList<Message>,
                        val currentEvaluationStatus: SimpleStringProperty,
                        val results: ObservableList<Message>
) : OptimizerGrpc.OptimizerImplBase() {

    var simulationsByName: Map<String, Simulation> = emptyMap()
    var stopRequested = false
    private val ErrorValue = Double.POSITIVE_INFINITY

    data class Input(
            val name: String,
            val lowerBound: Double,
            val upperBound: Double,
            val currentValue: Double
    )

    data class Output(
            val name: String
    )

    data class Message(
            val receiveTime: LocalDateTime,
            val sender: String,
            val message: String
    )

    data class Simulation(
            val inputs: List<Input>,
            val outputs: List<Output>,
            val description: String,
            val input: StreamObserver<OASISQueryDTO>,
            val output: Channel<SimulationResponseDTO>,
            val update: Channel<NodeStatusCommandOrResponseDTO>,
            val error: Channel<ErrorResponseDTO>,
            val timeOut: Duration? = null
    )

    override fun changeNodeName(request: NodeNameChangeCommandDTO, responseObserver: StreamObserver<NodeNameChangeResponseDTO>) = responseObserver.consume {
        withContext(Dispatchers.JavaFx) {
            val newNameSimulation = simulationsByName[request.newName]
            val target = simulationsByName[request.oldName]
            if (newNameSimulation == null && target != null) {
                simulationsByName += request.newName to target
                simulationsByName -= request.oldName

                list.remove(request.oldName)
                list.add(request.newName)

                NodeNameChangeResponseDTO.newBuilder().setChanged(true).build()
            } else {
                NodeNameChangeResponseDTO.newBuilder().setChanged(false).build()
            }
        }
    }

    override fun register(request: RegistrationCommandDTO, responseObserver: StreamObserver<OASISQueryDTO>) {
        GlobalScope.launch(Dispatchers.JavaFx) {
            if (request.name in simulationsByName.keys) {
                return@launch
            }
            list.add(request.name)
            simulationsByName += request.name to Simulation(emptyList(), emptyList(), "", responseObserver, Channel(1), Channel(1), Channel(1))
            updateStatusMessage("${request.name} registered")
        }
    }

    fun unregisterAll() {
        GlobalScope.launch(Dispatchers.JavaFx) {
            list.clear()
            simulationsByName.values.forEach { sim ->
                sim.input.onCompleted()
                sim.output.close()
            }
            simulationsByName = emptyMap()
        }
    }

    override fun startOptimization(request: StartOptimizationCommandDTO, responseObserver: StreamObserver<StartOptimizationResponseDTO>) = responseObserver.consume {
        withContext(Dispatchers.JavaFx) {
            startOptimization(RandomNumberOptimizer())
            StartOptimizationResponseDTO.newBuilder().setMessage("Started").setStarted(true).build()
        }
    }

    sealed class SimResult(open val name: String, open val result: Map<String, Double>) {
        data class Success(override val name: String, override val result: Map<String, Double>) : SimResult(name, result)
        data class TimeOut(override val name: String) : SimResult(name, emptyMap())
        data class TimeOutFailure(override val name: String, val exception: String) : SimResult(name, emptyMap())
        data class Failure(override val name: String, val exception: String) : SimResult(name, emptyMap())
    }

    data class Range(val lower : Double, val upper : Double)
    interface ValueFactory{
        fun  getInputs( inputs: List<Input>) : Map<String, Double>
    }

    class RandomNumberOptimizer : ValueFactory{
        override fun getInputs(inputs: List<Input>): Map<String, Double> =
                inputs.associate { it.name to Random.nextDouble(it.lowerBound, it.upperBound) }

    }

    /**
     * Couple things: we are missing the overall pool of configuration, so far, everything synced are base on one node
     * in OASIS, we have our problem definition and everything is mapped to the proxy
     * for the case of tool1 have x1, x2 as input and f3 as output
     * tool2 have x2, x3 f3 as input, and f4 as output will be impossible to setup in the current way though sync
     *
     * However, as a test bed, each evaluation request is tool dependent. You can have different configuration.
     *
     * The question here is when how much information is needed to a overall CFE setup relative to a evaluation. It that a
     * bigger set of information that is need that each individual evaluation. or oasis don't need to know that at all
     *
     */
    suspend fun startOptimization(valueFactory : ValueFactory) = GlobalScope.launch(Dispatchers.JavaFx) {
        stopRequested = false

        for (simName in simulationsByName.keys) {
            syncConfigFor(simName)
        }

        updateStatusMessage("Evaluating:")
        while (canContinue()) {

            for ((simName, sim) in simulationsByName) {
                updateStatusMessage("Evaluating: ${simName}")
                val inputVector = valueFactory.getInputs(sim.inputs)
                val message = OASISQueryDTO.newBuilder()
                        .setEvaluationRequest(OASISQueryDTO.SimulationEvaluationRequest.newBuilder()
                                .setName(simName)
                                .putAllInputVector(inputVector)
                        )
                        .build()

                sim.input.onNext(message)
                val result = select<SimResult> {
                    sim.output.onReceive { it -> SimResult.Success(it.name, it.outputVectorMap) }
                    sim.error.onReceive { it ->
                        SimResult.Failure(it.name, it.exception)
                    }
                    if(sim.timeOut != null){
                        onTimeout(sim.timeOut.toMillis()){
                            //Timed out
                            val message = OASISQueryDTO.newBuilder().setCancelRequest(
                                    OASISQueryDTO.SimulationCancelRequest.newBuilder().setName(simName)
                            ).build()
                            sim.input.onNext(message)
                            select {
                                sim.output.onReceive { it -> SimResult.TimeOut(it.name) }
                                sim.error.onReceive { it -> SimResult.TimeOutFailure(it.name, it.exception) }
                            }
                        }
                    }
                }
                when (result) {
                    is SimResult.Success -> {
                        addMessage(Message(LocalDateTime.now(), result.name, "Evaluation Succeed: Result [${result.result}]"))
                        addResult(Message(LocalDateTime.now(), result.name, "Succeed: Result [${result.result}]"))
                    }
                    is SimResult.Failure -> {
                        addMessage(Message(LocalDateTime.now(), result.name, "Evaluation Failed: Due to\n${result.exception}"))
                        addResult(Message(LocalDateTime.now(), result.name, "Failure: \n${result.exception}"))
                    }
                    is SimResult.TimeOut -> {
                        addMessage(Message(LocalDateTime.now(), result.name, "Evaluation Canceled to timed out after ${sim.timeOut!!.toMillis()} millisecond"))
                        addResult(Message(LocalDateTime.now(), result.name, "Canceled: Timed Out"))
                    }
                    is SimResult.TimeOutFailure -> {
                        addMessage(Message(LocalDateTime.now(), result.name, "Evaluation try to cancel Timed out after ${sim.timeOut!!.toMillis()} millisecond but Failed: Due to\n${result.exception}"))
                        addResult(Message(LocalDateTime.now(), result.name, "Cancellation failed:\n${result.exception}"))
                    }
                }
            }
        }
        updateStatusMessage("Idle")
    }

    fun updateStatusMessage(message : String)  = GlobalScope.launch(Dispatchers.JavaFx) {
        currentEvaluationStatus.value = message
    }

    fun buildErrorResult(outputs: List<String>): Map<String, Double> {
        return outputs.associate { it to ErrorValue }
    }

    fun stopOptimization() {
        stopRequested = true
    }

    fun syncAll() {
        GlobalScope.launch {
            for (simName in simulationsByName.keys) {
                syncConfigFor(simName)
            }
        }
    }

    fun cancelAll() {
        GlobalScope.launch {
            for ((name, sim) in simulationsByName) {
                val message = OASISQueryDTO.newBuilder().setCancelRequest(
                        OASISQueryDTO.SimulationCancelRequest.newBuilder().setName(name)
                ).build()

                sim.input.onNext(message)
            }
        }
    }

    fun cancel(nodeName : String){

    }

    fun setDuration(nodeName : String?, timeOut: Duration?){
        if(nodeName == null || ! simulationsByName.containsKey(nodeName)){
            return
        }
        val node: Simulation = simulationsByName.getValue(nodeName)
        val newNode = node.copy(
                timeOut = timeOut
        )
        simulationsByName += nodeName to newNode
    }

    fun disconnectAll() {
        GlobalScope.launch {
            for ((name, sim) in simulationsByName) {
                try {
                    sim.input.onCompleted()
                } catch (e: StatusRuntimeException) {
                    println("Error when close input for:\n$e")
                }
            }
        }
    }

    fun cancelAndStop() {
        GlobalScope.launch {
            stopOptimization()
            for ((name, sim) in simulationsByName) {
                val message = OASISQueryDTO.newBuilder().setCancelRequest(
                        OASISQueryDTO.SimulationCancelRequest.newBuilder().setName(name)
                ).build()
                sim.input.onNext(message)
            }
        }
    }

    private suspend fun syncConfigFor(simName: String) {
        val sim = simulationsByName.getValue(simName)
        val message = OASISQueryDTO.newBuilder().setNodeStatusRequest(
                OASISQueryDTO.NodeStatusUpdateRequest.newBuilder().setName(simName)
        ).build()

        sim.input.onNext(message)

        val result = select<Either<Simulation, Message>> {
            sim.update.onReceive { it -> Either.Left(updateFromResponse(it)) }
            sim.error.onReceive { it ->
                Either.Right(
                        Message(LocalDateTime.now(), it.name, "Error update node $simName due to ${it.message} :\n${it.exception}")
                )
            }
        }

        if (result.isLeft()) {
            simulationsByName += simName to result.left().get()
        } else {
            addMessage(result.right().get())
        }
    }

    private suspend fun addMessage(message:Message) =  GlobalScope.launch(Dispatchers.JavaFx){
        messages.add(message)
    }

    private suspend fun addResult(result:Message) =  GlobalScope.launch(Dispatchers.JavaFx){
        results.add(result)
    }

    private fun canContinue(): Boolean {
        return !stopRequested
    }


    override fun stopOptimization(request: StopOptimizationCommandDTO, responseObserver: StreamObserver<StopOptimizationResponseDTO>) = responseObserver.consume {
        stopOptimization()
        StopOptimizationResponseDTO.newBuilder().setMessage("Stopped").setStopped(true).build()
    }


    override fun offerSimulationResult(request: SimulationResponseDTO, responseObserver: StreamObserver<SimulationResultConfirmDTO>) = responseObserver.consume {
        val output = simulationsByName[request.name]?.output
        if (output != null) {
            output.offer(request)
        } else {
            throw IllegalStateException("no simulation '${request.name}' or buffer full")
        }
        SimulationResultConfirmDTO.newBuilder().build()
    }

    override fun offerErrorResult(request: ErrorResponseDTO, responseObserver: StreamObserver<ErrorConfirmDTO>) = responseObserver.consume {
        val error = simulationsByName[request.name]?.error
        if (error != null) {
            error.offer(request)
        } else {
            throw IllegalStateException("no simulation '${request.name}' or buffer full")
        }
        ErrorConfirmDTO.newBuilder().build()
    }


    override fun offerSimulationConfig(request: NodeStatusCommandOrResponseDTO, responseObserver: StreamObserver<NodeChangeConfirmDTO>) = responseObserver.consume {
        val update = simulationsByName[request.name]?.update

        if (update != null) {
            update.offer(request)
        } else {
            throw IllegalStateException("no simulation '${request.name}' or buffer full")
        }
        NodeChangeConfirmDTO.newBuilder().build()
    }


    override fun updateNode(request: NodeStatusCommandOrResponseDTO, responseObserver: StreamObserver<NodeChangeConfirmDTO>) = responseObserver.consume {
        val newNode = updateFromResponse(request)
        simulationsByName += request.name to newNode
        updateStatusMessage("${request.name} updated")

        NodeChangeConfirmDTO.newBuilder().setMessage("Node updated with inputs: ${newNode.inputs} outputs: ${newNode.outputs}").build()
    }

    override fun sendMessage(request: MessageCommandDTO, responseObserver: StreamObserver<MessageReponseDTO>) = responseObserver.consume {
        withContext(Dispatchers.JavaFx) {
            println("Message from [${request.name}] : ${request.message}")
            GlobalScope.launch(Dispatchers.JavaFx) {
                messages.add(Message(LocalDateTime.now(), request.name, request.message))
            }
            MessageReponseDTO.newBuilder().build()
        }
    }

    override fun unregister(request: UnRegistrationRequestDTO, responseObserver: StreamObserver<UnRegistrationResponseDTO>) = responseObserver.consume {
        withContext(Dispatchers.JavaFx) {
            var unregistered = false
            if (request.name in simulationsByName.keys) {
                simulationsByName -= request.name
                list -= request.name
                unregistered = true
            }
            UnRegistrationResponseDTO.newBuilder().setUnregistered(unregistered).build()
        }
    }

    private fun updateFromResponse(request: NodeStatusCommandOrResponseDTO): Simulation {
        val existingNode = simulationsByName.getValue(request.name)

        val newNode = existingNode.copy(
                inputs = request.inputsList.map { Input(it.name, it.lowerBound, it.upperBound, it.currentValue) },
                outputs = request.outputsList.map { Output(it.name) },
                description = request.description
        )
        return newNode
    }

}
