package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentTodo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/** Authoritative registry access. Implementations must load only the requested conversation. */
interface SubagentRegistrySource {
    suspend fun list(conversationId: String, includeTerminal: Boolean): List<SubagentEntry>

    suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot?
}

@Serializable
data class SubagentTodosSnapshot(
    val subagent: SubagentEntry,
    val todos: List<SubagentTodo> = emptyList(),
    @kotlinx.serialization.SerialName("todos_found") val todosFound: Boolean = false,
)

internal object SubagentAdminHandlers {
    private val json = Json { ignoreUnknownKeys = true }

    fun register(router: AdminRpcRouter, source: SubagentRegistrySource?) {
        router.registerScoped("subagent.list") { params, context ->
            val scope = requireScope(params, context)
            val includeTerminal = param(params, AdminParamKey("all"))?.toBooleanStrictOrNull() ?: false
            val entries = source.requireSource().list(scope.conversationId, includeTerminal)
                .filter { it.belongsTo(scope) }
            json.encodeToJsonElement(SubagentListResult(subagents = entries))
        }
        router.registerScoped("subagent.todos") { params, context ->
            val scope = requireScope(params, context)
            val toolCallId = params.requireParam(AdminParamKey("tool_call_id"))
            val snapshot = source.requireSource().todos(scope.conversationId, toolCallId)
                ?.takeIf { it.subagent.toolCallId == toolCallId && it.subagent.belongsTo(scope) }
            json.encodeToJsonElement(
                if (snapshot == null) {
                    SubagentTodosResult(found = false)
                } else {
                    SubagentTodosResult(
                        found = true,
                        subagent = snapshot.subagent,
                        todos = snapshot.todos,
                        todosFound = snapshot.todosFound,
                    )
                },
            )
        }
    }

    private fun requireScope(params: JsonObject?, context: AdminRpcRequestContext): SubagentRequestScope {
        if (!context.authenticated) adminError("unauthorized")
        val conversationId = params.requireParam(AdminParamKey("conversation_id"))
        if (!context.canAccessConversation(conversationId)) adminError("forbidden")
        return SubagentRequestScope(
            conversationId = conversationId,
            agentId = param(params, AdminParamKey("agent_id"))?.takeIf { it.isNotBlank() },
        )
    }

    private fun SubagentRegistrySource?.requireSource(): SubagentRegistrySource =
        this ?: adminError("subagent registry unavailable")

    private fun SubagentEntry.belongsTo(scope: SubagentRequestScope): Boolean =
        parentConversationId == scope.conversationId &&
            (scope.agentId == null || parentAgentId == scope.agentId)
}

private data class SubagentRequestScope(val conversationId: String, val agentId: String?)

@Serializable
private data class SubagentListResult(val subagents: List<SubagentEntry>)

@Serializable
private data class SubagentTodosResult(
    val found: Boolean,
    val subagent: SubagentEntry? = null,
    val todos: List<SubagentTodo> = emptyList(),
    @kotlinx.serialization.SerialName("todos_found") val todosFound: Boolean = false,
)
