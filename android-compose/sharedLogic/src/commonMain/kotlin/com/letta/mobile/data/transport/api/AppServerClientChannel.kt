package com.letta.mobile.data.transport.api

import com.letta.mobile.data.pins.wire.AgentPinsWire
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.appServerAdminRpcParams
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
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
 * the connection state. Chat turns ride the App Server turn engine, not this channel; cron RPCs use the
 * same bridge as the JVM channel. Subagent RPCs are not bridged yet, so they fail as on
 * [NoOpChannelTransport]. [state] and [events] are shared on [scope] only while something observes
 * them. Toolset status is retained eagerly until the session disconnects; [scope] bounds its
 * collector as well.
 */
class AppServerClientChannel(
    private val client: AppServerClient,
    scope: CoroutineScope,
    /** What the host advertised when this session authenticated (its `auth_response` capabilities). */
    override val hostCapabilities: Set<String> = emptySet(),
) : CronRpcChannel(appServerCronRpc(client)) {
    override val deviceStatuses get() = toolsets.deviceStatuses
    private val toolsets = ToolsetStatusStore()
    override val toolsetStates = toolsets.states

    init {
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            val observation = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                client.events.collect { toolsets.observe(it) }
            }
            try {
                if (client.isConnected.firstOrNull { !it } == null) awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    observation.cancelAndJoin()
                    toolsets.clear()
                }
            }
        }
    }

    override val state: StateFlow<ChannelTransportState> = connectionStates(client)
        .stateIn(scope, SharingStarted.WhileSubscribed(), ChannelTransportState.Connecting())

    /**
     * The host's pushes that are not App Server protocol frames (an Iroh
     * host's approvals, mode and conversation changes), as the [ServerFrame]s a JVM Iroh channel
     * delivers, so the shared approval relay and mode chip fold them the same way.
     */
    override val events: SharedFlow<ServerFrame> = client.events
        .mapNotNull { received -> (received.frame as? AppServerInboundFrame.Unknown)?.let { hostPush(it.raw) } }
        .shareIn(scope, SharingStarted.WhileSubscribed())

    private companion object {
        /**
         * A host push this channel carries, or null for any other frame; an unreadable one is
         * dropped, as the JVM Iroh channel drops a frame it cannot decode.
         */
        fun hostPush(raw: JsonObject): ServerFrame? =
            runCatching { LettaJson.wire.decodeFromJsonElement(ServerFrameSerializer, raw) }
                .getOrNull()
                ?.takeIf {
                    it is ServerFrame.PendingApprovalsUpdated || it is ServerFrame.PermissionModeUpdated ||
                        it is ServerFrame.ConversationActivity || it is ServerFrame.ConversationUpdated ||
                        (it is ServerFrame.Unknown && it.type in UNTYPED_HOST_PUSH_TYPES)
                }

        /** Host pushes with no typed frame; the shared pins and shell state decode their own raw body. */
        private val UNTYPED_HOST_PUSH_TYPES = setOf(AgentPinsWire.FRAME_TYPE, ShellStateWire.FRAME_TYPE)

        /**
         * The client's connection as a transport state. A client whose reconnect owner numbers its connections
         * ([AppServerClient.connectionEpoch]) is projected from that number, upstream of any state-flow
         * conflation, so a drop and redial never looks like staying connected. A client with no such owner (a
         * plain single-socket client) has no identity to read, so the connections are counted here instead: every
         * time it reports up is a new one.
         */
        private fun connectionStates(client: AppServerClient): Flow<ChannelTransportState> {
            client.connectionEpoch?.let { epochs ->
                return epochs.map { epoch -> epoch?.let(::connectedState) ?: DISCONNECTED }
            }
            var counted = 0L
            return client.isConnected.map { up -> if (up) connectedState(++counted) else DISCONNECTED }
        }

        fun connectedState(connectionEpoch: Long) = ChannelTransportState.Connected(
            serverId = "app-server", sessionId = "app-server", deviceId = null, connectionEpoch = connectionEpoch,
        )

        val DISCONNECTED = ChannelTransportState.Disconnected(code = 1000, reason = "App Server session closed")
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun appServerCronRpc(client: AppServerClient):
    suspend (String, String, String?) -> AppServerInboundFrame.AdminRpcResponse = { method, path, body ->
    client.adminRpc(
        AppServerCommand.AdminRpc(
            requestId = "admin-${Uuid.random()}", method = method,
            params = appServerAdminRpcParams(path = path, body = body),
        ),
    )
}
