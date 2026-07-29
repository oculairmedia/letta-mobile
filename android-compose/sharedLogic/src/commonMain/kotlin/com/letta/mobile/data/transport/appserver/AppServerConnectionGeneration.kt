package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.updateAndGet

/**
 * Coordinates the atomic lifecycle of one App Server connection generation
 * (letta-mobile-lgns8.21.1: one bidirectional WebSocket session).
 *
 * Letta Code ≥ 0.29.7 exposes a single `/ws` session (no `?channel=`). Readiness
 * begins [AppServerConnectionState.Disconnected] and becomes
 * [AppServerConnectionState.Ready] once that socket is open. Close or failure
 * moves the generation to [AppServerConnectionState.Failed] exactly once and
 * invokes [onTeardown] so the transport can fail pending work.
 *
 * Pure and platform-neutral: all transitions are lock-free atomic updates of a
 * single [MutableStateFlow], and mutators are non-suspending so they are safe to
 * call from a coroutine's `finally` block during cancellation.
 */
class AppServerConnectionGeneration(
    private val onTeardown: (reason: String?) -> Unit = {},
) {
    private data class Internal(
        val connecting: Boolean = false,
        val sessionOpen: Boolean = false,
        val finished: Boolean = false,
        val failure: AppServerConnectionState.Failed? = null,
    )

    private val internal = MutableStateFlow(Internal())
    private val _state = MutableStateFlow<AppServerConnectionState>(AppServerConnectionState.Disconnected)
    val state: StateFlow<AppServerConnectionState> = _state.asStateFlow()

    /** Signals that the generation has begun opening its socket. */
    fun markConnecting() {
        publish(internal.updateAndGet { if (it.finished) it else it.copy(connecting = true) })
    }

    /** Records that the bidirectional session socket is open. */
    fun onSessionOpen() {
        publish(
            internal.updateAndGet {
                if (it.finished) it else it.copy(sessionOpen = true)
            },
        )
    }

    /**
     * Records that the session closed or failed. The first such call finalizes the
     * generation as [AppServerConnectionState.Failed] and triggers [onTeardown];
     * subsequent calls are no-ops.
     */
    fun onSessionClosedOrFailed(terminal: Boolean, reason: String?) {
        val previous = internal.getAndUpdate {
            if (it.finished) it else it.copy(finished = true, failure = AppServerConnectionState.Failed(terminal, reason))
        }
        if (!previous.finished) {
            publish(internal.value)
            onTeardown(reason)
        }
    }

    private fun publish(snapshot: Internal) {
        _state.value = when {
            snapshot.failure != null -> snapshot.failure
            snapshot.sessionOpen -> AppServerConnectionState.Ready
            snapshot.connecting -> AppServerConnectionState.Connecting
            else -> AppServerConnectionState.Disconnected
        }
    }
}
