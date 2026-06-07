package com.letta.mobile.data.transport.api

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray

/**
 * Common placeholder for runtimes that can compile against the transport
 * contract before wiring a platform socket implementation.
 */
class NoOpChannelTransport : IChannelTransport {
    private val _state = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Idle)
    override val state: StateFlow<ChannelTransportState> = _state
    override val events: SharedFlow<ServerFrame> = MutableSharedFlow()

    override suspend fun connect(
        baseShimUrl: String,
        token: String,
        deviceId: String,
        clientVersion: String,
    ) {
        _state.value = ChannelTransportState.Disconnected(
            code = -1,
            reason = "No channel transport implementation is installed",
        )
    }

    override fun send(
        agentId: String,
        conversationId: String,
        text: String,
        otid: String?,
        contentParts: JsonArray?,
        startNewConversation: Boolean,
    ): Boolean = false

    override fun cancel(conversationId: String): Boolean = false
    override fun bye(): Boolean = false
    override suspend fun disconnect() {
        _state.value = ChannelTransportState.Disconnected(1000, "no-op disconnect")
    }

    override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult =
        A2uiActionDispatchResult.Failed

    override fun subscribe(runId: String, cursor: Long): Boolean = false

    override suspend fun sendCronList(
        agentId: String?,
        conversationId: String?,
        timeoutMs: Long,
    ): ServerFrame.CronListResponse = unsupported()

    override suspend fun sendCronAdd(
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
    ): ServerFrame.CronAddResponse = unsupported()

    override suspend fun sendCronGet(taskId: String, timeoutMs: Long): ServerFrame.CronGetResponse =
        unsupported()

    override suspend fun sendCronDelete(taskId: String, timeoutMs: Long): ServerFrame.CronDeleteResponse =
        unsupported()

    override suspend fun sendCronDeleteAll(agentId: String, timeoutMs: Long): ServerFrame.CronDeleteAllResponse =
        unsupported()

    override suspend fun sendSubagentList(all: Boolean, timeoutMs: Long): ServerFrame.SubagentListResponse =
        unsupported()

    override suspend fun sendSubagentTodos(toolCallId: String, timeoutMs: Long): ServerFrame.SubagentTodosResponse =
        unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("No channel transport implementation is installed")
}
