package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.withTimeoutOrNull

/**
 * letta-mobile-qygvv.9: cancelling a lease whose input is still QUEUED on the App Server.
 *
 * [OrphanedTurnAborter] never aborts a Queued lease: it has no run of its own, and an abort would
 * hit the turn ahead (another viewer's) and pause the queue. But the cancelled lease's
 * `create_message` would stay parked and run later with nobody observing it, so this removes it
 * with `remove_queue_item` instead. A handover or a lost connection is not a cancellation of the
 * input: the turn is still wanted (or the server drops it on socket close itself).
 */
internal class QueuedInputRemoval(
    private val remove: suspend (AppServerRuntimeScope, String) -> Boolean,
    private val timeoutMs: Long = REMOVE_TIMEOUT_MS,
) {
    fun shouldRemove(facts: LeaseReleaseFacts): Boolean =
        facts.phase == TurnLeasePhase.Queued &&
            facts.releaseReason == CANCELLATION &&
            !facts.generationSuperseded &&
            facts.releaseCause?.isTurnReleaseWithoutAbort() != true

    /** Removes [clientMessageId] when [shouldRemove]; never throws, bounded by [timeoutMs]. */
    @Suppress("CancellationMustPropagate") // NonCancellable release cleanup: nothing may escape or the lease leaks.
    suspend fun removeIfQueued(facts: LeaseReleaseFacts, runtime: AppServerRuntimeScope, clientMessageId: String?) {
        if (clientMessageId.isNullOrBlank() || !shouldRemove(facts)) return
        val outcome = try {
            withTimeoutOrNull(timeoutMs.milliseconds) { remove(runtime, clientMessageId) }
                ?.let { removed -> if (removed) "removed" else "not_removed" }
                ?: "timeout"
        } catch (error: Throwable) {
            "failed=${error::class.simpleName}"
        }
        Telemetry.event(
            "AppServerTurnEngine", "activeTurn.queuedInputRemoved",
            "key" to facts.key.toString(),
            "leaseToken" to facts.leaseToken,
            "clientMessageId" to clientMessageId,
            "outcome" to outcome,
            level = if (outcome == "removed") Telemetry.Level.INFO else Telemetry.Level.WARN,
        )
    }

    private companion object {
        const val CANCELLATION = "cancellation"
        const val REMOVE_TIMEOUT_MS = 5_000L
    }
}
