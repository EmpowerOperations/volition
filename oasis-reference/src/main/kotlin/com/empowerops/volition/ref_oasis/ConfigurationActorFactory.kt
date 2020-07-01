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