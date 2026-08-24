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

/**
 * Cron RPC client bridging [ServerFrame] cron requests over [adminRpc].
 */
internal class IrohCronRpcClient(
    private val adminRpc: suspend (method: String, path: String, body: String?) -> AppServerInboundFrame.AdminRpcResponse,
) {
    suspend fun sendCronList(agentId: String?, conversationId: String?, timeoutMs: Long): ServerFrame.CronListResponse {
        val requestId = "iroh-cron-list-${UUID.randomUUID()}"
        return cronInvoke(
            op = "cron.list",
            requestId = requestId,
            timeoutMs = timeoutMs,
            body = buildJsonObject {
                agentId?.let { put("agent_id", it) }
                conversationId?.let { put("conversation_id", it) }
            },
            mapSuccess = { result ->
                val decoded = json.decodeFromJsonElement<CronListRpcResult>(result)
                ServerFrame.CronListResponse(id = frameId("cron_list"), ts = nowIso(), requestId = requestId, success = true, tasks = decoded.tasks)
            },
            onFailure = ::cronListFailure,
        )
    }

    suspend fun sendCronAdd(
        agentId: String,
        name: String,
        description: String,
        prompt: String,
        recurring: Boolean,
        cron: String?,
        every: String?,
        at: String?,
        timezone: String?,
        conversationId: String?,
        timeoutMs: Long,
    ): ServerFrame.CronAddResponse {
        val requestId = "iroh-cron-add-${UUID.randomUUID()}"
        return cronInvoke(
            op = "cron.add",
            requestId = requestId,
            timeoutMs = timeoutMs,
            body = buildJsonObject {
                put("agent_id", agentId)
                put("name", name)
                put("description", description)
                put("prompt", prompt)
                put("recurring", recurring)
                cron?.let { put("cron", it) }
                timezone?.let { put("timezone", it) }
                conversationId?.let { put("conversation_id", it) }
                at?.let { put("scheduled_for", it) }
            },
            mapSuccess = { result ->
                val decoded = json.decodeFromJsonElement<CronMutationRpcResult>(result)
                ServerFrame.CronAddResponse(id = frameId("cron_add"), ts = nowIso(), requestId = requestId, success = true, task = decoded.task, warning = decoded.warning)
            },
            onFailure = ::cronAddFailure,
        )
    }

    suspend fun sendCronGet(taskId: String, timeoutMs: Long): ServerFrame.CronGetResponse {
        val requestId = "iroh-cron-get-${UUID.randomUUID()}"
        return cronInvoke(
            op = "cron.get",
            requestId = requestId,
            timeoutMs = timeoutMs,
            body = buildJsonObject { put("task_id", taskId) },
            mapSuccess = { result ->
                val decoded = json.decodeFromJsonElement<CronMutationRpcResult>(result)
                ServerFrame.CronGetResponse(id = frameId("cron_get"), ts = nowIso(), requestId = requestId, success = true, task = decoded.task)
            },
            onFailure = ::cronGetFailure,
        )
    }

    suspend fun sendCronDelete(taskId: String, timeoutMs: Long): ServerFrame.CronDeleteResponse {
        val requestId = "iroh-cron-delete-${UUID.randomUUID()}"
        return cronInvoke(
            op = "cron.delete",
            requestId = requestId,
            timeoutMs = timeoutMs,
            body = buildJsonObject { put("task_id", taskId) },
            mapSuccess = { _ ->
                ServerFrame.CronDeleteResponse(id = frameId("cron_delete"), ts = nowIso(), requestId = requestId, success = true)
            },
            onFailure = ::cronDeleteFailure,
        )
    }

    suspend fun sendCronDeleteAll(agentId: String, timeoutMs: Long): ServerFrame.CronDeleteAllResponse {
        val requestId = "iroh-cron-delete-all-${UUID.randomUUID()}"
        return cronInvoke(
            op = "cron.delete_all",
            requestId = requestId,
            timeoutMs = timeoutMs,
            body = buildJsonObject { put("agent_id", agentId) },
            mapSuccess = { result ->
                val decoded = json.decodeFromJsonElement<CronDeleteAllRpcResult>(result)
                ServerFrame.CronDeleteAllResponse(id = frameId("cron_delete_all"), ts = nowIso(), requestId = requestId, success = true, count = decoded.deleted)
            },
            onFailure = ::cronDeleteAllFailure,
        )
    }

    private suspend fun <T> cronInvoke(
        op: String,
        requestId: String,
        timeoutMs: Long,
        body: JsonObject,
        mapSuccess: (JsonElement) -> T,
        onFailure: (String, String) -> T,
    ): T = try {
        withTimeoutOrNull(timeoutMs.milliseconds) {
            val response = adminRpc(op, CRON_ADMIN_PATH, body.toString())
            if (!response.success) return@withTimeoutOrNull onFailure(requestId, response.error ?: "$op failed")
            val result = response.result ?: return@withTimeoutOrNull onFailure(requestId, "$op failed: no result")
            mapSuccess(result)
        } ?: onFailure(requestId, "$op timed out")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(requestId, error.message ?: "$op failed")
    }

    private fun cronListFailure(requestId: String, error: String) = ServerFrame.CronListResponse(
        id = frameId("cron_list"), ts = nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun cronAddFailure(requestId: String, error: String) = ServerFrame.CronAddResponse(
        id = frameId("cron_add"), ts = nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun cronGetFailure(requestId: String, error: String) = ServerFrame.CronGetResponse(
        id = frameId("cron_get"), ts = nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun cronDeleteFailure(requestId: String, error: String) = ServerFrame.CronDeleteResponse(
        id = frameId("cron_delete"), ts = nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun cronDeleteAllFailure(requestId: String, error: String) = ServerFrame.CronDeleteAllResponse(
        id = frameId("cron_delete_all"), ts = nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun frameId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
    private fun nowIso(): String = Instant.now().toString()

    @Serializable
    private data class CronListRpcResult(val tasks: List<CronTask> = emptyList())

    @Serializable
    private data class CronMutationRpcResult(
        val found: Boolean = false,
        val task: CronTask? = null,
        val warning: String? = null,
    )

    @Serializable
    private data class CronDeleteAllRpcResult(val deleted: Long = 0L)

    companion object {
        private const val CRON_ADMIN_PATH = "/v1/cron"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
