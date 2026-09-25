package com.letta.mobile.data.runtime

import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.util.Telemetry

/**
 * letta-mobile-qygvv.16: the reason a turn cut off by a lost transport session fails with. It is
 * classified as the `connection_lost` failure family (see [terminalReasonKind]).
 */
internal const val SESSION_LOST_TERMINAL_REASON: String =
    "Connection lost during turn: the transport session to the App Server closed"

/**
 * letta-mobile-qygvv.16: ends [lease]'s turn because the session carrying it is gone (connection
 * closed, redial, wrapper restart), so no server terminal will ever reach it. The terminal goes
 * through [processor]'s own terminal path, which flushes the buffered stop reason and usage first
 * and never emits a second terminal when the real one already went out.
 */
internal suspend fun cutOffTurnForSessionLoss(
    processor: TurnDraftProcessor,
    command: TurnCommand,
    lease: LeaseRef,
): TurnCutOffOutcome {
    val outcome = processor.cutOff(command.failedDraft(SESSION_LOST_TERMINAL_REASON))
    Telemetry.event(
        "AppServerTurnEngine", "turn.cutoff_by_session_loss",
        "key" to lease.key.toString(),
        "leaseToken" to lease.token,
        "runId" to (lease.current?.runId ?: ""),
        "agentId" to command.agentId.value,
        "conversationId" to command.conversationId.value,
        "outcome" to outcome.name,
        level = Telemetry.Level.WARN,
    )
    return outcome
}
