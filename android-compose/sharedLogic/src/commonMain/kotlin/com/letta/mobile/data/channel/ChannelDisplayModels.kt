package com.letta.mobile.data.channel

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.runtime.BackendDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Immutable
data class ChannelLibraryState(
    val channels: List<ChannelDisplayItem> = emptyList(),
) {
    val isEmpty: Boolean
        get() = channels.isEmpty()

    val summaryLabel: String
        get() = when (channels.size) {
            0 -> "No channels"
            1 -> "1 channel"
            else -> "${channels.size} channels"
        }
}

@Immutable
data class ChannelDisplayItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val detailText: String,
    val metadataLabels: List<String>,
    val status: ChannelDisplayStatus,
)

enum class ChannelDisplayStatus(val label: String) {
    Connected("Connected"),
    Connecting("Connecting"),
    /**
     * letta-mobile-wxy4s: the connection dropped and the supervisor is redialing.
     * Distinct from [Disconnected] on purpose — "reconnecting" must read
     * differently from "dead", and BOTH must read differently from "connected".
     * The 2026-07-31 incident was invisible precisely because dead connections
     * kept rendering cached data with no staleness signal at all.
     */
    Reconnecting("Reconnecting"),
    Idle("Idle"),
    Disconnected("Disconnected"),
    ;

    /** True when the surface is showing data that may be stale. */
    val isStale: Boolean
        get() = this == Reconnecting || this == Disconnected
}

object ChannelDisplayMapper {
    fun build(
        backendDescriptor: BackendDescriptor,
        channelTransportState: ChannelTransportState,
    ): ChannelLibraryState =
        ChannelLibraryState(
            channels = listOf(channelItem(backendDescriptor, channelTransportState)),
        )

    private fun channelItem(
        backendDescriptor: BackendDescriptor,
        channelTransportState: ChannelTransportState,
    ): ChannelDisplayItem {
        val status = channelTransportState.toDisplayStatus()
        return ChannelDisplayItem(
            id = backendDescriptor.backendId.value,
            title = backendDescriptor.label,
            subtitle = channelTransportState.describe(),
            detailText = channelTransportState.detailText(),
            metadataLabels = channelTransportState.metadataLabels(status),
            status = status,
        )
    }

    private fun ChannelTransportState.describe(): String = when (this) {
        ChannelTransportState.Idle -> "Idle"
        is ChannelTransportState.Connecting -> "Connecting"
        is ChannelTransportState.Connected -> buildString {
            append("Connected")
            canonicalLiveTransport?.takeIf { it.isNotBlank() }?.let { append(" via $it") }
        }
        is ChannelTransportState.Disconnected ->
            if (willReconnect) "Reconnecting${reason.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()}"
            else reason.ifBlank { "Disconnected" }
    }

    private fun ChannelTransportState.detailText(): String = when (this) {
        ChannelTransportState.Idle -> "Live channel transport is idle."
        is ChannelTransportState.Connecting -> "Live channel transport is connecting."
        is ChannelTransportState.Connected -> buildString {
            append("Connected to server ")
            append(serverId)
            append(" session ")
            append(sessionId)
            canonicalLiveTransport?.takeIf { it.isNotBlank() }?.let { append(" using $it") }
            if (a2uiEnabled) {
                append(". A2UI is enabled")
                a2uiVersion?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
            }
        }
        is ChannelTransportState.Disconnected ->
            if (willReconnect) {
                "Live channel transport lost its connection and is reconnecting. " +
                    "Anything shown may be stale."
            } else {
                reason.ifBlank { "Live channel transport is disconnected." }
            }
    }

    private fun ChannelTransportState.metadataLabels(status: ChannelDisplayStatus): List<String> = when (this) {
        ChannelTransportState.Idle,
        is ChannelTransportState.Connecting,
        -> listOf(status.label)
        is ChannelTransportState.Connected -> listOfNotNull(
            status.label,
            canonicalLiveTransport?.takeIf { it.isNotBlank() },
            "A2UI".takeIf { a2uiEnabled },
            a2uiVersion?.takeIf { it.isNotBlank() }?.let { "A2UI $it" },
            a2uiCatalog?.takeIf { it.isNotBlank() }?.let { "Catalog $it" },
            deviceId?.takeIf { it.isNotBlank() }?.let { "Device $it" },
        )
        is ChannelTransportState.Disconnected -> listOfNotNull(
            status.label,
            "Code $code",
            "Auth".takeIf { isAuthFailure },
            // wxy4s: never let a surface render cached data without saying so.
            "Stale".takeIf { status.isStale },
        )
    }

    private fun ChannelTransportState.toDisplayStatus(): ChannelDisplayStatus = when (this) {
        ChannelTransportState.Idle -> ChannelDisplayStatus.Idle
        is ChannelTransportState.Connecting -> ChannelDisplayStatus.Connecting
        is ChannelTransportState.Connected -> ChannelDisplayStatus.Connected
        // wxy4s: a supervisor-driven redial (Degraded) sets willReconnect=true —
        // surface it as Reconnecting so the user sees recovery in progress rather
        // than a hard failure (or, worse, nothing at all).
        is ChannelTransportState.Disconnected ->
            if (willReconnect && !isAuthFailure) ChannelDisplayStatus.Reconnecting
            else ChannelDisplayStatus.Disconnected
    }
}

class ChannelLibraryController<Graph : SessionRepositoryGraph>(
    private val sessionGraphProvider: SessionRepositoryGraphProvider<Graph>,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val stateFlow = MutableStateFlow(snapshotState())
    val state: StateFlow<ChannelLibraryState> = stateFlow

    private var stateJob: Job? = null

    fun start() {
        if (stateJob != null) return
        stateJob = scope.launch {
            sessionGraphProvider.currentGraph.collectLatest { graph ->
                graph.channelTransport.state.collect { transportState ->
                    stateFlow.value = ChannelDisplayMapper.build(
                        backendDescriptor = graph.backendDescriptor,
                        channelTransportState = transportState,
                    )
                }
            }
        }
    }

    fun refresh() {
        stateFlow.value = snapshotState()
    }

    override fun close() {
        stateJob?.cancel()
        stateJob = null
    }

    private fun snapshotState(): ChannelLibraryState =
        sessionGraphProvider.current.let { graph ->
            ChannelDisplayMapper.build(
                backendDescriptor = graph.backendDescriptor,
                channelTransportState = graph.channelTransport.state.value,
            )
        }
}
