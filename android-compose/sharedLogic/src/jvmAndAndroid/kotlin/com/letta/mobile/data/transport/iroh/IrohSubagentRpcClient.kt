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

/** Subagent RPC client bridging [ServerFrame] subagent requests over [adminRpc]. */
internal class IrohSubagentRpcClient(
    private val readyHandle: suspend () -> IrohConnectionHandle,
    private val currentScope: () -> SubagentRpcScope?,
    private val adminRpc: suspend (method: String, path: String, body: String?) -> AppServerInboundFrame.AdminRpcResponse,
) {
    suspend fun send(request: SubagentRpcRequest): ServerFrame {
        val context = RpcContext(request.tag)
        val scope = currentScope() ?: return request.failure(context, SCOPE_UNAVAILABLE)
        return try {
            withTimeoutOrNull(request.timeoutMs.milliseconds) {
                val response = call(scope, request) ?: return@withTimeoutOrNull request.failure(context, SUBAGENT_RPC_UNSUPPORTED)
                if (!response.success) return@withTimeoutOrNull request.failure(context, response.error ?: "${request.method} failed")
                request.success(context, response.result ?: return@withTimeoutOrNull request.failure(context, "${request.method} failed: no result"))
            } ?: request.failure(context, "${request.method} timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            request.failure(context, error.message ?: "${request.method} failed")
        }
    }

    private suspend fun call(scope: SubagentRpcScope, request: SubagentRpcRequest): AppServerInboundFrame.AdminRpcResponse? {
        val handle = readyHandle()
        if (handle.serverCapabilities?.contains(SUBAGENT_RPC_CAPABILITY) == false) return null
        val body = buildJsonObject {
            put("conversation_id", scope.conversationId)
            scope.agentId?.let { put("agent_id", it) }
            request.body.forEach { (key, value) -> put(key, value) }
        }
        val response = adminRpc(request.method, "/v1/conversations/${scope.conversationId}/subagents", body.toString())
        return response.takeUnless { !it.success && AdminRpcErrors.isUnknownMethod(it.error) }
    }

    internal sealed class SubagentRpcRequest(val method: String, val tag: String, val timeoutMs: Long, val body: JsonObject) {
        abstract fun success(context: RpcContext, result: kotlinx.serialization.json.JsonElement): ServerFrame
        abstract fun failure(context: RpcContext, error: String): ServerFrame

        class List(all: Boolean, timeoutMs: Long) : SubagentRpcRequest("subagent.list", "subagent_list", timeoutMs, buildJsonObject { put("all", all) }) {
            override fun success(context: RpcContext, result: kotlinx.serialization.json.JsonElement): ServerFrame {
                val decoded = json.decodeFromJsonElement<SubagentListRpcResult>(result)
                return ServerFrame.SubagentListResponse(id = context.frameId, ts = context.timestamp, requestId = context.requestId, success = true, subagents = decoded.subagents)
            }
            override fun failure(context: RpcContext, error: String): ServerFrame =
                ServerFrame.SubagentListResponse(id = context.frameId, ts = context.timestamp, requestId = context.requestId, success = false, error = error)
        }

        class Todos(toolCallId: String, timeoutMs: Long) : SubagentRpcRequest("subagent.todos", "subagent_todos", timeoutMs, buildJsonObject { put("tool_call_id", toolCallId) }) {
            override fun success(context: RpcContext, result: kotlinx.serialization.json.JsonElement): ServerFrame {
                val decoded = json.decodeFromJsonElement<SubagentTodosRpcResult>(result)
                return ServerFrame.SubagentTodosResponse(id = context.frameId, ts = context.timestamp, requestId = context.requestId, success = true, found = decoded.found, subagent = decoded.subagent, todos = decoded.todos, todosFound = decoded.todosFound)
            }
            override fun failure(context: RpcContext, error: String): ServerFrame =
                ServerFrame.SubagentTodosResponse(id = context.frameId, ts = context.timestamp, requestId = context.requestId, success = false, error = error)
        }
    }

    internal data class RpcContext(val tag: String) {
        val requestId = "iroh-$tag-${UUID.randomUUID()}"
        val frameId = "$tag-${UUID.randomUUID()}"
        val timestamp = Instant.now().toString()
    }

    @Serializable private data class SubagentListRpcResult(val subagents: List<SubagentEntry> = emptyList())
    @Serializable private data class SubagentTodosRpcResult(
        val found: Boolean = false,
        val subagent: SubagentEntry? = null,
        val todos: List<SubagentTodo> = emptyList(),
        @SerialName("todos_found") val todosFound: Boolean = false,
    )

    private companion object {
        const val SUBAGENT_RPC_CAPABILITY = "subagent_registry_v1"
        const val SUBAGENT_RPC_UNSUPPORTED = "subagent registry is unavailable on this Iroh node"
        const val SCOPE_UNAVAILABLE = "subagent scope unavailable; hydrate a conversation first"
        val json = Json { ignoreUnknownKeys = true }
    }
}
