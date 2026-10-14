package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.fanout.isRuntimeStreamDetached
import com.letta.mobile.data.controller.fanout.isRuntimeSubscriberOverflow
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
 * letta-mobile-by4vm.4: the reason a turn fails with when the fanout detached its subscriber for
 * falling a full buffer behind. The session is fine and the server run continues, but this turn's
 * stream has a gap, so it ends exactly like a lost session (same `connection_lost` family, so the
 * same retry/resync affordance) with a reason that says what actually happened.
 */
internal const val SUBSCRIBER_OVERFLOW_TERMINAL_REASON: String =
    "Connection lost during turn: this client fell behind the App Server event stream and was detached"

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
        val overflow = cause.isRuntimeSubscriberOverflow()
        val reason = if (overflow) SUBSCRIBER_OVERFLOW_TERMINAL_REASON else SESSION_LOST_TERMINAL_REASON
        processor.cutOff(command.failedDraft(reason)) { report(it, overflow) }
    }

    private fun report(outcome: TurnCutOffOutcome, overflow: Boolean) {
        Telemetry.event(
            "AppServerTurnEngine",
            if (overflow) "turn.cutoff_by_subscriber_overflow" else "turn.cutoff_by_session_loss",
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
