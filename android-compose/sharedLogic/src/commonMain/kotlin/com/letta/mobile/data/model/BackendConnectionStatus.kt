package com.letta.mobile.data.model

/** Where the client's App Server connection stands, as the shell shows it. */
sealed interface BackendConnectionStatus {
    data object Unconfigured : BackendConnectionStatus

    data object Connecting : BackendConnectionStatus

    /** Connected over [transport] ("WebSocket", "Iroh"). */
    data class Connected(val transport: String) : BackendConnectionStatus

    data class Failed(val message: String) : BackendConnectionStatus
}

/**
 * The connection the shell shows: unconfigured without [config], failed on a session [error], connecting
 * until agents are loaded, then connected.
 */
fun backendConnectionStatus(config: LettaConfig?, error: Throwable?, agentsLoaded: Boolean): BackendConnectionStatus = when {
    config == null -> BackendConnectionStatus.Unconfigured
    error != null -> BackendConnectionStatus.Failed(error.message ?: "Couldn't reach the backend")
    !agentsLoaded -> BackendConnectionStatus.Connecting
    else -> BackendConnectionStatus.Connected(config.toBackendLabel().orEmpty())
}
