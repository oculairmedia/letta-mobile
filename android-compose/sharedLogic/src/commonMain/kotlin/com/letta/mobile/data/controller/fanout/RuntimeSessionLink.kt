package com.letta.mobile.data.controller.fanout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Whether the App Server session behind a router is carrying frames right
 * now. Only a host whose router and turn engine outlive the socket (a stable
 * [com.letta.mobile.data.controller.reconnect.ReconnectingAppServerClient] pipe) ever leaves
 * [Linked]; a host that rebuilds its engine per connection ends turns through
 * [AppServerRuntimeEventRouter.detach] instead.
 */
sealed interface RuntimeSessionLink {
    /** Each restore after an interruption bumps [epoch]. */
    val epoch: Long

    data class Linked(override val epoch: Long) : RuntimeSessionLink

    /**
     * The socket dropped and the reconnect owner is redialling. Subscribers stay registered, so the
     * successor socket's frames reach the same turns; turns wait out
     * [com.letta.mobile.data.runtime.SESSION_RECONNECT_GRACE_MS] before failing.
     */
    data class Interrupted(override val epoch: Long, val reason: String?) : RuntimeSessionLink
}

/** The one writer of a router's [RuntimeSessionLink]. */
internal class RuntimeSessionLinkState {
    private val _link = MutableStateFlow<RuntimeSessionLink>(RuntimeSessionLink.Linked(epoch = 0))
    val link: StateFlow<RuntimeSessionLink> = _link.asStateFlow()

    /** Marks the session interrupted. A second interruption keeps the first one's epoch and reason. */
    fun interrupt(reason: String?) {
        _link.update { current ->
            current as? RuntimeSessionLink.Interrupted ?: RuntimeSessionLink.Interrupted(current.epoch, reason)
        }
    }

    /** Returns true when this call ended an interruption; false when the session was already linked. */
    fun restore(): Boolean {
        var restored = false
        _link.update { current ->
            restored = current is RuntimeSessionLink.Interrupted
            if (restored) RuntimeSessionLink.Linked(current.epoch + 1) else current
        }
        return restored
    }
}
