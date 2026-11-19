package com.letta.mobile.data.pins.shared

import com.letta.mobile.data.pins.wire.AgentPinsWire
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The host's answer to one pins request: [result] when it succeeded, else its [error] code. */
class AgentPinsReply(
    /** Whether the request succeeded. */
    val success: Boolean,
    /** The result; null on failure. */
    val result: JsonElement? = null,
    /** The failure's `error` string; null on success. */
    val error: String? = null,
)

/**
 * The part of the host connection the shared pins use. The app binds it to its channel transport
 * with [channelAgentPinsLink]; tests stand in a fake host.
 */
interface AgentPinsLink {
    /** The feature capabilities the connected host advertised; empty while disconnected. */
    val hostCapabilities: Set<String>

    /** Whether the host is connected right now. */
    val isConnected: Boolean

    /** Emits each time the connection comes up (true) or goes down (false). */
    val connection: Flow<Boolean>

    /** Every `pins_updated` frame the host pushes, raw. */
    val pushes: Flow<JsonObject>

    /** One `admin_rpc` call with [params]: the host's reply, failed or not. Throws when the link itself fails. */
    suspend fun call(method: String, params: JsonObject): AgentPinsReply
}

/** The shared pins link over a session's channel transport: one Iroh `admin_rpc` per request. */
fun channelAgentPinsLink(transport: IChannelTransport): AgentPinsLink = object : AgentPinsLink {
    override val hostCapabilities: Set<String> get() = transport.hostCapabilities

    override val isConnected: Boolean get() = transport.state.value is ChannelTransportState.Connected

    override val connection: Flow<Boolean> = transport.state.map { it is ChannelTransportState.Connected }

    override val pushes: Flow<JsonObject> = transport.events
        .filterIsInstance<ServerFrame.Unknown>()
        .filter { it.type == AgentPinsWire.FRAME_TYPE }
        .map { it.raw }

    override suspend fun call(method: String, params: JsonObject): AgentPinsReply {
        val response = transport.adminRpc(method = method, path = "", body = params.toString())
        return AgentPinsReply(response.success, response.result, response.error)
    }
}

/** The last shared list this device learned from one host, shown while that host is out of reach. */
interface AgentPinsCache {
    /** The last list stored; null when none was. */
    fun read(): AgentPinsSnapshot?

    /** Stores [snapshot] as the last list learned. */
    fun write(snapshot: AgentPinsSnapshot)
}

/**
 * [AgentPinsCache] in [store], one entry per host [scope] (the backend identity the session is bound
 * to). A corrupt entry reads as none; it is a cache of the host's value, never the source of truth.
 */
class SettingsAgentPinsCache(private val store: SecureSettingsStore, scope: String) : AgentPinsCache {
    private val key = "$KEY_PREFIX$scope"

    override fun read(): AgentPinsSnapshot? = store.getString(key)
        ?.let { runCatching { LettaJson.persisted.decodeFromString<AgentPinsSnapshot>(it) }.getOrNull() }

    override fun write(snapshot: AgentPinsSnapshot) = store.putString(key, LettaJson.persisted.encodeToString(snapshot))

    private companion object {
        const val KEY_PREFIX = "pins.agents.shared.v1:"
    }
}
