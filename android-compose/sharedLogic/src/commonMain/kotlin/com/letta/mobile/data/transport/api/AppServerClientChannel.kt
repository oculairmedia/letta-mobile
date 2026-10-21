package com.letta.mobile.data.transport.api

import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.appServerAdminRpcParams
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The session graph's channel over an App Server session the host already
 * holds ([client]), for a host with no JVM Iroh binding (the browser reaches an Iroh host's App
 * Server on `/letta/appserver/0` through iroh-wasm). It carries what the graph's Iroh admin sources
 * need: `admin_rpc` (the same [appServerAdminRpcParams] request the JVM Iroh transport sends) and
 * the connection state. Chat turns ride the App Server turn engine, not this channel; cron and
 * subagent RPCs are not bridged yet, so they fail as on [NoOpChannelTransport]. [state] and [events]
 * are shared on [scope] only while something observes them, so a closed graph leaves nothing running.
 */
class AppServerClientChannel(
    private val client: AppServerClient,
    scope: CoroutineScope,
    /** What the host advertised when this session authenticated (its `auth_response` capabilities). */
    override val hostCapabilities: Set<String> = emptySet(),
) : NoOpChannelTransport() {
    override val state: StateFlow<ChannelTransportState> = client.isConnected
        .map { connected -> if (connected) CONNECTED else DISCONNECTED }
        .stateIn(scope, SharingStarted.WhileSubscribed(), ChannelTransportState.Connecting())

    /**
     * The host's pushes that are not App Server protocol frames (an Iroh
     * host's `pending_approvals` and `permission_mode`), as the [ServerFrame]s a JVM Iroh channel
     * delivers, so the shared approval relay and mode chip fold them the same way.
     */
    override val events: SharedFlow<ServerFrame> = client.events
        .mapNotNull { received -> (received.frame as? AppServerInboundFrame.Unknown)?.let { hostPush(it.raw) } }
        .shareIn(scope, SharingStarted.WhileSubscribed())

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
        /**
         * A host push this channel carries, or null for any other frame; an unreadable one is
         * dropped, as the JVM Iroh channel drops a frame it cannot decode.
         */
        fun hostPush(raw: JsonObject): ServerFrame? =
            runCatching { LettaJson.wire.decodeFromJsonElement(ServerFrameSerializer, raw) }
                .getOrNull()
                ?.takeIf { it is ServerFrame.PendingApprovalsUpdated || it is ServerFrame.PermissionModeUpdated }

        val CONNECTED = ChannelTransportState.Connected(serverId = "app-server", sessionId = "app-server", deviceId = null)
        val DISCONNECTED = ChannelTransportState.Disconnected(code = 1000, reason = "App Server session closed")
    }
}
