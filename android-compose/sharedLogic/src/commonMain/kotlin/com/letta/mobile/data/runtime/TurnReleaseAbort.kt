package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.withTimeoutOrNull

/**
 * letta-mobile-qygvv.3: a cancellation that ends a turn lease WITHOUT the server turn needing an
 * `abort_message`, because the server turn is already over, unreachable, or owned elsewhere.
 *
 * Any other cancellation of a lease that saw no server terminal (a user cancel, a host scope
 * closing) aborts the server turn, so the server and the client agree the turn is over.
 */
open class TurnReleaseCancellation(message: String) : CancellationException(message)

/** A host stops observing a turn that keeps running under another owner: do not abort it. */
class TurnHandoverCancellation(message: String = "turn handed over") : TurnReleaseCancellation(message)

/** The lease's connection generation is gone; the server cancels an unobserved turn on WS close. */
internal class ConnectionSupersededCancellation(message: String) : TurnReleaseCancellation(message)

/** The busy-path reconcile proved the server loop idle; there is no server turn to abort. */
internal class DeadOwnerReleasedCancellation :
    TurnReleaseCancellation("owner released: App Server loop is waiting on input")

internal fun Throwable.isTurnReleaseWithoutAbort(): Boolean {
    var cause: Throwable? = this
    var depth = 0
    while (cause != null && depth < MAX_CAUSE_DEPTH) {
        if (cause is TurnReleaseCancellation) return true
        cause = cause.cause
        depth += 1
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 8

/** What the lease looked like at release, for the orphan-abort decision. */
internal data class LeaseReleaseFacts(
    val key: TurnRuntimeKey,
    val leaseToken: Long,
    val releaseReason: String,
    val releaseCause: Throwable?,
    val runId: String?,
    val lastTerminal: String?,
    val lastTerminalSource: String?,
    val generationSuperseded: Boolean,
    val abortAlreadyRequested: Boolean,
)

/**
 * letta-mobile-qygvv.3: never leave a server turn running with nobody observing it.
 *
 * When a lease ends without a server terminal (idle watchdog, stream error, or a cancellation
 * that is not a handover), the server may still be running the turn. Its approvals then block
 * the conversation queue and every later input queues behind it. This sends `abort_message` for
 * that runtime through the engine's keyed abort, BEFORE the lease is released, so no successor
 * input can queue behind the orphan (an abort also pauses the server queue until the next input).
 */
internal class OrphanedTurnAborter(
    private val abort: suspend (agentId: String, conversationId: String, runId: String?) ->
        AppServerInboundFrame.AbortMessageResponse,
    private val noteSettled: (TurnRuntimeKey, String?) -> Unit,
    private val timeoutMs: Long = ORPHAN_ABORT_TIMEOUT_MS,
) {
    fun shouldAbort(facts: LeaseReleaseFacts): Boolean {
        if (facts.releaseReason !in ABORTING_REASONS) return false
        if (facts.generationSuperseded || facts.abortAlreadyRequested) return false
        if (facts.lastTerminal != null && facts.lastTerminalSource !in LOCAL_TERMINAL_SOURCES) return false
        return facts.releaseCause?.isTurnReleaseWithoutAbort() != true
    }

    /** Sends the abort when [shouldAbort]; never throws, bounded by [timeoutMs]. */
    @Suppress("CancellationMustPropagate") // NonCancellable release cleanup: nothing may escape or the lease leaks.
    suspend fun abortIfOrphaned(facts: LeaseReleaseFacts) {
        if (!shouldAbort(facts)) return
        // A late terminal for the aborted run must not end the next turn on this key.
        noteSettled(facts.key, facts.runId)
        val outcome = try {
            withTimeoutOrNull(timeoutMs.milliseconds) {
                abort(facts.key.agentId, facts.key.conversationId, facts.runId)
            }?.let { response ->
                if (response.success) "aborted=${response.aborted}" else "error=${response.error.orEmpty()}"
            } ?: "timeout"
        } catch (error: Throwable) {
            // Runs in the lease's NonCancellable release path: nothing may escape it, or the
            // lease would never be released.
            "failed=${error::class.simpleName}"
        }
        Telemetry.event(
            "AppServerTurnEngine", "activeTurn.orphanAbort",
            "key" to facts.key.toString(),
            "leaseToken" to facts.leaseToken,
            "releaseReason" to facts.releaseReason,
            "runId" to (facts.runId ?: "<none>"),
            "outcome" to outcome,
            level = if (outcome.startsWith("aborted=")) Telemetry.Level.INFO else Telemetry.Level.WARN,
        )
    }

    companion object {
        const val ORPHAN_ABORT_TIMEOUT_MS: Long = 5_000L
        private val ABORTING_REASONS = setOf("watchdog_timeout", "stream_error", "cancellation")

        /** Terminals the engine synthesized itself; they are not evidence the server turn ended. */
        private val LOCAL_TERMINAL_SOURCES = setOf("idle_timeout", "input_rejected")
    }
}
