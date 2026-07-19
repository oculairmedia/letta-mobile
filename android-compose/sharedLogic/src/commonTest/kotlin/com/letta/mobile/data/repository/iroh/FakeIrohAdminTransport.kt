package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Admin-RPC test double. Cron/subagent/chat stubs stay on [NoOpChannelTransport]
 * so this file only owns the admin_rpc recording surface.
 */
class FakeIrohAdminTransport : NoOpChannelTransport() {
    /** Recorded admin_rpc invocations (typed [AdminRpcCall] bag). */
    val rpcCalls = mutableListOf<AdminRpcCall>()
    var rpcResponder: (AdminRpcCall) -> AppServerInboundFrame.AdminRpcResponse = { call ->
        AppServerInboundFrame.AdminRpcResponse(
            requestId = "req",
            success = false,
            error = "${call.method} has no responder",
        )
    }

    override val state: StateFlow<ChannelTransportState> =
        MutableStateFlow(ChannelTransportState.Connected("server", "session", "device"))

    /** Keep the fake "connected" for admin_rpc tests; ignore dial args. */
    override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) = Unit

    override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
        val call = AdminRpcCall(method = method, path = path, body = body)
        rpcCalls += call
        return rpcResponder(call)
    }
}
