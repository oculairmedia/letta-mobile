package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Subagent RPC client bridging [ServerFrame] subagent requests over [adminRpc].
 */
internal class IrohSubagentRpcClient(
    private val readyHandle: suspend () -> IrohConnectionHandle,
    private val currentScope: () -> SubagentRpcScope?,
    private val adminRpc: suspend (method: String, path: String, body: String?) -> AppServerInboundFrame.AdminRpcResponse,
) {
    suspend fun sendSubagentList(all: Boolean, timeoutMs: Long): ServerFrame.SubagentListResponse {
        val requestId = "iroh-subagent-list-${UUID.randomUUID()}"
        val scope = currentScope()
            ?: return subagentListFailure(requestId, "subagent scope unavailable; hydrate a conversation first")
        return invokeScopedRpc(
            requestId = requestId,
            timeoutMs = timeoutMs,
            unsupported = SUBAGENT_RPC_UNSUPPORTED,
            timedOut = "subagent.list timed out",
            failed = "subagent.list failed",
            call = {
                callScopedSubagentRpc(
                    method = "subagent.list",
                    scope = scope,
                    body = buildJsonObject { put("all", all) }.toString(),
                )
            },
            mapSuccess = { result ->
                val decoded = json.decodeFromJsonElement<SubagentListRpcResult>(result)
                ServerFrame.SubagentListResponse(
                    id = frameId("subagent_list"),
                    ts = nowIso(),
                    requestId = requestId,
                    success = true,
                    subagents = decoded.subagents,
                )
            },
            onFailure = ::subagentListFailure,
        )
    }

    suspend fun sendSubagentTodos(toolCallId: String, timeoutMs: Long): ServerFrame.SubagentTodosResponse {
        val requestId = "iroh-subagent-todos-${UUID.randomUUID()}"
        val scope = currentScope()
            ?: return subagentTodosFailure(requestId, "subagent scope unavailable; hydrate a conversation first")
        return invokeScopedRpc(
            requestId = requestId,
            timeoutMs = timeoutMs,
            unsupported = SUBAGENT_RPC_UNSUPPORTED,
            timedOut = "subagent.todos timed out",
            failed = "subagent.todos failed",
            call = {
                callScopedSubagentRpc(
                    method = "subagent.todos",
                    scope = scope,
                    body = buildJsonObject { put("tool_call_id", toolCallId) }.toString(),
                )
            },
            mapSuccess = { result ->
                val decoded = json.decodeFromJsonElement<SubagentTodosRpcResult>(result)
                ServerFrame.SubagentTodosResponse(
                    id = frameId("subagent_todos"),
                    ts = nowIso(),
                    requestId = requestId,
                    success = true,
                    found = decoded.found,
                    subagent = decoded.subagent,
                    todos = decoded.todos,
                    todosFound = decoded.todosFound,
                )
            },
            onFailure = ::subagentTodosFailure,
        )
    }

    private suspend fun callScopedSubagentRpc(
        method: String,
        scope: SubagentRpcScope,
        body: String,
    ): AppServerInboundFrame.AdminRpcResponse? {
        val handle = readyHandle()
        val advertised = handle.serverCapabilities
        if (advertised != null && SUBAGENT_RPC_CAPABILITY !in advertised) return null
        val scopedBody = buildJsonObject {
            put("conversation_id", scope.conversationId)
            scope.agentId?.let { put("agent_id", it) }
            json.parseToJsonElement(body).jsonObject.forEach { (key, value) -> put(key, value) }
        }.toString()
        val response = adminRpc(method, "/v1/conversations/${scope.conversationId}/subagents", scopedBody)
        return response.takeUnless {
            !it.success && AdminRpcErrors.isUnknownMethod(it.error)
        }
    }

    private suspend fun <T> invokeScopedRpc(
        requestId: String,
        timeoutMs: Long,
        unsupported: String,
        timedOut: String,
        failed: String,
        call: suspend () -> AppServerInboundFrame.AdminRpcResponse?,
        mapSuccess: (JsonElement) -> T,
        onFailure: (String, String) -> T,
    ): T = try {
        withTimeoutOrNull(timeoutMs.milliseconds) {
            val response = call() ?: return@withTimeoutOrNull onFailure(requestId, unsupported)
            if (!response.success) return@withTimeoutOrNull onFailure(requestId, response.error ?: failed)
            val result = response.result ?: return@withTimeoutOrNull onFailure(requestId, "$failed: no result")
            mapSuccess(result)
        } ?: onFailure(requestId, timedOut)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(requestId, error.message ?: failed)
    }

    private fun subagentListFailure(requestId: String, error: String) = ServerFrame.SubagentListResponse(
        id = frameId("subagent_list"), ts = nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun subagentTodosFailure(requestId: String, error: String) = ServerFrame.SubagentTodosResponse(
        id = frameId("subagent_todos"), ts = nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun frameId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
    private fun nowIso(): String = Instant.now().toString()

    @Serializable
    private data class SubagentListRpcResult(val subagents: List<SubagentEntry> = emptyList())

    @Serializable
    private data class SubagentTodosRpcResult(
        val found: Boolean = false,
        val subagent: SubagentEntry? = null,
        val todos: List<SubagentTodo> = emptyList(),
        @SerialName("todos_found") val todosFound: Boolean = false,
    )

    companion object {
        internal const val SUBAGENT_RPC_CAPABILITY = "subagent_registry_v1"
        private const val SUBAGENT_RPC_UNSUPPORTED = "subagent registry is unavailable on this Iroh node"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
