package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentTodo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Authoritative subagent registry backed by the server-local admin shim.
 * Every request carries the parent conversation scope; no client correlation
 * state is consulted.
 */
class HttpSubagentRegistrySource internal constructor(
    private val proxy: AdminProxyClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SubagentRegistrySource {
    override suspend fun list(conversationId: String, includeTerminal: Boolean): List<SubagentEntry> {
        val response = proxy.get(
            AdminPath.shim("v1", "subagents").builder()
                .query("conversation_id", conversationId)
                .query("all", includeTerminal.toString())
                .build(),
        )
        return json.decodeFromJsonElement(SubagentListResponse.serializer(), response).subagents
    }

    override suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot? {
        val response = proxy.get(
            AdminPath.shim("v1", "subagents", toolCallId, "todos").builder()
                .query("conversation_id", conversationId)
                .build(),
        )
        val decoded = json.decodeFromJsonElement(SubagentTodosResponse.serializer(), response)
        if (!decoded.found || decoded.subagent == null) return null
        return SubagentTodosSnapshot(decoded.subagent, decoded.todos, decoded.todosFound)
    }

    companion object {
        const val CAPABILITY = "subagent_registry_v1"

        /** Returns null until the shim explicitly advertises the HTTP contract. */
        fun discover(adminBaseUrl: String): HttpSubagentRegistrySource? {
            val proxy = AdminProxyClient(adminBaseUrl)
            return runCatching {
                val capability = proxy.get(AdminPath.shim("v1", "capabilities").build())
                    .jsonObject[CAPABILITY]?.jsonObject
                val available = capability?.get("available")?.jsonPrimitive?.booleanOrNull == true
                val transport = capability?.get("transport")?.jsonPrimitive?.content
                if (available && transport == "rest") HttpSubagentRegistrySource(proxy) else null
            }.getOrNull()
        }
    }
}

@Serializable
private data class SubagentListResponse(val subagents: List<SubagentEntry> = emptyList())

@Serializable
private data class SubagentTodosResponse(
    val found: Boolean = false,
    val subagent: SubagentEntry? = null,
    val todos: List<SubagentTodo> = emptyList(),
    @kotlinx.serialization.SerialName("todos_found") val todosFound: Boolean = false,
)
