package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.fanout.isRuntimeStreamDetached
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive

/**
 * letta-mobile-qygvv.16: the reason a turn cut off by a lost transport session fails with. It is
 * classified as the `connection_lost` failure family (see [terminalReasonKind]).
 */
internal const val SESSION_LOST_TERMINAL_REASON: String =
    "Connection lost during turn: the transport session to the App Server closed"

/**
 * letta-mobile-qygvv.16: ends [lease]'s turn when the session carrying it is gone (connection
 * closed, redial, wrapper restart), because no server terminal will ever reach it then. The
 * terminal goes through [processor]'s own terminal path, which flushes the buffered stop reason
 * and usage first and never emits a second terminal when the real one already went out.
 */
internal class SessionLossCutOff(
    private val processor: TurnDraftProcessor,
    private val command: TurnCommand,
    private val lease: LeaseRef,
) {
    /** [events], ending the turn instead of failing when the stream detaches (see [endIfSessionLost]). */
    fun endOnDetach(events: Flow<AppServerReceivedFrame>): Flow<AppServerReceivedFrame> =
        events.catch { cause ->
            if (cause is CancellationException) endIfSessionLost(cause)
            throw cause
        }

    /**
     * When [cause] is the turn stream detaching (not a cancellation of the turn itself), publishes
     * the turn's terminal and completes the turn; otherwise returns without doing anything.
     */
    private suspend fun endIfSessionLost(cause: CancellationException) {
        if (!cause.isRuntimeStreamDetached() || !currentCoroutineContext().isActive) return
        processor.cutOff(command.failedDraft(SESSION_LOST_TERMINAL_REASON), ::report)
    }

    private fun report(outcome: TurnCutOffOutcome) {
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
    }
}
