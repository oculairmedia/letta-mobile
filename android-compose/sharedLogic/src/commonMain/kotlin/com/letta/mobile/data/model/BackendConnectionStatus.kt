package com.letta.mobile.data.model

/** Where the client's App Server connection stands, as the shell shows it. */
sealed interface BackendConnectionStatus {
    data object Unconfigured : BackendConnectionStatus

    data object Connecting : BackendConnectionStatus

    /** Connected over [transport] ("WebSocket", "Iroh"). */
    data class Connected(val transport: String) : BackendConnectionStatus

    data class Failed(val message: String) : BackendConnectionStatus
}
