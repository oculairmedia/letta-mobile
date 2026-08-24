package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/** Cron RPC client bridging [ServerFrame] cron requests over [adminRpc]. */
internal class IrohCronRpcClient(
    private val adminRpc: suspend (method: String, path: String, body: String?) -> AppServerInboundFrame.AdminRpcResponse,
) {
    suspend fun send(request: CronRpcRequest): ServerFrame = when (request) {
        is CronRpcRequest.List -> execute(request, CronListRpcResult.serializer()) { context, result ->
            ServerFrame.CronListResponse(id = context.frameId, ts = context.timestamp, requestId = context.requestId, success = true, tasks = result.tasks)
        }
        is CronRpcRequest.Add -> execute(request, CronMutationRpcResult.serializer()) { context, result ->
            ServerFrame.CronAddResponse(id = context.frameId, ts = context.timestamp, requestId = context.requestId, success = true, task = result.task, warning = result.warning)
        }
        is CronRpcRequest.Get -> execute(request, CronMutationRpcResult.serializer()) { context, result ->
            ServerFrame.CronGetResponse(id = context.frameId, ts = context.timestamp, requestId = context.requestId, success = true, task = result.task)
        }
        is CronRpcRequest.Delete -> execute(request, JsonElement.serializer()) { context, _ ->
            ServerFrame.CronDeleteResponse(id = context.frameId, ts = context.timestamp, requestId = context.requestId, success = true)
        }
        is CronRpcRequest.DeleteAll -> execute(request, CronDeleteAllRpcResult.serializer()) { context, result ->
            ServerFrame.CronDeleteAllResponse(id = context.frameId, ts = context.timestamp, requestId = context.requestId, success = true, count = result.deleted)
        }
    }

    private suspend fun <T> execute(
        request: CronRpcRequest,
        serializer: kotlinx.serialization.KSerializer<T>,
        success: (RpcContext, T) -> ServerFrame,
    ): ServerFrame {
        val context = request.context()
        return try {
            withTimeoutOrNull(request.timeoutMs.milliseconds) {
                val response = adminRpc(request.operation, CRON_ADMIN_PATH, request.body.toString())
                val error = response.error ?: "${request.operation} failed"
                if (!response.success) return@withTimeoutOrNull request.failure(context, error)
                val result = response.result ?: return@withTimeoutOrNull request.failure(context, "$error: no result")
                success(context, json.decodeFromJsonElement(serializer, result))
            } ?: request.failure(context, "${request.operation} timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            request.failure(context, error.message ?: "${request.operation} failed")
        }
    }

    internal sealed class CronRpcRequest(
        val operation: String,
        val timeoutMs: Long,
        val body: JsonObject,
        private val tag: String,
    ) {
        abstract fun failure(context: RpcContext, error: String): ServerFrame

        class List(agentId: String?, conversationId: String?, timeoutMs: Long) : CronRpcRequest(
            "cron.list", timeoutMs, buildJsonObject {
                agentId?.let { put("agent_id", it) }
                conversationId?.let { put("conversation_id", it) }
            }, "cron_list",
        ) {
            override fun failure(context: RpcContext, error: String) =
                ServerFrame.CronListResponse(id = context.frameId, ts = context.timestamp, requestId = context.requestId, success = false, error = error)
        }

        class Add(
            agentId: String, name: String, description: String, prompt: String, recurring: Boolean,
            cron: String?, at: String?, timezone: String?, conversationId: String?, timeoutMs: Long,
        ) : CronRpcRequest("cron.add", timeoutMs, buildJsonObject {
            put("agent_id", agentId); put("name", name); put("description", description); put("prompt", prompt); put("recurring", recurring)
            cron?.let { put("cron", it) }; timezone?.let { put("timezone", it) }; conversationId?.let { put("conversation_id", it) }; at?.let { put("scheduled_for", it) }
        }, "cron_add") {
            override fun failure(context: RpcContext, error: String) =
                ServerFrame.CronAddResponse(id = context.frameId, ts = context.timestamp, requestId = context.requestId, success = false, error = error)
        }

        class Get(taskId: String, timeoutMs: Long) : CronRpcRequest("cron.get", timeoutMs, buildJsonObject { put("task_id", taskId) }, "cron_get") {
            override fun failure(context: RpcContext, error: String) =
                ServerFrame.CronGetResponse(id = context.frameId, ts = context.timestamp, requestId = context.requestId, success = false, error = error)
        }

        class Delete(taskId: String, timeoutMs: Long) : CronRpcRequest("cron.delete", timeoutMs, buildJsonObject { put("task_id", taskId) }, "cron_delete") {
            override fun failure(context: RpcContext, error: String) =
                ServerFrame.CronDeleteResponse(id = context.frameId, ts = context.timestamp, requestId = context.requestId, success = false, error = error)
        }

        class DeleteAll(agentId: String, timeoutMs: Long) : CronRpcRequest("cron.delete_all", timeoutMs, buildJsonObject { put("agent_id", agentId) }, "cron_delete_all") {
            override fun failure(context: RpcContext, error: String) =
                ServerFrame.CronDeleteAllResponse(id = context.frameId, ts = context.timestamp, requestId = context.requestId, success = false, error = error)
        }

        fun context(): RpcContext = RpcContext(tag)
    }

    internal data class RpcContext(val tag: String) {
        val requestId = "iroh-$tag-${UUID.randomUUID()}"
        val frameId = "$tag-${UUID.randomUUID()}"
        val timestamp = Instant.now().toString()
    }

    @Serializable private data class CronListRpcResult(val tasks: List<CronTask> = emptyList())
    @Serializable private data class CronMutationRpcResult(val found: Boolean = false, val task: CronTask? = null, val warning: String? = null)
    @Serializable private data class CronDeleteAllRpcResult(val deleted: Long = 0L)

    private companion object {
        const val CRON_ADMIN_PATH = "/v1/cron"
        val json = Json { ignoreUnknownKeys = true }
    }
}
