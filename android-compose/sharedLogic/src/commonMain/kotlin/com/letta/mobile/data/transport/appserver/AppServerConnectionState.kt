package com.letta.mobile.data.transport.appserver

/**
 * Explicit lifecycle of one App Server transport connection generation
 * (letta-mobile-lgns8.2).
 *
 * A "generation" is the control + stream WebSocket pair treated as a single
 * atomic unit: readiness begins [Disconnected] (never optimistically connected),
 * advances to [Ready] only when BOTH sockets are open, and any single-socket
 * failure tears down the whole generation into [Failed].
 */
sealed interface AppServerConnectionState {
    /** No sockets established yet (initial state — not optimistically connected). */
    data object Disconnected : AppServerConnectionState

    /** At least one socket is opening but the generation is not fully ready. */
    data object Connecting : AppServerConnectionState

    /** Both control and stream sockets are open; the generation may be used. */
    data object Ready : AppServerConnectionState

    /**
     * The generation was torn down. [terminal] distinguishes an auth/config
     * failure that must not be blindly retried from a transient/retryable drop.
     */
    data class Failed(val terminal: Boolean, val reason: String?) : AppServerConnectionState

    val isReady: Boolean get() = this is Ready
}
