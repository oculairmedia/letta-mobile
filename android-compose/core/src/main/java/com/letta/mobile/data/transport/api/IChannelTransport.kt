package com.letta.mobile.data.transport.api

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.ServerFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray

/**
 * Narrow public surface of [ChannelTransport] used by repositories and
 * bridge adapters. Tests can substitute hand-written fakes for this
 * interface instead of mocking the stateful concrete WebSocket transport
 * in a reused Gradle daemon JVM.
 */
interface IChannelTransport {
    val state: StateFlow<ChannelTransport.State>
    val events: SharedFlow<ServerFrame>

    suspend fun connect(
        baseShimUrl: String,
        token: String,
        deviceId: String,
        clientVersion: String,
    )

    fun send(
        agentId: String,
        conversationId: String,
        text: String,
        otid: String? = null,
        contentParts: JsonArray? = null,
    ): Boolean

    fun cancel(): Boolean
    fun bye(): Boolean
    suspend fun disconnect()
    fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult

    suspend fun sendCronList(
        agentId: String? = null,
        conversationId: String? = null,
        timeoutMs: Long = ChannelTransport.DEFAULT_CRON_TIMEOUT_MS,
    ): ServerFrame.CronListResponse

    suspend fun sendCronAdd(
        agentId: String,
        name: String,
        description: String,
        prompt: String,
        recurring: Boolean,
        cron: String? = null,
        every: String? = null,
        at: String? = null,
        timezone: String? = null,
        conversationId: String? = null,
        timeoutMs: Long = ChannelTransport.DEFAULT_CRON_TIMEOUT_MS,
    ): ServerFrame.CronAddResponse

    suspend fun sendCronGet(
        taskId: String,
        timeoutMs: Long = ChannelTransport.DEFAULT_CRON_TIMEOUT_MS,
    ): ServerFrame.CronGetResponse

    suspend fun sendCronDelete(
        taskId: String,
        timeoutMs: Long = ChannelTransport.DEFAULT_CRON_TIMEOUT_MS,
    ): ServerFrame.CronDeleteResponse

    suspend fun sendCronDeleteAll(
        agentId: String,
        timeoutMs: Long = ChannelTransport.DEFAULT_CRON_TIMEOUT_MS,
    ): ServerFrame.CronDeleteAllResponse
}
