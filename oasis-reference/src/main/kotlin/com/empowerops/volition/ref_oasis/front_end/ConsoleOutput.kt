package com.empowerops.volition.ref_oasis.front_end

import com.empowerops.volition.dto.Logger
import com.empowerops.volition.ref_oasis.Message
import com.empowerops.volition.ref_oasis.StatusUpdateEvent
import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import kotlinx.coroutines.channels.Channel
import java.time.LocalDateTime
import java.util.logging.Level

/**
 * TODO LIST:
 * 1. Configurable level and verbosity
 * 2. Using logging framework for persistence
 * 3. Standard out/error streaming for connected client side (cloud)
 * 4. Log/Logfile retrieval for client side (cloud)
 */
class ConsoleOutput(eventBus: EventBus) : Logger {
    var log: List<Message> = emptyList()
    val outChannel = Channel<String>(Channel.UNLIMITED)

    init {
        eventBus.register(this)
    }

    override fun log(message: String, sender : String) {
        log += Message(sender, message)
        val logMessage = "[${LocalDateTime.now()}] $sender > $message"
        System.out.println(logMessage)
    }

    @Subscribe
    fun onEvent(event : StatusUpdateEvent){
        val message = Message("Optimizer Event", event.toString(), Level.INFO)
        log += message
        val logMessage = "[${LocalDateTime.now()}] > ${event.status}"
        System.out.println(logMessage)
    }
}