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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
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
    suspend fun sendSubagentList(all: Boolean, timeoutMs: Long): ServerFrame.SubagentListResponse =
        executeSubagentOp<SubagentListRpcResult, ServerFrame.SubagentListResponse>(
            method = "subagent.list",
            tag = "subagent_list",
            payload = buildJsonObject { put("all", all) },
            timeoutMs = timeoutMs,
            onSuccess = { id, ts, reqId, decoded ->
                ServerFrame.SubagentListResponse(id = id, ts = ts, requestId = reqId, success = true, subagents = decoded.subagents)
            },
            onFailure = { id, ts, reqId, error ->
                ServerFrame.SubagentListResponse(id = id, ts = ts, requestId = reqId, success = false, error = error)
            },
        )

    suspend fun sendSubagentTodos(toolCallId: String, timeoutMs: Long): ServerFrame.SubagentTodosResponse =
        executeSubagentOp<SubagentTodosRpcResult, ServerFrame.SubagentTodosResponse>(
            method = "subagent.todos",
            tag = "subagent_todos",
            payload = buildJsonObject { put("tool_call_id", toolCallId) },
            timeoutMs = timeoutMs,
            onSuccess = { id, ts, reqId, decoded ->
                ServerFrame.SubagentTodosResponse(
                    id = id,
                    ts = ts,
                    requestId = reqId,
                    success = true,
                    found = decoded.found,
                    subagent = decoded.subagent,
                    todos = decoded.todos,
                    todosFound = decoded.todosFound,
                )
            },
            onFailure = { id, ts, reqId, error ->
                ServerFrame.SubagentTodosResponse(id = id, ts = ts, requestId = reqId, success = false, error = error)
            },
        )

    private suspend inline fun <reified R, T> executeSubagentOp(
        method: String,
        tag: String,
        payload: JsonObject,
        timeoutMs: Long,
        crossinline onSuccess: (id: String, ts: String, reqId: String, decoded: R) -> T,
        crossinline onFailure: (id: String, ts: String, reqId: String, error: String) -> T,
    ): T {
        val reqId = "iroh-$tag-${UUID.randomUUID()}"
        val frameId = "$tag-${UUID.randomUUID()}"
        val ts = Instant.now().toString()

        val scope = currentScope()
            ?: return onFailure(frameId, ts, reqId, "subagent scope unavailable; hydrate a conversation first")

        return try {
            withTimeoutOrNull(timeoutMs.milliseconds) {
                val response = callScopedSubagentRpc(method, scope, payload)
                    ?: return@withTimeoutOrNull onFailure(frameId, ts, reqId, SUBAGENT_RPC_UNSUPPORTED)
                if (!response.success) return@withTimeoutOrNull onFailure(frameId, ts, reqId, response.error ?: "$method failed")
                val result = response.result ?: return@withTimeoutOrNull onFailure(frameId, ts, reqId, "$method failed: no result")
                val decoded = json.decodeFromJsonElement<R>(result)
                onSuccess(frameId, ts, reqId, decoded)
            } ?: onFailure(frameId, ts, reqId, "$method timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onFailure(frameId, ts, reqId, error.message ?: "$method failed")
        }
    }

    private suspend fun callScopedSubagentRpc(
        method: String,
        scope: SubagentRpcScope,
        payload: JsonObject,
    ): AppServerInboundFrame.AdminRpcResponse? {
        val handle = readyHandle()
        val advertised = handle.serverCapabilities
        if (advertised != null && SUBAGENT_RPC_CAPABILITY !in advertised) return null
        val scopedBody = buildJsonObject {
            put("conversation_id", scope.conversationId)
            scope.agentId?.let { put("agent_id", it) }
            payload.forEach { (key, value) -> put(key, value) }
        }.toString()
        val response = adminRpc(method, "/v1/conversations/${scope.conversationId}/subagents", scopedBody)
        return response.takeUnless {
            !it.success && AdminRpcErrors.isUnknownMethod(it.error)
        }
    }

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
