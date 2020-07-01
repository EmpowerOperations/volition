package com.empowerops.volition.ref_oasis

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import java.time.Duration
import java.util.*


sealed class ConfigurationMessage {

    data class RunRequest(val id: UUID): UnaryRequestResponseConfigurationMessage<RunResult>()
    data class UpsertNode(
            val name: String,
            val newName: String?,
            val inputs: List<Input>?,
            val outputs: List<Output>?,
            val autoImport: Boolean?,
            val timeOut: Duration?,
            val inputMapping: Map<String, ParameterName>?,
            val outputMapping: Map<ParameterName, String>?
    ): ConfigurationMessage()

    data class GetNode(
            val name: String
    ): UnaryRequestResponseConfigurationMessage<Simulation?>()

    data class UpdateProblemDef(
            val inputs: List<String>?,
            val outputs: List<String>?,
            val constraints: List<String>?,
            val intermediates: List<String>?,
            val objectives: List<String>?
    ): ConfigurationMessage()
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
    fun make(): ConfigurationActor = scope.actor<ConfigurationMessage>(Dispatchers.Unconfined) {

        for(message in channel) try {
            println("config actor receieved $message!")

            val result = when(message){
                is ConfigurationMessage.RunRequest -> {
                    val result = model.getResult(message.id)

                    message.respondWith(result)
                }
                is ConfigurationMessage.UpsertNode -> {

                    val previousSimulation = model.findSimulationName(message.name)
                            ?: Simulation(message.newName ?: message.name)

                    val newSimulation = previousSimulation.copy(
                            inputs = message.inputs ?: previousSimulation.inputs,
                            outputs = message.outputs ?: previousSimulation.outputs,
                            timeOut = message.timeOut ?: previousSimulation.timeOut,
                            autoImport = message.autoImport ?: previousSimulation.autoImport,
                            inputMapping = message.inputMapping ?: previousSimulation.inputMapping,
                            outputMapping = message.outputMapping ?: previousSimulation.outputMapping
                    )

                    // TODO the responsibilities of model vs this object are not simple,
                    // and we havent gotten into eventing yet.
                    // model service should expose convienience mutator functions,
                    // but the events should be posted here.

                    if(newSimulation != previousSimulation){
                        model.removeSim(previousSimulation.name)
                        model.addSim(newSimulation)
                    }

                    if(message.autoImport == true){
                        val importedSuccessfully = model.autoImport(newSimulation)
                        require(importedSuccessfully)
                    }

                    Unit
                }
                is ConfigurationMessage.GetNode -> {
                    val singleSimulation = model.findSimulationName(message.name)

                    message.respondWith(singleSimulation)
                }
                is ConfigurationMessage.UpdateProblemDef -> {

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