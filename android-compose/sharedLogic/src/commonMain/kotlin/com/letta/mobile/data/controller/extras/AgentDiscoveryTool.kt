package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.capability.Capability
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class DiscoverableAgent(
    val agentId: String,
    val name: String,
    val description: String? = null,
    val aliases: List<String> = emptyList(),
    val role: String? = null,
    val capabilities: List<String> = emptyList(),
    val host: String? = null,
    val available: Boolean = true,
)

fun interface AgentDiscoverySource {
    suspend fun listAgents(): List<DiscoverableAgent>?
}

class AgentDiscoveryTool(
    private val source: AgentDiscoverySource,
) : ExternalTool {
    override val name = "agent_discover"
    override val description =
        "Find Letta agents by name, ID, alias, role, capability, or host. " +
            "Use the canonical agentId returned by this tool with agent_message_send."
    override val capability = Capability.AgentMessaging
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("query", buildJsonObject { put("type", "string") })
            put("capability", buildJsonObject { put("type", "string") })
            put("role", buildJsonObject { put("type", "string") })
            put("host", buildJsonObject { put("type", "string") })
            put("limit", buildJsonObject { put("type", "integer") })
            put("offset", buildJsonObject { put("type", "integer") })
        })
    }

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult {
        val query = input.string("query")?.trim().orEmpty()
        val capability = input.string("capability")?.trim().orEmpty()
        val role = input.string("role")?.trim().orEmpty()
        val host = input.string("host")?.trim().orEmpty()
        val limit = input.int("limit", 20).coerceIn(1, 50)
        val offset = input.int("offset", 0).coerceAtLeast(0)
        val agents = source.listAgents()
            ?: return ExternalToolResult.Error("agent_discovery_unavailable")
        val normalizedQuery = normalize(query)
        val matches = agents.asSequence()
            .filter { capability.isEmpty() || it.capabilities.any { value -> normalize(value) == normalize(capability) } }
            .filter { role.isEmpty() || normalize(it.role.orEmpty()) == normalize(role) }
            .filter { host.isEmpty() || normalize(it.host.orEmpty()) == normalize(host) }
            .mapNotNull { agent -> score(agent, normalizedQuery)?.let { it to agent } }
            .sortedWith(compareByDescending<Pair<Int, DiscoverableAgent>> { it.first }.thenBy { normalize(it.second.name) })
            .map { it.second }
            .toList()
        if (matches.isEmpty()) {
            return ExternalToolResult.Error("agent_not_found")
        }
        val page = matches.drop(offset).take(limit)
        return ExternalToolResult.Success(buildJsonObject {
            put("ok", true)
            put("query", query)
            put("total", matches.size)
            put("offset", offset)
            put("limit", limit)
            put("hasMore", offset + page.size < matches.size)
            put("ambiguous", normalizedQuery.isNotEmpty() && page.size > 1 && page.first().name.equals(query, ignoreCase = true))
            put("agents", buildJsonArray { page.forEach { add(it.toJson()) } })
        }.toString())
    }

    private fun score(agent: DiscoverableAgent, query: String): Int? {
        if (query.isEmpty()) return 0
        val id = normalize(agent.agentId)
        val name = normalize(agent.name)
        val aliases = agent.aliases.map(::normalize)
        return when {
            query == id -> 100
            query == name -> 95
            query in aliases -> 90
            id.contains(query) -> 80
            name.contains(query) -> 70
            aliases.any { query in it } -> 60
            else -> null
        }
    }

    private fun DiscoverableAgent.toJson() = buildJsonObject {
        put("agentId", agentId)
        put("name", name)
        description?.let { put("description", it) }
        put("aliases", buildJsonArray { aliases.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        role?.let { put("role", it) }
        put("capabilities", buildJsonArray { capabilities.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        host?.let { put("host", it) }
        put("available", available)
    }

    private fun normalize(value: String) = value.removePrefix("letta_").lowercase()
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content
    private fun JsonObject.int(key: String, default: Int) = this[key]?.jsonPrimitive?.content?.toIntOrNull() ?: default
}
