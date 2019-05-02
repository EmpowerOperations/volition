package com.empowerops.volition.ref_async_oasis

import com.empowerops.volition.dto.*
import com.google.protobuf.Message
import io.grpc.ServerInterceptors
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.select
import java.io.Closeable
import java.lang.IllegalStateException
import java.util.*

data class RequestResponsePair<T>(val request: T, val response: SendChannel<Message>, val type: AsyncOptimizerEndpoint.ResponseType) where T: Message

class AsyncOptimizerEndpoint(private val channel: Channel<RequestResponsePair<*>> = Channel(Channel.RENDEZVOUS))
    : OptimizerGrpc.OptimizerImplBase(), ReceiveChannel<RequestResponsePair<*>> by channel
{
    enum class ResponseType { Unary, Stream }

    private fun <T: Message> makePair(
            request: T,
            responseObserver: StreamObserver<Message>,
            responseType: ResponseType = ResponseType.Unary
    ) : RequestResponsePair<T> {

        val source = Exception()
        val actor = GlobalScope.actor<Message> {
            try {
                when (responseType) {
                    ResponseType.Unary -> {
                        val responseMessage = receiveOrNull() ?: throw IllegalStateException("no value provided to channel")
                        responseObserver.onNext(responseMessage)
                    }
                    ResponseType.Stream -> consumeEach(responseObserver::onNext)
                }
            }
            catch(ex: Exception){
                source.initCause(ex)
                ex.printStackTrace()
                responseObserver.onError(ex)
            }
            finally {
                responseObserver.onCompleted()
            }
        }
        return RequestResponsePair(request, actor, responseType)
    }

    inline fun <reified T: Message> StreamObserver<T>.asRuntimeChecked() = object: StreamObserver<Message> {
        override fun onNext(value: Message) {
            require(value is T) { "server generated response $value (type=${value::class.simpleName}) was not of correct response type ${T::class.simpleName}" }
            this@asRuntimeChecked.onNext(value)
        }

        override fun onError(t: Throwable?) = this@asRuntimeChecked.onError(t)
        override fun onCompleted() = this@asRuntimeChecked.onCompleted()
    }

    override fun registerRequest(request: RequestRegistrationCommandDTO, responseObserver: StreamObserver<RequestQueryDTO>) =
             runBlocking<Unit> { channel.send(makePair(request, responseObserver.asRuntimeChecked(), ResponseType.Stream)) }
    override fun unregisterRequest(request: RequestUnRegistrationRequestDTO, responseObserver: StreamObserver<UnRegistrationResponseDTO>) =
             runBlocking<Unit> { channel.send(makePair(request, responseObserver.asRuntimeChecked())) }
    override fun startOptimization(request: StartOptimizationCommandDTO, responseObserver: StreamObserver<StartOptimizationResponseDTO>) =
             runBlocking<Unit> { channel.send(makePair(request, responseObserver.asRuntimeChecked())) }
    override fun stopOptimization(request: StopOptimizationCommandDTO, responseObserver: StreamObserver<StopOptimizationResponseDTO>) =
             runBlocking<Unit> { channel.send(makePair(request, responseObserver.asRuntimeChecked())) }
    override fun offerSimulationResult(request: SimulationResponseDTO, responseObserver: StreamObserver<SimulationResultConfirmDTO>) =
             runBlocking<Unit> { channel.send(makePair(request, responseObserver.asRuntimeChecked())) }
    override fun offerSimulationConfig(request: NodeStatusCommandOrResponseDTO, responseObserver: StreamObserver<NodeChangeConfirmDTO>) =
             runBlocking<Unit> { channel.send(makePair(request, responseObserver.asRuntimeChecked())) }
    override fun offerErrorResult(request: ErrorResponseDTO, responseObserver: StreamObserver<ErrorConfirmDTO>) =
             runBlocking<Unit> { channel.send(makePair(request, responseObserver.asRuntimeChecked())) }
    override fun sendMessage(request: MessageCommandDTO, responseObserver: StreamObserver<MessageResponseDTO>) =
             runBlocking<Unit> { channel.send(makePair(request, responseObserver.asRuntimeChecked())) }
    override fun updateNode(request: NodeStatusCommandOrResponseDTO, responseObserver: StreamObserver<NodeChangeConfirmDTO>) =
             runBlocking<Unit> { channel.send(makePair(request, responseObserver.asRuntimeChecked())) }
    override fun autoConfigure(request: NodeStatusCommandOrResponseDTO, responseObserver: StreamObserver<NodeChangeConfirmDTO>) =
             runBlocking<Unit> { channel.send(makePair(request, responseObserver.asRuntimeChecked())) }
    override fun changeNodeName(request: NodeNameChangeCommandDTO, responseObserver: StreamObserver<NodeNameChangeResponseDTO>) =
             runBlocking<Unit> { channel.send(makePair(request, responseObserver.asRuntimeChecked())) }
    override fun updateConfiguration(request: ConfigurationCommandDTO, responseObserver: StreamObserver<ConfigurationResponseDTO>) =
             runBlocking<Unit> { channel.send(makePair(request, responseObserver.asRuntimeChecked())) }
    override fun requestRunResult(request: ResultRequestDTO, responseObserver: StreamObserver<ResultResponseDTO>) =
             runBlocking<Unit> { channel.send(makePair(request, responseObserver.asRuntimeChecked())) }
}


//TODO: sealed classes:
// 1. for resumption of existing actors, this is effectively an Either<Message, ChannelResumed>
// 2. for the select { asyncServer.onReceieve, ui.onReceieve }

sealed class OptimizationRequest
data class APIRequest(val request: Message, val response: SendChannel<Message>, val responseType: AsyncOptimizerEndpoint.ResponseType): OptimizationRequest()

sealed class UIRequest: OptimizationRequest()
object StartOptimization: UIRequest()


sealed class SimulatorMessage
data class NameChange(val newName: String): SimulatorMessage()
data class SimulationClientRegistered(val name: String, val input: SendChannel<RequestQueryDTO>): SimulatorMessage()
data class EvaluationRequest(val inputs: Map<String, Double>, val result: CompletableDeferred<Map<String, Double>>): SimulatorMessage()
data class EvaluationResponse(val outputs: Map<String, Double>): SimulatorMessage()

data class InputParameter(val name: String, val lowerBound: Double, val upperBound: Double, val lastKnownValue: Double)
data class OutputParameter(val name: String, val lastKnownValue: Double)

data class StopCommand(val stopped: CompletableDeferred<Unit> = CompletableDeferred())
data class RunningOptimizationInfo(val id: UUID, val job: SendChannel<StopCommand>)

fun main(args: Array<String>) = runBlocking<Unit>(Dispatchers.Main){

    val logger = object: Logger {
        override fun log(message: String, sender: String) = System.err.println(message)
    }
    val endpoint = AsyncOptimizerEndpoint()
    val server = NettyServerBuilder
            .forPort(5550)
            .addService(ServerInterceptors.intercept(endpoint, LoggingInterceptor(logger)))
            .build()

    val service = OptimizerServiceAsync.createStartedAsync(endpoint, Channel())

    server.start()
    service.awaitTermination()
}

typealias SimulatorProxy = SendChannel<SimulatorMessage>

class OptimizerServiceAsync private constructor(){

    private var clients: Map<String, SimulatorProxy> = emptyMap()
    private var runningOptimization: RunningOptimizationInfo? = null
    private var parameters: List<InputParameter> = emptyList()
    private var outputs: List<OutputParameter> = emptyList()

    private lateinit var job: Job

    companion object {
        fun createStartedAsync(
                asyncServer: AsyncOptimizerEndpoint,
                ui: ReceiveChannel<UIRequest>
        ): OptimizerServiceAsync = OptimizerServiceAsync().also { it.runServerAsync(asyncServer, ui) }
    }

    private fun runServerAsync(
            asyncServer: AsyncOptimizerEndpoint,
            ui: ReceiveChannel<UIRequest>
    ) = GlobalScope.launch {

        while(true){

            System.out.println("processing message...")

            val inbound = select<OptimizationRequest> {
                asyncServer.onReceive { APIRequest(it.request, it.response, it.type) }
                ui.onReceive { it }
            }

            when(inbound){
                is APIRequest -> inbound.closeIfUnaryAfterAfter {
                    val result: Message? = when(val request = inbound.request){
                        is RequestRegistrationCommandDTO -> {
                            val client = findMakeOrResumeSimulationClient(request.name)
                            client.send(SimulationClientRegistered(request.name, inbound.response))

                            null
                        }
                        is StartOptimizationCommandDTO -> {
                            require(runningOptimization == null)
                            val runningID = UUID.randomUUID()
                            val runningOptimizationJob = startOptimizationCoroutineAsync(
                                    parameters, outputs, clients.values.toList()
                            )
                            runningOptimization = RunningOptimizationInfo(runningID, runningOptimizationJob)

                            StartOptimizationResponseDTO.newBuilder().apply { runID = runningID.toString() }.build()
                        }
                        is SimulationResponseDTO -> {
                            clients.getValue(request.name).send(EvaluationResponse(request.outputVectorMap))

                            SimulationResultConfirmDTO.newBuilder().build()
                        }
                        is StopOptimizationCommandDTO -> {
                            val optimization = runningOptimization
                            require(optimization != null)
                            val command = StopCommand()
                            optimization.job.send(command)
                            command.stopped.await()

                            StopOptimizationResponseDTO.newBuilder().apply { runID = optimization.id.toString() }.build()
                        }
                        is NodeStatusCommandOrResponseDTO -> {
                            parameters += request.inputsList.map { InputParameter(it.name, it.lowerBound, it.upperBound, it.currentValue) }
                            outputs += request.outputsList.map { OutputParameter(it.name, it.value) }

                            NodeChangeConfirmDTO.newBuilder().apply { message = "OK" }.build()
                        }

                        else -> TODO()
                    }

                    if(result != null){
                        inbound.response.send(result)
                    }
                }
                is UIRequest -> when(inbound){
                    is StartOptimization -> {
                        require(runningOptimization == null)
                        runningOptimization = RunningOptimizationInfo(UUID.randomUUID(), startOptimizationCoroutineAsync(
                                parameters, outputs, clients.values.toList()
                        ))
                    }
                }
            } as Any

        }
    }.also { job = it }

    suspend fun awaitTermination(){ job.join() }

    fun CoroutineScope.findMakeOrResumeSimulationClient(name: String): SendChannel<SimulatorMessage> {
        return clients[name]?.takeIf { !it.isClosedForSend }
                ?: startRegisteredSimulationCoroutineAsync().also { clients += name to it }
    }

    fun CoroutineScope.startOptimizationCoroutineAsync(
            inputs: List<InputParameter>,
            outputs: List<OutputParameter>,
            simulations: List<SimulatorProxy>
    ) = actor<StopCommand> {
        System.out.println("running optimization")
        var command: StopCommand? = null
        val rand = Random()

        while(isActive){
            command = poll()
            if(command != null) break

            val inputVector = inputs.associate { it.name to rand.nextIn(it.lowerBound .. it.upperBound) }

            val results = simulations
                    .map {
                        val result = CompletableDeferred<Map<String, Double>>()
                        it.send(EvaluationRequest(inputVector, result))
                        result
                    }
                    .flatMap { it.await().entries }
                    .associate { (a, b) -> a to b }

            println("evaluation finished with $results")
        }

        command!!.stopped.complete(Unit)
    }

    fun Random.nextIn(range: ClosedRange<Double>) = nextDouble() * (range.endInclusive - range.start) + range.start

    fun CoroutineScope.startRegisteredSimulationCoroutineAsync() = actor<SimulatorMessage> {
        System.out.println("started simulation coroutine")
        registered@ while(isActive) try {
            val registration = expect<SimulatorMessage, SimulationClientRegistered>()

            val output = registration.input
            var name = registration.name

            while(isActive) {
                val next = receiveOrNull()
                println("coroutine $name is processing $next")

                val x = when (next) {
                    null -> break@registered
                    is NameChange -> {
                        name = next.newName
                        Unit
                    }
                    is EvaluationRequest -> {
                        val nextEvaluation = RequestQueryDTO.newBuilder().apply {
                            evaluationRequest = RequestQueryDTO.SimulationEvaluationRequest.newBuilder().apply {
                                this.name = name
                                putAllInputVector(next.inputs)
                            }.build()
                        }.build()

                        output.send(nextEvaluation)
                        val response = expect<SimulatorMessage, EvaluationResponse>()

                        next.result.complete(response.outputs)

                        Unit
                    }
                    is SimulationClientRegistered -> TODO()
                    is EvaluationResponse -> TODO("illegal state")
                }
            }
        }
        catch(ex: StatusRuntimeException){
            //the GRPC stream died, retry.
            ex.printStackTrace()
            System.err.println("Stream died mid-coroutine, will wait for next connection.")
            continue
        }

    }
}

private inline fun <R> APIRequest.closeIfUnaryAfterAfter(block: () -> R) = when(this.responseType) {
    AsyncOptimizerEndpoint.ResponseType.Unary -> response.closeAfter { block() }
    AsyncOptimizerEndpoint.ResponseType.Stream -> { block() }
}
private inline fun <T, R> SendChannel<T>.closeAfter(block: () -> R) = asCloseable().use { block() }

private fun SendChannel<*>.asCloseable() = Closeable { this@asCloseable.close() }

public inline suspend fun <T: Any, reified E: T> ReceiveChannel<T>.expect(): E {
    val next = receiveOrNull()
    require(next != null){ "protocol voliation: expected inbound message to be an instance of ${E::class.simpleName} but it was null" }
    require(next is E) { "protocol violation: expected $next to be ${E::class.simpleName} but it was actually ${next::class.simpleName}" }
    return next
}