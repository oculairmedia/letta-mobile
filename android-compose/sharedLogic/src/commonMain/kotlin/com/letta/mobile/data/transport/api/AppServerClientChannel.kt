package com.letta.mobile.data.transport.api

import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.appServerAdminRpcParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * letta-mobile-ckmub.17.1: the session graph's channel over an App Server session the host already
 * holds ([client]), for a host with no JVM Iroh binding (the browser reaches an Iroh host's App
 * Server on `/letta/appserver/0` through iroh-wasm). It carries what the graph's Iroh admin sources
 * need: `admin_rpc` (the same [appServerAdminRpcParams] request the JVM Iroh transport sends) and
 * the connection state. Chat turns ride the App Server turn engine, not this channel; cron and
 * subagent RPCs are not bridged yet, so they fail as on [NoOpChannelTransport]. [state] is shared on
 * [scope] only while a graph repository observes it, so a closed graph leaves nothing running.
 */
class AppServerClientChannel(
    private val client: AppServerClient,
    scope: CoroutineScope,
) : NoOpChannelTransport() {
    override val state: StateFlow<ChannelTransportState> = client.isConnected
        .map { connected -> if (connected) CONNECTED else DISCONNECTED }
        .stateIn(scope, SharingStarted.WhileSubscribed(), ChannelTransportState.Connecting())

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse =
        client.adminRpc(
            AppServerCommand.AdminRpc(
                requestId = "admin-${Uuid.random()}",
                method = method,
                params = appServerAdminRpcParams(path = path, body = body),
            ),
        )

    private companion object {
        val CONNECTED = ChannelTransportState.Connected(serverId = "app-server", sessionId = "app-server", deviceId = null)
        val DISCONNECTED = ChannelTransportState.Disconnected(code = 1000, reason = "App Server session closed")
    }
}
