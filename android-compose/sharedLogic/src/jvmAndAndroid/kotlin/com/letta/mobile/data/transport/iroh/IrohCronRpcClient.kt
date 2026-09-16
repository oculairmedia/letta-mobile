package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
 * Cron RPC client bridging [ServerFrame] cron requests over [adminRpc].
 */
internal class IrohCronRpcClient(
    private val adminRpc: suspend (method: String, path: String, body: String?) -> AppServerInboundFrame.AdminRpcResponse,
) {
    suspend fun sendCronList(agentId: String?, conversationId: String?, timeoutMs: Long): ServerFrame.CronListResponse =
        executeCronOp<CronListRpcResult, ServerFrame.CronListResponse>(
            op = "cron.list",
            body = buildJsonObject {
                agentId?.let { put("agent_id", it) }

                conversationId?.let { put("conversation_id", it) }
            },
            timeoutMs = timeoutMs,
            onSuccess = { id, ts, reqId, decoded ->
                ServerFrame.CronListResponse(id = id, ts = ts, requestId = reqId, success = true, tasks = decoded.tasks)
            },
            onFailure = { id, ts, reqId, error ->
                ServerFrame.CronListResponse(id = id, ts = ts, requestId = reqId, success = false, error = error)
            },
        )

    suspend fun sendCronAdd(request: CronAddRequest): ServerFrame.CronAddResponse =
        executeCronOp<CronMutationRpcResult, ServerFrame.CronAddResponse>(
            op = "cron.add",
            body = buildJsonObject {
                put("agent_id", request.agentId)
                put("name", request.name)
                put("description", request.description)
                put("prompt", request.prompt)
                put("recurring", request.recurring)
                request.cron?.let { put("cron", it) }
                request.timezone?.let { put("timezone", it) }
                request.conversationId?.let { put("conversation_id", it) }
                request.at?.let { put("scheduled_for", it) }
            },
            timeoutMs = request.timeoutMs,
            onSuccess = { id, ts, reqId, decoded ->
                ServerFrame.CronAddResponse(id = id, ts = ts, requestId = reqId, success = true, task = decoded.task, warning = decoded.warning)
            },
            onFailure = { id, ts, reqId, error ->
                ServerFrame.CronAddResponse(id = id, ts = ts, requestId = reqId, success = false, error = error)
            },
        )

    suspend fun sendCronGet(taskId: String, timeoutMs: Long): ServerFrame.CronGetResponse =
        executeCronOp<CronMutationRpcResult, ServerFrame.CronGetResponse>(
            op = "cron.get",
            body = buildJsonObject { put("task_id", taskId) },
            timeoutMs = timeoutMs,
            onSuccess = { id, ts, reqId, decoded ->
                ServerFrame.CronGetResponse(id = id, ts = ts, requestId = reqId, success = true, task = decoded.task)
            },
            onFailure = { id, ts, reqId, error ->
                ServerFrame.CronGetResponse(id = id, ts = ts, requestId = reqId, success = false, error = error)
            },
        )

    suspend fun sendCronDelete(taskId: String, timeoutMs: Long): ServerFrame.CronDeleteResponse =
        executeCronOp<Unit, ServerFrame.CronDeleteResponse>(
            op = "cron.delete",
            body = buildJsonObject { put("task_id", taskId) },
            timeoutMs = timeoutMs,
            onSuccess = { id, ts, reqId, _ ->
                ServerFrame.CronDeleteResponse(id = id, ts = ts, requestId = reqId, success = true)
            },
            onFailure = { id, ts, reqId, error ->
                ServerFrame.CronDeleteResponse(id = id, ts = ts, requestId = reqId, success = false, error = error)
            },
        )

    suspend fun sendCronDeleteAll(agentId: String, timeoutMs: Long): ServerFrame.CronDeleteAllResponse =
        executeCronOp<CronDeleteAllRpcResult, ServerFrame.CronDeleteAllResponse>(
            op = "cron.delete_all",
            body = buildJsonObject { put("agent_id", agentId) },
            timeoutMs = timeoutMs,
            onSuccess = { id, ts, reqId, decoded ->
                ServerFrame.CronDeleteAllResponse(id = id, ts = ts, requestId = reqId, success = true, count = decoded.deleted)
            },
            onFailure = { id, ts, reqId, error ->
                ServerFrame.CronDeleteAllResponse(id = id, ts = ts, requestId = reqId, success = false, error = error)
            },
        )

    private suspend inline fun <reified R, T> executeCronOp(
        op: String,
        body: JsonObject,
        timeoutMs: Long,
        crossinline onSuccess: (id: String, ts: String, reqId: String, decoded: R) -> T,
        crossinline onFailure: (id: String, ts: String, reqId: String, error: String) -> T,
    ): T {
        val opTag = op.replace('.', '_')
        val reqId = "iroh-$opTag-${UUID.randomUUID()}"
        val frameId = "$opTag-${UUID.randomUUID()}"
        val ts = Instant.now().toString()

        return try {
            withTimeoutOrNull(timeoutMs.milliseconds) {
                val response = adminRpc(op, CRON_ADMIN_PATH, body.toString())
                if (!response.success) return@withTimeoutOrNull onFailure(frameId, ts, reqId, response.error ?: "$op failed")
                if (R::class == Unit::class) {
                    @Suppress("UNCHECKED_CAST")
                    return@withTimeoutOrNull onSuccess(frameId, ts, reqId, Unit as R)
                }
                val result = response.result ?: return@withTimeoutOrNull onFailure(frameId, ts, reqId, "$op failed: no result")
                val decoded = json.decodeFromJsonElement<R>(result)
                onSuccess(frameId, ts, reqId, decoded)
            } ?: onFailure(frameId, ts, reqId, "$op timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onFailure(frameId, ts, reqId, error.message ?: "$op failed")
        }
    }

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

internal data class CronAddRequest(
    val agentId: String,
    val name: String,
    val description: String,
    val prompt: String,
    val recurring: Boolean,
    val cron: String?,
    val every: String?,
    val at: String?,
    val timezone: String?,
    val conversationId: String?,
    val timeoutMs: Long,
)
