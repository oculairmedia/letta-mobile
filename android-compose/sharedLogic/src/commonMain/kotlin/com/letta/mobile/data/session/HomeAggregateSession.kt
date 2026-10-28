package com.letta.mobile.data.session

import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.api.FrameCollectorOverflowAwareChannelTransport
import com.letta.mobile.data.transport.iroh.AdminRpcErrors

/** Home's connection/capability epoch, independent of graph wrapper and presentation metadata. */
internal class HomeAggregateSession {
    private var graph: SessionRepositoryGraph? = null
    private var connection: ChannelTransportState.Connected? = null
    private var transportGeneration: Long? = null
    private var features: Set<String> = emptySet()
    private var routes: Set<SessionRoute> = emptySet()

    val isConnected: Boolean get() = connection != null

    /** True when the host, connection generation or advertised capabilities have changed. */
    fun update(next: SessionRepositoryGraph): Boolean {
        val connected = next.channelTransport.state.value as? ChannelTransportState.Connected
        val nextGeneration = (next.channelTransport as? FrameCollectorOverflowAwareChannelTransport)
            ?.frameCollectorConnectionGeneration
        val nextFeatures = next.channelTransport.hostCapabilities.toSet()
        val nextRoutes = SessionRoute.entries.filterTo(mutableSetOf(), next.capabilities::supports)
        val hostChanged = hostChanged(next)
        val changed = hostChanged || connectionChanged(connected, nextGeneration) ||
            capabilitiesChanged(connected, nextFeatures, nextRoutes)
        graph = next
        connection = connected
        transportGeneration = nextGeneration
        if (connected != null || hostChanged) {
            features = nextFeatures
            routes = nextRoutes
        }
        return changed
    }

    private fun hostChanged(next: SessionRepositoryGraph): Boolean =
        graph?.backendDescriptor?.backendId != next.backendDescriptor.backendId ||
            graph?.channelTransport !== next.channelTransport

    private fun connectionChanged(connected: ChannelTransportState.Connected?, nextGeneration: Long?): Boolean {
        if (connected == null) return false
        return connection == null || connected.serverId != connection?.serverId ||
            connected.sessionId != connection?.sessionId || nextGeneration != transportGeneration
    }

    // Clearing handshake features during disconnect is not a new host capability offer.
    private fun capabilitiesChanged(
        connected: ChannelTransportState.Connected?,
        nextFeatures: Set<String>,
        nextRoutes: Set<SessionRoute>,
    ): Boolean = connected != null && (nextFeatures != features || nextRoutes != routes)
}

/** Explicit statistic absence, including the shared admin-RPC unknown-method vocabulary. */
internal fun Throwable.isUnsupportedHomeStatistic(): Boolean {
    if (this is UnsupportedOperationException || AdminRpcErrors.isUnknownMethod(message)) return true
    val reason = message.orEmpty().lowercase()
    return reason.contains("unsupported admin_rpc") || reason.contains("admin_rpc is not supported") ||
        reason.contains("has no admin_rpc path")
}
