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
 * other kinds a plain disconnect the user has to retry. [connectionEpoch] labels the Connected state with
 * which connection of its owner it is (the caller counts them; this projection holds no state).
 */
fun ReconnectingClientState.toChannelTransportState(
    serverId: String,
    sessionId: String = serverId,
    connectionEpoch: Long = 0,
): ChannelTransportState = when (this) {
    is ReconnectingClientState.Connecting ->
        ChannelTransportState.Connecting(reconnecting = attempt > 0, attempt = attempt)
    ReconnectingClientState.Recovering -> ChannelTransportState.Connecting(reconnecting = true)
    ReconnectingClientState.Ready -> ChannelTransportState.Connected(
        serverId = serverId, sessionId = sessionId, deviceId = null, connectionEpoch = connectionEpoch,
    )
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

/**
 * Numbers one session's connections for [toChannelTransportState]: each entry into Ready is a new one, so a
 * redial of the same server still reads as a different connection to a collector that never saw the drop.
 * Not thread-safe: feed it from one collector.
 */
class ReconnectingConnectionEpochs {
    private var epoch = 0L
    private var wasReady = false

    /** [state] as a channel transport state, with this session's current connection epoch. */
    fun project(state: ReconnectingClientState, serverId: String): ChannelTransportState {
        val ready = state is ReconnectingClientState.Ready
        if (ready && !wasReady) epoch += 1
        wasReady = ready
        return state.toChannelTransportState(serverId = serverId, connectionEpoch = epoch)
    }
}
