package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.extras.AgentDiscoverySource
import com.letta.mobile.data.controller.extras.DiscoverableAgent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The agent_discover tool can be invoked many times in a single tool-heavy
 * turn (an autonomous agent looping over agent_discover + agent_message_send).
 * Each call re-reads and re-parses every agents/{id}.json file under baseDir,
 * so an uncached listAgents() turns a chatty loop into repeated redundant disk
 * I/O. A short TTL cache keeps a single turn's rapid-fire lookups cheap while
 * still picking up newly-registered agents within a few seconds.
 */
class LocalBackendAgentDiscoverySource(
    private val store: LocalBackendAdminStore,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
) : AgentDiscoverySource {
    private val cacheMutex = Mutex()
    private var cachedAgents: List<DiscoverableAgent>? = null
    private var cachedAtMs: Long = Long.MIN_VALUE

    override suspend fun listAgents(): List<DiscoverableAgent>? {
        cacheMutex.withLock {
            val cached = cachedAgents
            if (cached != null && nowMillis() - cachedAtMs < cacheTtlMs) return cached
        }
        val fresh = (store.listAgentsProjected(limit = null, offset = 0) as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.toDiscoverableAgent() }
            ?: return null
        cacheMutex.withLock {
            cachedAgents = fresh
            cachedAtMs = nowMillis()
        }
        return fresh
    }

    private fun JsonObject.toDiscoverableAgent(): DiscoverableAgent? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val name = this["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: id
        val tags = (this["tags"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val capabilities = (this["capabilities"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        return DiscoverableAgent(
            agentId = id,
            name = name,
            description = this["description"]?.jsonPrimitive?.contentOrNull,
            aliases = tags,
            role = this["role"]?.jsonPrimitive?.contentOrNull,
            capabilities = capabilities,
            available = true,
        )
    }

    private companion object {
        const val DEFAULT_CACHE_TTL_MS = 5_000L
    }
}
