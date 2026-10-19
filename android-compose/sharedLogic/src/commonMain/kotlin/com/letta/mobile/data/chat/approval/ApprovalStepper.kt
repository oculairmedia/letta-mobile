package com.letta.mobile.data.chat.approval

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.chat.projection.requiresUserInput
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.runtime.ApprovalBinding

/**
 *  (F14, G47): several approvals waiting on the person at once are answered one
 * at a time, "1 of N", the way Letta Desktop steps through parallel tool calls, and submitted
 * together once the last is decided.
 *
 * A pure state machine: [pending] is what the timeline says waits on the person (oldest first), and
 * the decisions made so far are held here until [ApprovalSteps.ready]. A request that leaves
 * [pending] (answered elsewhere, its tool returned, the host dropped it) leaves the steps and takes
 * any decision held for it along, so a stale decision is never sent.
 */
@Immutable
data class ApprovalSteps(
    /** The requests to step through, oldest first. */
    val pending: List<UiApprovalRequest> = emptyList(),
    /** Decisions held for the batch, by request id. */
    val decided: Map<String, ApprovalStepDecision> = emptyMap(),
    /** The request shown now; the first undecided one unless the person stepped back. */
    val currentRequestId: String? = null,
    /** Requests whose decision already went out with a batch. */
    val submitted: Set<String> = emptySet(),
) {
    val total: Int get() = pending.size

    /** Stepping only applies when more than one request waits; a lone request is answered at once. */
    val stepping: Boolean get() = total > 1

    val current: UiApprovalRequest? get() = pending.firstOrNull { it.requestId == currentRequestId }

    /** 1-based position of [current], for the "1 of N" counter. */
    val position: Int get() = pending.indexOfFirst { it.requestId == currentRequestId } + 1

    /** Every request has a decision and none went out yet: the batch can be submitted. */
    val ready: Boolean get() = stepping && submitted.isEmpty() && pending.all { it.requestId in decided }

    fun canGoBack(): Boolean = position > 1

    fun canGoForward(): Boolean = position in 1 until total && currentRequestId in decided
}

/** One held decision: what [com.letta.mobile.ui.chat.session.ChatApprovalAnswer] will carry. */
@Immutable
data class ApprovalStepDecision(
    val requestId: String,
    val toolCallIds: List<String>,
    val approve: Boolean,
    val reason: String? = null,
    val selectedSuggestionIds: List<String> = emptyList(),
    val suggestionBinding: ApprovalBinding? = null,
)

object ApprovalStepper {
    /** The requests in [messages] that wait on the person, oldest first, one per request id. */
    fun pendingIn(messages: List<UiMessage>): List<UiApprovalRequest> {
        val answered = messages.mapNotNullTo(HashSet()) { it.approvalResponse?.requestId }
        return messages.asSequence()
            .mapNotNull { it.approvalRequest }
            .filter { it.requiresUserInput() && it.requestId !in answered }
            .distinctBy { it.requestId }
            .toList()
    }

    /** [steps] over a new [pending] list: departed requests and their decisions drop out. */
    fun reconcile(steps: ApprovalSteps, pending: List<UiApprovalRequest>): ApprovalSteps {
        val ids = pending.mapTo(HashSet()) { it.requestId }
        val decided = steps.decided.filterKeys { it in ids }
        val current = steps.currentRequestId?.takeIf { it in ids } ?: firstUndecided(pending, decided)
        return ApprovalSteps(pending, decided, current, steps.submitted.filterTo(HashSet()) { it in ids })
    }

    /** Holds [decision] for the current request and moves to the next undecided one. */
    fun decide(steps: ApprovalSteps, decision: ApprovalStepDecision): ApprovalSteps {
        if (steps.pending.none { it.requestId == decision.requestId } || decision.requestId in steps.submitted) return steps
        val decided = steps.decided + (decision.requestId to decision)
        val next = firstUndecided(steps.pending, decided, after = decision.requestId) ?: decision.requestId
        return steps.copy(decided = decided, currentRequestId = next)
    }

    fun back(steps: ApprovalSteps): ApprovalSteps {
        if (!steps.canGoBack()) return steps
        return steps.copy(currentRequestId = steps.pending[steps.position - 2].requestId)
    }

    fun forward(steps: ApprovalSteps): ApprovalSteps {
        if (!steps.canGoForward()) return steps
        return steps.copy(currentRequestId = steps.pending[steps.position].requestId)
    }

    /** The batch in the order the requests were made; empty until [ApprovalSteps.ready]. */
    fun batch(steps: ApprovalSteps): List<ApprovalStepDecision> =
        if (steps.ready) steps.pending.mapNotNull { steps.decided[it.requestId] } else emptyList()

    /** [batch], once: the steps it leaves mark every request in it submitted. */
    fun take(steps: ApprovalSteps): Pair<ApprovalSteps, List<ApprovalStepDecision>> {
        val batch = batch(steps)
        if (batch.isEmpty()) return steps to batch
        return steps.copy(submitted = batch.mapTo(HashSet()) { it.requestId }) to batch
    }

    private fun firstUndecided(
        pending: List<UiApprovalRequest>,
        decided: Map<String, ApprovalStepDecision>,
        after: String? = null,
    ): String? {
        val start = after?.let { id -> pending.indexOfFirst { it.requestId == id } + 1 } ?: 0
        val ordered = pending.drop(start) + pending.take(start)
        return ordered.firstOrNull { it.requestId !in decided }?.requestId
    }
}
