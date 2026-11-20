package com.letta.mobile.data.shellstate.shared

import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The host's answer to one shell-state request: [result] when it succeeded, else its [error] code. */
class ShellStateReply(
    /** Whether the request succeeded. */
    val success: Boolean,
    /** The result; null on failure. */
    val result: JsonElement? = null,
    /** The failure's `error` string; null on success. */
    val error: String? = null,
)

/**
 * The part of the host connection the shared shell state uses. The app binds it to its channel
 * transport with [channelShellStateLink]; tests stand in a fake host.
 */
interface ShellStateLink {
    /** The feature capabilities the connected host advertised; empty while disconnected. */
    val hostCapabilities: Set<String>

    /** Whether the host is connected right now. */
    val isConnected: Boolean

    /** Emits each time the connection comes up (true) or goes down (false). */
    val connection: Flow<Boolean>

    /** Every `shell_state_updated` frame the host pushes, raw. */
    val pushes: Flow<JsonObject>

    /** One `admin_rpc` call with [params]: the host's reply, failed or not. Throws when the link itself fails. */
    suspend fun call(method: String, params: JsonObject): ShellStateReply
}

/** The shared shell state link over a session's channel transport: one Iroh `admin_rpc` per request. */
fun channelShellStateLink(transport: IChannelTransport): ShellStateLink = ChannelShellStateLink(transport)

private class ChannelShellStateLink(private val transport: IChannelTransport) : ShellStateLink {
    override suspend fun call(method: String, params: JsonObject): ShellStateReply =
        transport.adminRpc(method = method, path = "", body = params.toString())
            .let { ShellStateReply(success = it.success, result = it.result, error = it.error) }

    override val pushes: Flow<JsonObject> = transport.events.mapNotNull { frame ->
        (frame as? ServerFrame.Unknown)?.takeIf { it.type == ShellStateWire.FRAME_TYPE }?.raw
    }

    override val connection: Flow<Boolean> = transport.state.map(::isUp)

    override val isConnected: Boolean get() = isUp(transport.state.value)

    override val hostCapabilities: Set<String> get() = transport.hostCapabilities

    private fun isUp(state: ChannelTransportState): Boolean = state is ChannelTransportState.Connected
}
