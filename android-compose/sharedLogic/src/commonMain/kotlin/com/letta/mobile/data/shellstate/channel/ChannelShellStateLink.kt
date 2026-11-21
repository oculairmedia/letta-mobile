package com.letta.mobile.data.shellstate.channel

import com.letta.mobile.data.shellstate.shared.ShellStateLink
import com.letta.mobile.data.shellstate.shared.ShellStateReply
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonObject

/** The shared shell state link over a session's channel transport: one Iroh `admin_rpc` per request. */
fun channelShellStateLink(transport: IChannelTransport): ShellStateLink = ChannelShellStateLink { transport }

/**
 * The same link over a transport resolved on first use: [transport] is not called until the link is used, so a
 * graph where the transport itself depends on the settings that own this link can build the link first.
 */
fun channelShellStateLink(transport: () -> IChannelTransport): ShellStateLink = ChannelShellStateLink(transport)

private class ChannelShellStateLink(resolve: () -> IChannelTransport) : ShellStateLink {
    private val transport: IChannelTransport by lazy(resolve)

    override suspend fun call(method: String, params: JsonObject): ShellStateReply =
        transport.adminRpc(method = method, path = "", body = params.toString())
            .let { ShellStateReply(success = it.success, result = it.result, error = it.error) }

    override val pushes: Flow<JsonObject> = flow {
        emitAll(
            transport.events.mapNotNull { frame ->
                (frame as? ServerFrame.Unknown)?.takeIf { it.type == ShellStateWire.FRAME_TYPE }?.raw
            },
        )
    }

    override val connection: Flow<String?> = flow { emitAll(transport.state.map(::sessionOf)) }

    override val currentConnection: String? get() = sessionOf(transport.state.value)

    override val hostCapabilities: Set<String> get() = transport.hostCapabilities

    private fun sessionOf(state: ChannelTransportState): String? =
        (state as? ChannelTransportState.Connected)?.let { "${it.sessionId}#${it.connectionEpoch}" }
}
