package com.letta.mobile.data.transport

sealed interface ChannelTransportState {
    data object Idle : ChannelTransportState
    data class Connecting(
        val reconnecting: Boolean = false,
        val attempt: Int = 0,
    ) : ChannelTransportState
    data class Connected(
        val serverId: String,
        val sessionId: String,
        val deviceId: String?,
        val a2uiEnabled: Boolean = false,
        val a2uiVersion: String? = null,
        val a2uiCatalog: String? = null,
        val canonicalLiveTransport: String? = null,
        val a2uiSupportedCatalogs: List<String> = emptyList(),
        val a2uiSupportedWidgets: List<String> = emptyList(),
    ) : ChannelTransportState

    data class Disconnected(
        val code: Int,
        val reason: String,
        val isAuthFailure: Boolean = false,
        val willReconnect: Boolean = false,
        val reconnectAttempt: Int = 0,
    ) : ChannelTransportState
}

object ChannelTransportDefaults {
    const val DEFAULT_CRON_TIMEOUT_MS: Long = 5_000L
}

/**
 * A dial failure no redial can fix: the connection supervisor stops
 * redialling and reports the failure's message as a final disconnect. The on-device runtime's
 * single launch ending is one; only restarting the app recovers from it.
 */
interface TerminalConnectionFailure
