package com.empowerops.volition.ref_oasis

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import java.time.Duration
import java.util.*


sealed class ConfigurationMessage {

    data class RunRequest(val id: UUID): UnaryRequestResponseConfigurationMessage<RunResult>()
    data class UpsertNode(
            val name: String,
            val inputs: List<Input>,
            val outputs: List<Output>,
            val autoImport: Boolean,
            val timeOut: Duration,
            val inputMapping: Map<String, ParameterName>,
            val outputMapping: Map<ParameterName, String>
    ): ConfigurationMessage()

    data class GetNode(
            val name: String
    ): UnaryRequestResponseConfigurationMessage<Simulation?>()
}
abstract class UnaryRequestResponseConfigurationMessage<T>: ConfigurationMessage(), ResponseNeeded<T> {
    val response: CompletableDeferred<T> = CompletableDeferred()
    final override fun respondWith(value: T) { response.complete(value) }
    final override fun failedWith(ex: Throwable) { response.completeExceptionally(ex) }
    final override fun close() {
        response.completeExceptionally(NoSuchElementException("internal error: no result provided by service to $this"))
    }
}

typealias ConfigurationActor = SendChannel<ConfigurationMessage>

class ConfigurationActorFactory(
        val scope: CoroutineScope,
        val model: ModelService
){
    fun make(): ConfigurationActor = scope.actor<ConfigurationMessage> {

        for(message in channel) try {
            println("config actor receieved $message!")

            val result = when(message){
                is ConfigurationMessage.RunRequest -> {
                    val result = model.getResult(message.id)

                    message.respondWith(result)
                }
                is ConfigurationMessage.UpsertNode -> {
                    val simulation = Simulation(
                            message.name,
                            message.inputs,
                            message.outputs,
                            "description of ${message.name}",
                            message.timeOut,
                            message.autoImport,
                            message.inputMapping,
                            message.outputMapping
                    )

                    model.addSim(simulation)

                    if(message.autoImport){
                        val setupSuccess = model.autoSetup(simulation)
                        require(setupSuccess)
                    }

                    Unit
                }
                is ConfigurationMessage.GetNode -> {
                    val singleSimulation = model.findSimulationName(message.name)

                    message.respondWith(singleSimulation)
                }
                is UnaryRequestResponseConfigurationMessage<*> -> TODO()
            }
        }
        catch(ex: Throwable){
            if(message is ResponseNeeded<*>){
                message.failedWith(ex)
            }
        }
        finally {
            if(message is ResponseNeeded<*>){
                message.close()
            }
        }
    }
}