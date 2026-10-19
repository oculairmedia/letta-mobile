package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.appserver.AppServerProbeResult

/**
 * Projects the reconnect owner's lifecycle onto [ChannelTransportState], the
 * one session-status model every UI reads (Android's Iroh supervisor projects onto it too, in
 * `IrohChannelTransport`). Replaces the parallel `AppServerSessionStatus` banner model, which
 * nothing consumed.
 *
 * [serverId] / [sessionId] label the Connected state. Terminal stops never report
 * `willReconnect`: [GiveUpKind.Authentication] is an auth failure (code 4001, as on Iroh), the
 * other kinds a plain disconnect the user has to retry.
 */
fun ReconnectingClientState.toChannelTransportState(
    serverId: String,
    sessionId: String = serverId,
): ChannelTransportState = when (this) {
    is ReconnectingClientState.Connecting ->
        ChannelTransportState.Connecting(reconnecting = attempt > 0, attempt = attempt)
    ReconnectingClientState.Recovering -> ChannelTransportState.Connecting(reconnecting = true)
    ReconnectingClientState.Ready -> ChannelTransportState.Connected(serverId = serverId, sessionId = sessionId, deviceId = null)
    is ReconnectingClientState.BackingOff -> ChannelTransportState.Disconnected(
        code = CLOSE_ABNORMAL,
        reason = reason.orEmpty(),
        willReconnect = true,
        reconnectAttempt = attempt + 1,
    )
    is ReconnectingClientState.GaveUp -> ChannelTransportState.Disconnected(
        code = closeCodeFor(kind),
        reason = reason.orEmpty(),
        isAuthFailure = kind == GiveUpKind.Authentication,
        willReconnect = false,
    )
    ReconnectingClientState.Stopped -> ChannelTransportState.Idle
}

private fun closeCodeFor(kind: GiveUpKind): Int = when (kind) {
    GiveUpKind.Authentication -> CLOSE_AUTH_FAILED
    GiveUpKind.Rejected -> CLOSE_POLICY_VIOLATION
    GiveUpKind.Incompatible -> CLOSE_INCOMPATIBLE
    GiveUpKind.Exhausted -> CLOSE_ABNORMAL
}

private const val CLOSE_ABNORMAL = 1006
private const val CLOSE_POLICY_VIOLATION = 1008
private const val CLOSE_AUTH_FAILED = 4001
private const val CLOSE_INCOMPATIBLE = 4426

/** A one-line English summary for desktop banners and logs. Android maps the kinds to resources. */
fun AppServerProbeResult.summary(): String = when (this) {
    is AppServerProbeResult.Ok ->
        "Connected: letta-code ${identity.lettaCodeVersion} (${identity.backend} backend, protocol ${identity.protocolVersion})"
    is AppServerProbeResult.Authentication -> "Authentication failed: $detail"
    is AppServerProbeResult.Incompatible -> "Incompatible server: $reason"
    is AppServerProbeResult.Unavailable -> "Server unavailable: $detail"
}
