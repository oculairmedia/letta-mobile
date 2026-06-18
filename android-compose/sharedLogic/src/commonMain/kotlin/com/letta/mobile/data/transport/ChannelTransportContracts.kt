package com.letta.mobile.data.transport

sealed interface A2uiActionDispatchResult {
    data class Sent(val frameId: String) : A2uiActionDispatchResult
    data class Queued(val frameId: String) : A2uiActionDispatchResult
    data object Failed : A2uiActionDispatchResult
}

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
