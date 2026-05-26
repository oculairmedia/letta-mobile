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
        startNewConversation: Boolean = false,
    ): Boolean

    fun cancel(conversationId: String): Boolean
    fun bye(): Boolean
    suspend fun disconnect()
    fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult

    /**
     * letta-mobile-2rkdj — Spec §11/§3.4: replay + live-tail a Run's
     * frame log starting after [cursor]. Returns true if the
     * `subscribe` frame was dispatched on the wire, false if the
     * socket is not connected.
     *
     * Replayed frames arrive as [ServerFrame.SubscribeFrameMessage]
     * envelopes on [events] in seq order; subscription terminates
     * with [ServerFrame.SubscribeDone] once the run reaches a
     * terminal status and the live-tail catches up.
     *
     * Pass `cursor = 0` for a full replay from the start of the run.
     */
    fun subscribe(runId: String, cursor: Long = 0L): Boolean

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
