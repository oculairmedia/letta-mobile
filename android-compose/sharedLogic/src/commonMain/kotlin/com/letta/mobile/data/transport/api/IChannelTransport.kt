package com.letta.mobile.data.transport.api

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransportDefaults
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray

/**
 * Cross-platform transport surface used by repositories, chat bridges, and
 * desktop/runtime adapters. Platform modules own the concrete socket/client
 * implementation.
 */
interface IChannelTransport {
    val state: StateFlow<ChannelTransportState>
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
     * Replay + live-tail a Run's frame log starting after [cursor].
     */
    fun subscribe(runId: String, cursor: Long = 0L): Boolean

    suspend fun sendCronList(
        agentId: String? = null,
        conversationId: String? = null,
        timeoutMs: Long = ChannelTransportDefaults.DEFAULT_CRON_TIMEOUT_MS,
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
        timeoutMs: Long = ChannelTransportDefaults.DEFAULT_CRON_TIMEOUT_MS,
    ): ServerFrame.CronAddResponse

    suspend fun sendCronGet(
        taskId: String,
        timeoutMs: Long = ChannelTransportDefaults.DEFAULT_CRON_TIMEOUT_MS,
    ): ServerFrame.CronGetResponse

    suspend fun sendCronDelete(
        taskId: String,
        timeoutMs: Long = ChannelTransportDefaults.DEFAULT_CRON_TIMEOUT_MS,
    ): ServerFrame.CronDeleteResponse

    suspend fun sendCronDeleteAll(
        agentId: String,
        timeoutMs: Long = ChannelTransportDefaults.DEFAULT_CRON_TIMEOUT_MS,
    ): ServerFrame.CronDeleteAllResponse

    suspend fun sendSubagentList(
        all: Boolean = false,
        timeoutMs: Long = ChannelTransportDefaults.DEFAULT_CRON_TIMEOUT_MS,
    ): ServerFrame.SubagentListResponse

    suspend fun sendSubagentTodos(
        toolCallId: String,
        timeoutMs: Long = ChannelTransportDefaults.DEFAULT_CRON_TIMEOUT_MS,
    ): ServerFrame.SubagentTodosResponse
}
