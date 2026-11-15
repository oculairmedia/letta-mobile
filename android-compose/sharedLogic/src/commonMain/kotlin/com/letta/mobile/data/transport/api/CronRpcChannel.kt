package com.letta.mobile.data.transport.api

import kotlin.time.Duration
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shared cron transport over the wrapper admin RPC contract; other operations remain unavailable.
 */
@OptIn(ExperimentalUuidApi::class)
open class CronRpcChannel(
    private val rpcCall: suspend (method: String, path: String, body: String?) -> AppServerInboundFrame.AdminRpcResponse,
) : NoOpChannelTransport() {
    override suspend fun adminRpc(method: String, path: String, body: String?) = rpcCall(method, path, body)

    override suspend fun listCrons(query: CronListQuery, timeout: Duration): ServerFrame.CronListResponse =
        executeCronOp<JsonObject, ServerFrame.CronListResponse>(
            op = "cron.list",
            body = buildJsonObject {
                query.agentId?.let { put("agent_id", it) }

                query.conversationId?.let { put("conversation_id", it) }
            },
            timeout = timeout,
            onSuccess = { frame, decoded ->
                decodeFrame<ServerFrame.CronListResponse>(decoded, frame)
            },
            onFailure = { frame, error ->
                ServerFrame.CronListResponse(id = frame.id, ts = frame.ts, requestId = frame.requestId, success = false, error = error)
            },
        )

    override suspend fun addCron(params: CronAddRequest, timeout: Duration): ServerFrame.CronAddResponse =
        executeCronOp<JsonObject, ServerFrame.CronAddResponse>(
            op = "cron.add",
            body = buildJsonObject {
                put("agent_id", params.agentId)
                put("name", params.name)
                put("description", params.description)
                put("prompt", params.prompt)
                put("recurring", params.recurring)
                params.cron?.let { put("cron", it) }
                params.timezone?.let { put("timezone", it) }
                params.conversationId?.let { put("conversation_id", it) }
                params.at?.let { put("scheduled_for", it) }
            },
            timeout = timeout,
            onSuccess = { frame, decoded ->
                decodeFrame<ServerFrame.CronAddResponse>(decoded, frame)
            },
            onFailure = { frame, error ->
                ServerFrame.CronAddResponse(id = frame.id, ts = frame.ts, requestId = frame.requestId, success = false, error = error)
            },
        )

    override suspend fun getCron(task: CronTaskRef, timeout: Duration): ServerFrame.CronGetResponse =
        executeCronOp<JsonObject, ServerFrame.CronGetResponse>(
            op = "cron.get",
            body = buildJsonObject { put("task_id", task.value) },
            timeout = timeout,
            onSuccess = { frame, decoded ->
                decodeFrame<ServerFrame.CronGetResponse>(decoded, frame)
            },
            onFailure = { frame, error ->
                ServerFrame.CronGetResponse(id = frame.id, ts = frame.ts, requestId = frame.requestId, success = false, error = error)
            },
        )

    override suspend fun deleteCron(task: CronTaskRef, timeout: Duration): ServerFrame.CronDeleteResponse =
        executeCronOp<Unit, ServerFrame.CronDeleteResponse>(
            op = "cron.delete",
            body = buildJsonObject { put("task_id", task.value) },
            timeout = timeout,
            onSuccess = { frame, _ ->
                ServerFrame.CronDeleteResponse(id = frame.id, ts = frame.ts, requestId = frame.requestId, success = true)
            },
            onFailure = { frame, error ->
                ServerFrame.CronDeleteResponse(id = frame.id, ts = frame.ts, requestId = frame.requestId, success = false, error = error)
            },
        )

    override suspend fun deleteAllCrons(agent: CronAgentRef, timeout: Duration): ServerFrame.CronDeleteAllResponse =
        executeCronOp<JsonObject, ServerFrame.CronDeleteAllResponse>(
            op = "cron.delete_all",
            body = buildJsonObject { put("agent_id", agent.value) },
            timeout = timeout,
            onSuccess = { frame, decoded ->
                ServerFrame.CronDeleteAllResponse(
                    id = frame.id, ts = frame.ts, requestId = frame.requestId, success = true,
                    count = decoded["deleted"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            },
            onFailure = { frame, error ->
                ServerFrame.CronDeleteAllResponse(id = frame.id, ts = frame.ts, requestId = frame.requestId, success = false, error = error)
            },
        )

    override suspend fun sendCronPause(command: CronPauseCommand): ServerFrame.CronPauseResponse =
        executeCronOp<Unit, ServerFrame.CronPauseResponse>(
            op = "cron.pause",
            body = buildJsonObject { put("task_id", command.taskId) },
            timeout = command.timeoutMs.milliseconds,
            onSuccess = { frame, _ ->
                ServerFrame.CronPauseResponse(id = frame.id, ts = frame.ts, requestId = frame.requestId, success = true)
            },
            onFailure = { frame, error ->
                ServerFrame.CronPauseResponse(id = frame.id, ts = frame.ts, requestId = frame.requestId, success = false, error = error)
            },
        )

    override suspend fun sendCronResume(command: CronResumeCommand): ServerFrame.CronResumeResponse =
        executeCronOp<Unit, ServerFrame.CronResumeResponse>(
            op = "cron.resume",
            body = buildJsonObject {
                put("task_id", command.taskId)
                command.scheduledFor?.let { put("scheduled_for", it) }
            },
            timeout = command.timeoutMs.milliseconds,
            onSuccess = { frame, _ ->
                ServerFrame.CronResumeResponse(id = frame.id, ts = frame.ts, requestId = frame.requestId, success = true)
            },
            onFailure = { frame, error ->
                ServerFrame.CronResumeResponse(id = frame.id, ts = frame.ts, requestId = frame.requestId, success = false, error = error)
            },
        )

    // RPC and decoder failures become typed transport responses; cancellation still propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend inline fun <reified R, T> executeCronOp(
        op: String,
        body: JsonObject,
        timeout: Duration,
        crossinline onSuccess: (frame: CronFrameIdentity, decoded: R) -> T,
        crossinline onFailure: (frame: CronFrameIdentity, error: String) -> T,
    ): T {
        val opTag = op.replace('.', '_')
        val frame = CronFrameIdentity(
            id = "$opTag-${Uuid.random()}",
            ts = Clock.System.now().toString(),
            requestId = "iroh-$opTag-${Uuid.random()}",
        )

        return try {
            withTimeoutOrNull(timeout) {
                val response = adminRpc(op, CRON_ADMIN_PATH, body.toString())
                if (!response.success) return@withTimeoutOrNull onFailure(frame, response.error ?: "$op failed")
                if (R::class == Unit::class) {
                    @Suppress("UNCHECKED_CAST")
                    return@withTimeoutOrNull onSuccess(frame, Unit as R)
                }
                val result = response.result ?: return@withTimeoutOrNull onFailure(frame, "$op failed: no result")
                val decoded = json.decodeFromJsonElement<R>(result)
                onSuccess(frame, decoded)
            } ?: onFailure(frame, "$op timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onFailure(frame, error.message ?: "$op failed")
        }
    }

    private inline fun <reified T> decodeFrame(result: JsonObject, frame: CronFrameIdentity): T =
        json.decodeFromJsonElement(buildJsonObject {
            result.forEach { (key, value) -> put(key, value) }
            put("id", frame.id)
            put("ts", frame.ts)
            put("request_id", frame.requestId)
            put("success", true)
        })

    companion object {
        private const val CRON_ADMIN_PATH = "/v1/cron"
        private val json = LettaJson.wire
    }
}

private data class CronFrameIdentity(val id: String, val ts: String, val requestId: String)
