package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.extras.AgentDiscoverySource
import com.letta.mobile.data.controller.extras.DiscoverableAgent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

class LocalBackendAgentDiscoverySource(
    private val store: LocalBackendAdminStore,
) : AgentDiscoverySource {
    override suspend fun listAgents(): List<DiscoverableAgent>? =
        (store.listAgentsProjected(limit = null, offset = 0) as? JsonArray)?.mapNotNull { (it as? JsonObject)?.toDiscoverableAgent() }

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
}
