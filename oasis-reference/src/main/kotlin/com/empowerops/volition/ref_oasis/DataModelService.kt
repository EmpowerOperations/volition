package com.empowerops.volition.ref_oasis

import com.google.common.eventbus.EventBus
import java.time.Duration

interface Event
interface ModelEvent : Event

data class StatusUpdateEvent(val status: String) : Event
data class NewMessageEvent(val message: Message) : Event
data class NewResultEvent(val result: Result) : Event

data class PluginRegisteredEvent(val name: String) : ModelEvent
data class PluginUnRegisteredEvent(val name: String) : ModelEvent
class PluginUpdatedEvent : ModelEvent
data class PluginRenamedEvent(val oldName: String, val newName: String) : ModelEvent
data class ProxyAddedEvent(val name: String) : ModelEvent
data class ProxyRemovedEvent(val name: String) : ModelEvent
data class ProxyRenamedEvent(val oldName: String, val newName: String) : ModelEvent
class ProxyUpdatedEvent : ModelEvent

fun <T : Nameable> List<T>.getValue(name: String) : T = single { it.name == name }
fun <T : Nameable> List<T>.getNamed(name: String?) : T? = singleOrNull { it.name == name }
fun <T : Nameable> List<T>.hasName(name: String?): Boolean = any { it.name == name }
fun <T : Nameable> List<T>.replace(old: T, new: T) : List<T> = this - old + new
fun <T : Nameable> List<T>.getNames(): List<String> = map{it.name}

class DataModelService(private val eventBus: EventBus) {
    var simulations: List<Simulation> = emptyList()
    var proxies: List<Proxy> = emptyList()

    fun setDuration(nodeName: String?, timeOut: Duration?) : Boolean{
        val oldNode = proxies.getNamed(nodeName) ?: return false
        proxies = proxies.replace(oldNode, oldNode.copy(timeOut = timeOut))
        return true
    }

    fun renameSim(newName: String, oldName: String): Boolean {
        val oldSim = simulations.getNamed(oldName) ?: return false
        if (simulations.hasName(newName)) return false
        simulations = simulations.replace (oldSim, oldSim.copy(name = newName))
        eventBus.post(PluginRenamedEvent(oldName, newName))
        renameProxy(newName, oldName)//this action may or may not be true and we don't care
        return true
    }

    private fun renameProxy(newName: String, oldName: String): Boolean {
        val oldProxy = proxies.getNamed(oldName) ?: return false
        if (proxies.hasName(newName)) return false
        proxies = proxies.replace(oldProxy, oldProxy.copy(name = newName))
        eventBus.post(ProxyRenamedEvent(oldName, newName))
        return true
    }

    fun addNewSim(simulation: Simulation) : Boolean {
        if (simulations.hasName(simulation.name)) return false

        simulations += simulation
        eventBus.post(PluginRegisteredEvent(simulation.name))
        return true
    }

    /**
     * try unregister a node and remove it
     */
    fun closeSim(name: String) : Boolean{
        val sim = simulations.getNamed(name) ?: return false
        try {
            sim.input.onCompleted()
        } finally {
            return removeSim(name)
        }
    }

    private fun removeSim(name: String) : Boolean {
        val sim = simulations.getNamed(name) ?: return false
        simulations -= sim
        eventBus.post(PluginUnRegisteredEvent(name))
        return true
    }

    fun updateSim(newSim: Simulation) : Boolean{
        val oldSim = simulations.getNamed(newSim.name) ?: return false
        simulations = simulations.replace(oldSim, newSim)
        eventBus.post(PluginUpdatedEvent())
        return true
    }

    fun addConfiguration(name: String) : Boolean {
        if (proxies.hasName(name)) return false
        proxies += Proxy(name)
        eventBus.post(ProxyAddedEvent(name))
        return true
    }

    /**
     * Match the setup between proxy and its simulation
     */
    fun syncConfiguration(name: String) : Boolean{
        val oldProxy = proxies.getNamed(name) ?: return false
        val sim = simulations.getNamed(name) ?: return false
        proxies = proxies.replace(oldProxy, Proxy(name, sim.inputs, sim.outputs, oldProxy.timeOut))
        eventBus.post(ProxyUpdatedEvent())
        return true
    }

    /**
     * Match the setup between proxy and its simulation
     */
    fun syncConfiguration(newProxy: Proxy) : Boolean{
        val oldProxy = proxies.getNamed(newProxy.name) ?: return false
        proxies = proxies.replace(oldProxy, newProxy)
        eventBus.post(ProxyUpdatedEvent())
        return true
    }

    fun removeConfiguration(name: String) : Boolean{
        val proxy = proxies.getNamed(name) ?: return false
        proxies -= proxy
        eventBus.post(ProxyRemovedEvent(name))
        return true
    }


    fun findIssue(): List<String> {
        var issues = emptyList<String>()
        val proxyNames = proxies.getNames()
        val simNames = simulations.getNames()

        val proxyWithNoMatchingSim = proxyNames - simNames
        if (proxyWithNoMatchingSim.isNotEmpty()) {
            proxyWithNoMatchingSim.forEach {
                issues += "Proxy Missing Simulation: Proxy setup \"$it\" reference to a missing simulation"
            }
        }

        val simWithNoMatchingProxy = simNames - proxyNames
        if (simWithNoMatchingProxy.isNotEmpty()) {
            simWithNoMatchingProxy.forEach {
                issues += "Not used Simulation: Simulation \"$it\" are not used in any proxy setup"
            }
        }

        proxies.forEach { proxy ->
            val name = proxy.name
            val simulation = simulations.getNamed(name)
            if (simulation != null) {
                if (proxy.inputs.map { it.name } != simulation.inputs.map { it.name } || proxy.outputs != simulation.outputs) {
                    issues += "Sync issue: Proxy setup \"$name\" is out of sync with simluaiton \"$name\""
                }
            }
        }
        return issues
    }
}
