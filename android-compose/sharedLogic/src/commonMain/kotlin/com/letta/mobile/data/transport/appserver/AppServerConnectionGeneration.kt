package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.updateAndGet

/**
 * Coordinates the atomic lifecycle of one App Server connection generation
 * (letta-mobile-lgns8.2): both the control and stream sockets belong to a single
 * generation whose [state] starts [AppServerConnectionState.Disconnected] and
 * only becomes [AppServerConnectionState.Ready] once BOTH sockets are open. The
 * FIRST socket to close or fail moves the generation to
 * [AppServerConnectionState.Failed] exactly once and invokes [onTeardown] so the
 * transport can cancel the sibling socket and fail pending work.
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
        val controlOpen: Boolean = false,
        val streamOpen: Boolean = false,
        val finished: Boolean = false,
        val failure: AppServerConnectionState.Failed? = null,
    )

    private val internal = MutableStateFlow(Internal())
    private val _state = MutableStateFlow<AppServerConnectionState>(AppServerConnectionState.Disconnected)
    val state: StateFlow<AppServerConnectionState> = _state.asStateFlow()

    /** Signals that the generation has begun opening its sockets. */
    fun markConnecting() {
        publish(internal.updateAndGet { if (it.finished) it else it.copy(connecting = true) })
    }

    /** Records that [channel]'s socket is open; readiness requires both channels. */
    fun onChannelOpen(channel: AppServerChannel) {
        publish(
            internal.updateAndGet {
                if (it.finished) {
                    it
                } else {
                    when (channel) {
                        AppServerChannel.Control -> it.copy(controlOpen = true)
                        AppServerChannel.Stream -> it.copy(streamOpen = true)
                    }
                }
            },
        )
    }

    /**
     * Records that [channel] closed or failed. The first such call finalizes the
     * whole generation as [AppServerConnectionState.Failed] and triggers
     * [onTeardown]; subsequent calls (e.g. the sibling socket being cancelled as
     * a result) are no-ops.
     */
    fun onChannelClosedOrFailed(terminal: Boolean, reason: String?) {
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
            snapshot.controlOpen && snapshot.streamOpen -> AppServerConnectionState.Ready
            snapshot.connecting || snapshot.controlOpen || snapshot.streamOpen -> AppServerConnectionState.Connecting
            else -> AppServerConnectionState.Disconnected
        }
    }
}
