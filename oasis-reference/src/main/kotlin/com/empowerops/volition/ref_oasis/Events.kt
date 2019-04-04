package com.empowerops.volition.ref_oasis

import java.util.*

interface Event
interface ModelEvent : Event
interface OptimizationModelEvent : ModelEvent

open class StatusUpdateEvent(val status: String)                        : Event
data class NewMessageEvent(val message: Message)                        : ModelEvent, StatusUpdateEvent("New message received: $message")
data class NewResultEvent(val result: Result)                           : ModelEvent, StatusUpdateEvent("New result received: $result")

data class PluginUnRegisteredEvent(val name: String)                    : OptimizationModelEvent, StatusUpdateEvent("Plugin $name unregistered")
data class PluginRegisteredEvent(val name: String)                      : OptimizationModelEvent, StatusUpdateEvent("Plugin $name registered")
data class PluginUpdatedEvent(val name: String)                         : OptimizationModelEvent, StatusUpdateEvent("Plugin $name updated")
data class PluginRenamedEvent(val oldName: String, val newName: String) : OptimizationModelEvent, StatusUpdateEvent("Plugin $oldName renamed to $newName")
data class ProxyAddedEvent(val name: String)                            : OptimizationModelEvent, StatusUpdateEvent("Proxy $name added")
data class ProxyRemovedEvent(val name: String)                          : OptimizationModelEvent, StatusUpdateEvent("Proxy $name removed")
data class ProxyRenamedEvent(val oldName: String, val newName: String)  : OptimizationModelEvent, StatusUpdateEvent("Proxy $oldName renamed to $newName")
data class ProxyUpdatedEvent(val name: String)                          : OptimizationModelEvent, StatusUpdateEvent("Proxy $name updated")

data class PausedEvent(val id: UUID)    : StatusUpdateEvent("Run Paused  - ID:$id")
data class RunStartedEvent(val id: UUID): StatusUpdateEvent("Run Started - ID:$id")
data class RunStoppedEvent(val id: UUID): StatusUpdateEvent("Run Stopped - ID:$id")
     class StartRequestedEvent          : StatusUpdateEvent("Run Requested")
     class StopRequestedEvent           : StatusUpdateEvent("Stop Requested")
     class PausedRequestedEvent         : StatusUpdateEvent("Pause Requested")
     class RunResumedEvent              : StatusUpdateEvent("Run Resumed")

class SimulationUpdateRequestedEvent(val name: String)                  : Event