package com.empowerops.volition.ref_oasis

import com.google.common.eventbus.EventBus
import java.time.Duration
import java.util.*

class DataModelService(private val eventBus: EventBus, private val overwriteMode : Boolean) {
    var simulations: List<Simulation> = emptyList()
        private set
    var proxies: List<Proxy> = emptyList()
        private set
    var resultList : Map<UUID, List<EvaluationResult>> = emptyMap()
        private set
    var messageList : List<Message> = emptyList()
        private set

    /**
     * Update the timeout for configuration by name
     */
    fun setDuration(nodeName: String?, timeOut: Duration?) : Boolean{
        val oldNode = proxies.getNamed(nodeName) ?: return false
        proxies = proxies.replace(oldNode, oldNode.copy(timeOut = timeOut))
        eventBus.post(ProxyUpdatedEvent(nodeName!!))
        return true
    }

    /**
     * Rename a simulation and, if possible, its associated configuration
     */
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
        proxies = proxies.replace(oldProxy, oldProxy.copy(name = newName))
        eventBus.post(ProxyRenamedEvent(oldName, newName))
        return true
    }

    /**
     * Add a new simulation
     * will return false if simulation with same name already exist
     */
    fun addSim(simulation: Simulation) : Boolean {
        if (simulations.hasName(simulation.name) && ! overwriteMode) return false
        if(overwriteMode && simulations.hasName(simulation.name)){
            removeSim(simulation.name)
        }
        simulations += simulation
        eventBus.post(PluginRegisteredEvent(simulation.name))
        return true
    }

    /**
     * Try unregister a simulation base on name and remove it regardless the result.
     *
     * This will also set the StreamObserver of request to completed so the holder of that should get notified
     */
    fun closeSim(name: String) : Boolean{
        val sim = simulations.getNamed(name) ?: return false
        try {
            sim.input.onCompleted()
        } finally {
            return removeSim(name)
        }
    }

    /**
     * Remove a simulation base on name. Will return false if requested simulation does not exist
     */
    private fun removeSim(name: String) : Boolean {
        val sim = simulations.getNamed(name) ?: return false
        simulations -= sim
        eventBus.post(PluginUnRegisteredEvent(name))
        return true
    }

    /**
     * Update simulation and, if possible, the associated configuration
     */
    fun updateSimAndConfiguration(newSim: Simulation) : Boolean{
        val oldSim = simulations.getNamed(newSim.name) ?: return false
        simulations = simulations.replace(oldSim, newSim)
        eventBus.post(PluginUpdatedEvent(newSim.name))
        syncConfigurationToSimulation(newSim.name)
        return true
    }

    /**
     * Automatically add the configuration base on existing simulation
     *
     * This will:
     * 1. Remove all the existing configuration
     * 2. Update the existing simulation base on the request
     * 3. Create and add a single configuration associated to the new simulation
     */
    fun autoSetup(newSim: Simulation) : Boolean {
        if(! simulations.hasName(newSim.name)) return false

        proxies.forEach { removeConfiguration(it.name) }
        var result = true
        result = result && updateSimAndConfiguration(newSim)
        result = result && addAndSyncConfiguration(newSim.name)
        return result
    }

    /**
     * Add a new configuration base on exist simulation name
     */
    fun addAndSyncConfiguration(name: String) : Boolean {
        if (proxies.hasName(name) || ! simulations.hasName(name)) return false
        proxies += Proxy(name)
        eventBus.post(ProxyAddedEvent(name))
        syncConfigurationToSimulation(name)
        return true
    }

    /**
     * Refresh the configuration to match to the associated simulation's setup
     */
    private fun syncConfigurationToSimulation(name: String) : Boolean{
        val oldProxy = proxies.getNamed(name) ?: return false
        val sim = simulations.getNamed(name) ?: return false
        proxies = proxies.replace(oldProxy, Proxy(name, sim.inputs, sim.outputs, oldProxy.timeOut))
        eventBus.post(ProxyUpdatedEvent(name))
        return true
    }

    /**
     * Request to retrieve the setup base on the registered connection name
     *
     * Simulation may or may not be updated and the result is up to Plugin Endpoint
     */
    fun updateSimulation(name: String){
        eventBus.post(SimulationUpdateRequestedEvent(name))
    }

    /**
     * Match the setup between proxy and its simulation
     */
    fun updateConfiguration(newProxy: Proxy) : Boolean{
        val oldProxy = proxies.getNamed(newProxy.name) ?: return false
        proxies = proxies.replace(oldProxy, newProxy)
        eventBus.post(ProxyUpdatedEvent(newProxy.name))
        return true
    }

    /**
     * Remove a configuration with name
     */
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

        if(proxyNames.isEmpty()){
            issues += "No proxy setup"
        }
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

    fun addNewResult(runID: UUID, result: EvaluationResult) {
        val results = resultList.getOrElse(runID) { emptyList() }
        resultList += runID to results + result
        eventBus.post(NewResultEvent(result))
    }

    fun addNewMessage(message: Message) {
        messageList += message
        eventBus.post(NewMessageEvent(message))
    }

}
