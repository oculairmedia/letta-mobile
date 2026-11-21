package com.letta.mobile.data.shellstate.channel

import com.letta.mobile.data.shellstate.shared.ShellStateLink
import com.letta.mobile.data.shellstate.shared.ShellStateReply
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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

/**
 * The same link over whichever transport [transports] holds now, for a client whose connection is replaced as a whole
 * (the browser's session graph is rebuilt per session, the desktop publishes its transport when it connects): null is no
 * connection. Each instance is a connection of its own, even when it reports the same session id and connection epoch
 * as the one before it (two backends can), so a swap is always seen as a new connection and the document is read again.
 */
fun channelShellStateLink(transports: StateFlow<IChannelTransport?>): ShellStateLink = FollowingShellStateLink(transports)

@OptIn(ExperimentalCoroutinesApi::class)
private class FollowingShellStateLink(private val transports: StateFlow<IChannelTransport?>) : ShellStateLink {
    private val lock = SynchronizedObject()
    private val numbered = ArrayDeque<Pair<IChannelTransport, Long>>()
    private var issued = 0L

    override suspend fun call(method: String, params: JsonObject): ShellStateReply {
        val transport = checkNotNull(transports.value) { "No channel transport" }
        return transport.adminRpc(method = method, path = "", body = params.toString())
            .let { ShellStateReply(success = it.success, result = it.result, error = it.error) }
    }

    override val pushes: Flow<JsonObject> = transports.flatMapLatest { transport ->
        transport?.events?.mapNotNull { frame -> (frame as? ServerFrame.Unknown)?.takeIf { it.type == ShellStateWire.FRAME_TYPE }?.raw }
            ?: emptyFlow()
    }

    override val connection: Flow<String?> = transports.flatMapLatest { transport ->
        transport?.state?.map { state -> sessionOf(transport, state) } ?: flowOf(null)
    }

    override val currentConnection: String? get() = transports.value?.let { sessionOf(it, it.state.value) }

    override val hostCapabilities: Set<String> get() = transports.value?.hostCapabilities.orEmpty()

    /**
     * The number of [transport] as an instance: given once, on first sight, and kept for as long as it is among the few most
     * recent, so a late read of a transport that was just replaced never renumbers the one that replaced it.
     */
    private fun instanceOf(transport: IChannelTransport): Long = synchronized(lock) {
        numbered.firstOrNull { it.first === transport }?.second ?: run {
            issued += 1
            numbered.addLast(transport to issued)
            if (numbered.size > RECENT_TRANSPORTS) numbered.removeFirst()
            issued
        }
    }

    private fun sessionOf(transport: IChannelTransport, state: ChannelTransportState): String? =
        (state as? ChannelTransportState.Connected)?.let { "${instanceOf(transport)}:${it.sessionId}#${it.connectionEpoch}" }

    private companion object {
        const val RECENT_TRANSPORTS = 4
    }
}

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
