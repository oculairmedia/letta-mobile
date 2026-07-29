package com.letta.mobile.data.transport.appserver

/**
 * Explicit lifecycle of one App Server transport connection generation
 * (letta-mobile-lgns8.21.1).
 *
 * A "generation" is one bidirectional WebSocket session: readiness begins
 * [Disconnected] (never optimistically connected), advances to [Ready] once the
 * session socket is open, and any close/failure tears the generation into
 * [Failed].
 */
sealed interface AppServerConnectionState {
    /** No session established yet (initial state — not optimistically connected). */
    data object Disconnected : AppServerConnectionState

    /** The session socket is opening but the generation is not fully ready. */
    data object Connecting : AppServerConnectionState

    /** The bidirectional session socket is open; the generation may be used. */
    data object Ready : AppServerConnectionState

    /**
     * The generation was torn down. [terminal] distinguishes an auth/config
     * failure that must not be blindly retried from a transient/retryable drop.
     */
    data class Failed(val terminal: Boolean, val reason: String?) : AppServerConnectionState

    val isReady: Boolean get() = this is Ready
}
