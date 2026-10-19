package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.fanout.RuntimeSessionLink
import com.letta.mobile.data.controller.fanout.isRuntimeStreamDetached
import com.letta.mobile.data.controller.fanout.isRuntimeSubscriberOverflow
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * The reason a turn cut off by a lost transport session fails with. It is
 * classified as the `connection_lost` failure family (see [terminalReasonKind]).
 */
internal const val SESSION_LOST_TERMINAL_REASON: String =
    "Connection lost during turn: the transport session to the App Server closed"

/**
 * The reason a turn fails with when the fanout detached its subscriber for
 * falling a full buffer behind. The session is fine and the server run continues, but this turn's
 * stream has a gap, so it ends exactly like a lost session (same `connection_lost` family, so the
 * same retry/resync affordance) with a reason that says what actually happened.
 */
internal const val SUBSCRIBER_OVERFLOW_TERMINAL_REASON: String =
    "Connection lost during turn: this client fell behind the App Server event stream and was detached"

/**
 * How long a turn waits, keeping its lease and its fanout subscription, for
 * an interrupted App Server session to come back before it fails with
 * [SESSION_LOST_TERMINAL_REASON]. The server keeps running the turn meanwhile.
 *
 * 30 s covers a Wi-Fi blip or a roaming hand-off plus about six full-jitter redials of
 * [com.letta.mobile.data.controller.reconnect.FullJitterBackoff] (250 ms base, doubling; the
 * ceilings add up to ~32 s by the seventh attempt), and stays well under the 300 s turn idle
 * watchdog, so a dead session still fails with its own connection_lost reason.
 */
const val SESSION_RECONNECT_GRACE_MS: Long = 30_000L

/**
 * Ends [lease]'s turn when the session carrying it is gone (connection
 * closed, redial, wrapper restart), because no server terminal will ever reach it then. The
 * terminal goes through [processor]'s own terminal path, which flushes the buffered stop reason
 * and usage first and never emits a second terminal when the real one already went out.
 *
 * two ways a session goes away, handled differently.
 *  - The stream detaches ([endOnDetach]): the router was torn down for good, or this subscriber
 *    overflowed. Nothing will reconnect it, so the turn ends at once.
 *  - The session is interrupted ([watchReconnect], on a router whose [link] outlives the socket):
 *    the turn is reconnecting. It keeps its lease and subscription; if the link is restored within
 *    [graceMs] the successor socket's frames continue the turn, otherwise it fails here.
 */
internal class SessionLossCutOff(
    private val processor: TurnDraftProcessor,
    private val command: TurnCommand,
    private val lease: LeaseRef,
    private val link: StateFlow<RuntimeSessionLink>? = null,
    private val graceMs: Long = SESSION_RECONNECT_GRACE_MS,
) {
    /**
     * Fails the turn when [link] stays interrupted for [graceMs]; a restore inside the window
     * cancels the countdown and a later interruption starts a fresh one. Its failure completes the
     * turn through the collected flow, like any other cut-off; null without a link.
     */
    private fun watchReconnect(scope: CoroutineScope): Job? {
        val sessionLink = link ?: return null
        return scope.launch {
            sessionLink.collectLatest { state ->
                if (state !is RuntimeSessionLink.Interrupted) return@collectLatest
                reportReconnecting(state)
                delay(graceMs.milliseconds)
                processor.cutOff(command.failedDraft(SESSION_LOST_TERMINAL_REASON)) { report(it, overflow = false) }
            }
        }
    }

    private fun reportReconnecting(state: RuntimeSessionLink.Interrupted) {
        Telemetry.event(
            "AppServerTurnEngine",
            "turn.reconnecting",
            "key" to lease.key.toString(),
            "runId" to (lease.current?.runId ?: ""),
            "epoch" to state.epoch,
            "reason" to (state.reason ?: ""),
            "graceMs" to graceMs,
        )
    }

    /**
     * [events], ending the turn instead of failing when the stream detaches (see [endIfSessionLost]),
     * with the reconnect countdown ([watchReconnect]) running for as long as they are collected.
     */
    fun endOnDetach(events: Flow<AppServerReceivedFrame>): Flow<AppServerReceivedFrame> =
        flow {
            coroutineScope {
                val reconnectWatch = watchReconnect(this)
                try {
                    emitAll(events)
                } finally {
                    reconnectWatch?.cancel()
                }
            }
        }.catch { cause ->
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
