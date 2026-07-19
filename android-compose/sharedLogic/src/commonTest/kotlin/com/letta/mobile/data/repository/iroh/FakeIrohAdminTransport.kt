package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray

class FakeIrohAdminTransport : IChannelTransport {
    data class RpcCall(val method: String, val path: String, val body: String?)

    val rpcCalls = mutableListOf<RpcCall>()
    var rpcResponder: (RpcCall) -> AppServerInboundFrame.AdminRpcResponse = { call ->
        AppServerInboundFrame.AdminRpcResponse(
            requestId = "req",
            success = false,
            error = "${call.method} has no responder",
        )
    }

    override val state: StateFlow<ChannelTransportState> =
        MutableStateFlow(ChannelTransportState.Connected("server", "session", "device"))
    override val events = MutableSharedFlow<ServerFrame>()
    override val frameEvents = MutableSharedFlow<TransportFrameEvent>()

    override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) = Unit
    override fun send(
        agentId: String,
        conversationId: String,
        text: String,
        otid: String?,
        contentParts: JsonArray?,
        startNewConversation: Boolean,
    ): Boolean = true

    override fun cancel(conversationId: String): Boolean = true
    override fun bye(): Boolean = true
    override suspend fun disconnect() = Unit
    override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult = A2uiActionDispatchResult.Sent("frame-1")
    override fun subscribe(runId: String, cursor: Long): Boolean = false
    override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
        val call = RpcCall(method, path, body)
        rpcCalls += call
        return rpcResponder(call)
    }

    override suspend fun sendCronList(agentId: String?, conversationId: String?, timeoutMs: Long) = error("unused")
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
    ) = error("unused")

    override suspend fun sendCronGet(taskId: String, timeoutMs: Long) = error("unused")
    override suspend fun sendCronDelete(taskId: String, timeoutMs: Long) = error("unused")
    override suspend fun sendCronDeleteAll(agentId: String, timeoutMs: Long) = error("unused")
    override suspend fun sendSubagentList(all: Boolean, timeoutMs: Long) = error("unused")
    override suspend fun sendSubagentTodos(toolCallId: String, timeoutMs: Long) = error("unused")
}
