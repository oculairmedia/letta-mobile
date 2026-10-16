package com.letta.mobile.data.presence

import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus

/**
 * letta-mobile-te3n1.1: monotonic counts of the moments in a conversation's runs - a send, a tool
 * starting or ending, an approval being asked, a run completing, being cancelled or failing.
 *
 * The registry is a conflated state flow, so a consumer that only compares phases misses moments:
 * two completions inside one sample read as one, and a run that finishes and is followed at once
 * by the next send never shows its DONE. Counts survive sampling - a reader diffs them against the
 * last ones it saw - which is what lets the mascot nod at every send and celebrate every finish.
 */
data class RunEdges(
    /** The user sent (or re-sent) a message. */
    val sent: Int = 0,
    /** Tool calls that entered the in-flight set. */
    val toolsStarted: Int = 0,
    /** Tool calls that left the in-flight set (returned, denied, or ended with the turn). */
    val toolsEnded: Int = 0,
    /** Distinct approval requests the turn parked on. */
    val approvals: Int = 0,
    /** Runs that reached a successful terminal. */
    val completed: Int = 0,
    /** Runs that were cancelled. */
    val cancelled: Int = 0,
    /** Attempts that failed. */
    val failed: Int = 0,
) {
    operator fun plus(other: RunEdges): RunEdges = RunEdges(
        sent = sent + other.sent,
        toolsStarted = toolsStarted + other.toolsStarted,
        toolsEnded = toolsEnded + other.toolsEnded,
        approvals = approvals + other.approvals,
        completed = completed + other.completed,
        cancelled = cancelled + other.cancelled,
        failed = failed + other.failed,
    )

    companion object {
        val NONE = RunEdges()

        /**
         * The counts after [event] moved a conversation from [previous] to [next]. [rested] is
         * [previous] with a finished turn's counters dropped (what the reducer folded from), so a
         * tool counted open by the finished turn is not counted as ending twice.
         */
        internal fun after(
            previous: ConversationRunState,
            rested: ConversationRunState,
            next: ConversationRunState,
            event: RuntimeEventPayload,
        ): RunEdges {
            val delta = RunStep(previous, rested, next, event).delta()
            return if (delta == NONE) previous.edges else previous.edges + delta
        }
    }
}

/** One reducer step, read as the moments it was; each count is its own small rule. */
private class RunStep(
    private val previous: ConversationRunState,
    private val rested: ConversationRunState,
    private val next: ConversationRunState,
    private val event: RuntimeEventPayload,
) {
    fun delta(): RunEdges = RunEdges(
        sent = sent(),
        toolsStarted = next.openToolCallIds.count { it !in rested.openToolCallIds },
        toolsEnded = rested.openToolCallIds.count { it !in next.openToolCallIds },
        approvals = approvalAskedAnew().once(),
        completed = finishedAs(RuntimeRunStatus.Completed).once(),
        cancelled = finishedAs(RuntimeRunStatus.Cancelled).once(),
        failed = (previous.phase != RunPhase.FAILED && next.phase == RunPhase.FAILED).once(),
    )

    private fun sent(): Int = (event is RuntimeEventPayload.LocalUserAppend || event is RuntimeEventPayload.RetryRequested).once()

    private fun approvalAskedAnew(): Boolean =
        event is RuntimeEventPayload.ApprovalRequested && event.request.approvalId.value != previous.awaitingApprovalId

    /** A terminal re-reported (stop reason and turn-done both map to Completed) is one finish. */
    private val finishing: Boolean = previous.phase != RunPhase.DONE && next.phase == RunPhase.DONE

    private fun finishedAs(status: RuntimeRunStatus): Boolean =
        finishing && (event as? RuntimeEventPayload.RunLifecycleChanged)?.status == status

    private fun Boolean.once(): Int = if (this) 1 else 0
}
