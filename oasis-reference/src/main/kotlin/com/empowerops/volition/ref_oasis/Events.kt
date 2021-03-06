package com.empowerops.volition.ref_oasis

import java.util.*

interface Event
interface ModelEvent : Event
interface OptimizationModelEvent : ModelEvent

open class StatusUpdateEvent(val status: String)                        : Event

data class BasicStatusUpdateEvent(val message: String)                  : StatusUpdateEvent(message)
data class NewMessageEvent(val message: Message)                        : ModelEvent, StatusUpdateEvent("New message received: $message")
data class NewResultEvent(val result: EvaluationResult)                 : ModelEvent, StatusUpdateEvent("New result received: $result")

data class PluginUnRegisteredEvent(val name: String)                    : OptimizationModelEvent, StatusUpdateEvent("Plugin $name unregistered")
data class PluginRegisteredEvent(val name: String)                      : OptimizationModelEvent, StatusUpdateEvent("Plugin $name registered")
data class PluginUpdatedEvent(val name: String)                         : OptimizationModelEvent, StatusUpdateEvent("Plugin $name updated")
data class PluginRenamedEvent(val oldName: String, val newName: String) : OptimizationModelEvent, StatusUpdateEvent("Plugin $oldName renamed to $newName")

data class PausedEvent(val id: UUID)              : StatusUpdateEvent("Run Paused  - ID:$id")
data class RunStartedEvent(val id: UUID)          : StatusUpdateEvent("Run Started - ID:$id")
data class RunStoppedEvent(val id: UUID)          : StatusUpdateEvent("Run Stopped - ID:$id")
data class StartRequestedEvent(val id: UUID)      : StatusUpdateEvent("Run Requested - ID:$id")
data class StopRequestedEvent(val id: UUID)       : StatusUpdateEvent("Stop Requested - ID:$id")
data class ForceStopRequestedEvent(val id: UUID)  : StatusUpdateEvent("Force Stop Requested - ID:$id")
data class PausedRequestedEvent(val id: UUID)     : StatusUpdateEvent("Pause Requested - ID:$id")
data class RunResumedEvent(val id: UUID)          : StatusUpdateEvent("Run Resumed - ID:$id")

data class SimulationUpdateRequestedEvent(val name: String)                  : Event