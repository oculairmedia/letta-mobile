package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.transport.appserver.AppServerProbeResult

/**
 * What a connection banner shows for one App Server session (letta-mobile-bzvro.2, F02).
 *
 * The reference client's status model: connecting, connected, reconnecting, authentication-error,
 * incompatible, connection-error and disabled. [AuthenticationError] and [Incompatible] are
 * terminal: nothing retries until the user changes settings or presses Retry, so the banner must
 * say what to fix instead of spinning.
 */
sealed interface AppServerSessionStatus {
    data object Connecting : AppServerSessionStatus

    data object Connected : AppServerSessionStatus

    data class Reconnecting(val attempt: Int, val nextDelayMs: Long, val reason: String?) : AppServerSessionStatus

    data class AuthenticationError(val detail: String) : AppServerSessionStatus

    data class Incompatible(val detail: String) : AppServerSessionStatus

    /** Retrying stopped on transient failures; Retry may help later. */
    data class ConnectionError(val detail: String) : AppServerSessionStatus

    data object Disabled : AppServerSessionStatus

    /** True when automatic retry has stopped and the user has to act. */
    val needsUserAction: Boolean
        get() = this is AuthenticationError || this is Incompatible || this is ConnectionError
}

/** Projects the supervisor's lifecycle onto the banner's status model. */
fun ReconnectingClientState.toSessionStatus(): AppServerSessionStatus = when (this) {
    is ReconnectingClientState.Connecting ->
        if (attempt == 0) AppServerSessionStatus.Connecting else AppServerSessionStatus.Reconnecting(attempt, 0, null)
    ReconnectingClientState.Recovering -> AppServerSessionStatus.Connecting
    ReconnectingClientState.Ready -> AppServerSessionStatus.Connected
    is ReconnectingClientState.BackingOff -> AppServerSessionStatus.Reconnecting(attempt + 1, delayMs, reason)
    is ReconnectingClientState.GaveUp -> when (kind) {
        GiveUpKind.Authentication -> AppServerSessionStatus.AuthenticationError(reason.orEmpty())
        GiveUpKind.Incompatible -> AppServerSessionStatus.Incompatible(reason.orEmpty())
        GiveUpKind.Rejected, GiveUpKind.Exhausted -> AppServerSessionStatus.ConnectionError(reason.orEmpty())
    }
    ReconnectingClientState.Stopped -> AppServerSessionStatus.Disabled
}

/**
 * The terminal status a failed probe implies, or null when the failure is transient (or the probe
 * passed) and reconnecting may continue.
 */
fun AppServerProbeResult.terminalSessionStatus(): AppServerSessionStatus? = when (this) {
    is AppServerProbeResult.Authentication -> AppServerSessionStatus.AuthenticationError(detail)
    is AppServerProbeResult.Incompatible -> AppServerSessionStatus.Incompatible(reason)
    is AppServerProbeResult.Ok, is AppServerProbeResult.Unavailable -> null
}

/** A one-line English summary for desktop banners and logs. Android maps the kinds to resources. */
fun AppServerProbeResult.summary(): String = when (this) {
    is AppServerProbeResult.Ok ->
        "Connected: letta-code ${identity.lettaCodeVersion} (${identity.backend} backend, protocol ${identity.protocolVersion})"
    is AppServerProbeResult.Authentication -> "Authentication failed: $detail"
    is AppServerProbeResult.Incompatible -> "Incompatible server: $reason"
    is AppServerProbeResult.Unavailable -> "Server unavailable: $detail"
}
