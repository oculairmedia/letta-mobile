package com.letta.mobile.data.shellstate.shared

import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
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
fun channelShellStateLink(transport: IChannelTransport): ShellStateLink = object : ShellStateLink {
    override val hostCapabilities: Set<String> get() = transport.hostCapabilities

    override val isConnected: Boolean get() = transport.state.value is ChannelTransportState.Connected

    override val connection: Flow<Boolean> = transport.state.map { it is ChannelTransportState.Connected }

    override val pushes: Flow<JsonObject> = transport.events
        .filterIsInstance<ServerFrame.Unknown>()
        .filter { it.type == ShellStateWire.FRAME_TYPE }
        .map { it.raw }

    override suspend fun call(method: String, params: JsonObject): ShellStateReply {
        val response = transport.adminRpc(method = method, path = "", body = params.toString())
        return ShellStateReply(response.success, response.result, response.error)
    }
}
