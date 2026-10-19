package com.letta.mobile.data.a2ui

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** One tool call of one approval request: the pair an A2UI answer must name. */
data class ToolApprovalRef(val approvalId: ToolApprovalId, val callId: ToolCallId) {
    companion object {
        /** The ref for wire/timeline ids, or null when either is blank. */
        fun of(approvalRequestId: String?, callId: String?): ToolApprovalRef? {
            if (approvalRequestId.isNullOrBlank() || callId.isNullOrBlank()) return null
            return ToolApprovalRef(ToolApprovalId(approvalRequestId), ToolCallId(callId))
        }
    }
}

/** A decision a pending approval accepts. */
enum class ToolApprovalDecision {
    Approve,
    Deny,
    ;

    companion object {
        val All: Set<ToolApprovalDecision> = entries.toSet()
    }
}

/** Why an A2UI approval answer was refused; logged, never submitted. */
enum class ToolApprovalRejection {
    /** Not built by the client's ToolApprovalCard renderer: a server-authored Button or similar. */
    NotFromApprovalCard,

    /** Missing request id, call id, or a recognisable decision. */
    Malformed,

    /** No approval with that request id is pending in this client. */
    UnknownRequest,

    /** The request is pending, but not for that tool call. */
    CallMismatch,

    /** The request is pending in another conversation than the one the answer was made in. */
    ConversationMismatch,

    /** The pending approval does not offer that decision. */
    DecisionNotAllowed,

    /** Already answered once, or its turn ended. */
    Retired,
}

/** What [PendingToolApprovalRegistry.admit] made of a `tool_approval_response`. */
sealed interface ToolApprovalAdmission {
    /** Submit [submission] to [conversation] (the conversation the client registered, not the action's own). */
    data class Admitted(
        val submission: A2uiToolApprovalSubmission,
        val conversation: ConversationId,
    ) : ToolApprovalAdmission

    data class Rejected(val reason: ToolApprovalRejection) : ToolApprovalAdmission
}

/**
 * The tool approvals this client itself is showing as pending, and the only
 * gate an A2UI `tool_approval_response` passes on its way to becoming a real approval.
 *
 * TRUST MODEL. A2UI surfaces are authored by the agent's side of the wire, so an action's name and
 * context prove nothing: a prompt-injected agent can draw a plain Button named
 * `tool_approval_response` that carries its own pending call's ids. An answer is admitted only when
 *  1. the client's ToolApprovalCard renderer built it ([A2uiActionOrigin.ToolApprovalCard], which
 *     no wire payload can set), so a person tapped an approval affordance; and
 *  2. its request id and call id name an approval the client registered from its own timeline
 *     projection (control_request / approval request), in the conversation the client is showing; and
 *  3. that pair has not been answered already, nor its turn ended.
 * Admission consumes the pair, so one tap submits once.
 *
 * Entries come from [register] or, usually, [sync] with the timeline's pending set; a pair that
 * leaves the pending set is forgotten. [expire] retires a conversation's pairs at turn end. Retired
 * pairs stay refused until the timeline stops listing them, so a lagging projection cannot revive
 * them.
 */
class PendingToolApprovalRegistry(private val retiredCap: Int = MAX_RETIRED) {
    private data class Entry(val conversation: ConversationId, val allowedDecisions: Set<ToolApprovalDecision>)

    private val lock = SynchronizedObject()
    private val pending = LinkedHashMap<ToolApprovalRef, Entry>()

    /** Answered or expired pairs, by conversation; insertion-ordered so the oldest is evicted first. */
    private val retired = LinkedHashMap<ToolApprovalRef, ConversationId>()

    fun register(
        conversation: ConversationId,
        ref: ToolApprovalRef,
        allowedDecisions: Set<ToolApprovalDecision> = ToolApprovalDecision.All,
    ) = synchronized(lock) {
        if (ref !in retired) pending[ref] = Entry(conversation, allowedDecisions)
    }

    /**
     * Makes [conversation]'s entries exactly [now], minus anything already retired; retired pairs
     * no longer pending are forgotten.
     */
    fun sync(conversation: ConversationId, now: Set<ToolApprovalRef>) = synchronized(lock) {
        pending.entries.removeAll { (ref, entry) -> entry.conversation == conversation && ref !in now }
        retired.entries.removeAll { (ref, owner) -> owner == conversation && ref !in now }
        now.filter { it !in retired && it !in pending }
            .forEach { pending[it] = Entry(conversation, ToolApprovalDecision.All) }
    }

    /** Turn end: every pending approval in [conversation] can no longer be answered from A2UI. */
    fun expire(conversation: ConversationId) = synchronized(lock) {
        val ended = pending.filterValues { it.conversation == conversation }.keys
        ended.forEach { retireLocked(it, conversation) }
    }

    /**
     * Admits or refuses [action], answered while the client showed [conversation]; null when it
     * is not a `tool_approval_response` at all. Refusals are logged here.
     */
    fun admit(action: A2uiAction, conversation: ConversationId?): ToolApprovalAdmission? {
        if (!action.isToolApprovalResponse) return null
        val admission = synchronized(lock) { admitLocked(action, conversation) }
        when (admission) {
            is ToolApprovalAdmission.Rejected -> logRejection(action, admission.reason)
            is ToolApprovalAdmission.Admitted -> logUnpersistedScope(admission.submission)
        }
        return admission
    }

    private fun admitLocked(action: A2uiAction, conversation: ConversationId?): ToolApprovalAdmission {
        if (action.origin != A2uiActionOrigin.ToolApprovalCard) return rejected(ToolApprovalRejection.NotFromApprovalCard)
        val submission = action.toToolApprovalSubmission() ?: return rejected(ToolApprovalRejection.Malformed)
        val key = submission.ref
        val entry = pending[key] ?: return rejected(missingReason(key))
        val reason = when {
            entry.conversation != conversation -> ToolApprovalRejection.ConversationMismatch
            submission.decision !in entry.allowedDecisions -> ToolApprovalRejection.DecisionNotAllowed
            else -> null
        }
        if (reason != null) return rejected(reason)
        retireLocked(key, entry.conversation)
        return ToolApprovalAdmission.Admitted(submission, entry.conversation)
    }

    private fun missingReason(key: ToolApprovalRef): ToolApprovalRejection = when {
        key in retired -> ToolApprovalRejection.Retired
        pending.keys.any { it.approvalId == key.approvalId } -> ToolApprovalRejection.CallMismatch
        else -> ToolApprovalRejection.UnknownRequest
    }

    private fun retireLocked(key: ToolApprovalRef, conversation: ConversationId) {
        pending.remove(key)
        retired.remove(key)
        retired[key] = conversation
        while (retired.size > retiredCap) {
            val victim = retired.keys.first()
            retired.remove(victim)
            Telemetry.event(TAG, "retired.evictedOverCap", "approvalRequestId" to victim.approvalId.value, "cap" to retiredCap, level = Telemetry.Level.WARN)
        }
    }

    private fun rejected(reason: ToolApprovalRejection) = ToolApprovalAdmission.Rejected(reason)

    companion object {
        const val TAG = "A2uiToolApprovalGate"
        private const val MAX_RETIRED = 256
    }
}

private val A2uiToolApprovalSubmission.decision: ToolApprovalDecision
    get() = if (approve) ToolApprovalDecision.Approve else ToolApprovalDecision.Deny

/**
 * The approval API answers one request ([com.letta.mobile.ui.chat.session.ChatApprovalAnswer] has
 * no scope); a lasting grant is an always-allow rule bound to the parked request's own
 * suggestions, which an A2UI card does not carry. A `session`/`forever` tap is therefore applied
 * once, and said so here rather than silently.
 */
private fun logUnpersistedScope(submission: A2uiToolApprovalSubmission) {
    val scope = submission.scope?.lowercase() ?: return
    if (!submission.approve || scope !in PERSISTENT_SCOPES) return
    Telemetry.event(
        PendingToolApprovalRegistry.TAG, "approval.scopeAppliedOnce",
        "scope" to scope,
        "approvalRequestId" to submission.ref.approvalId.value,
    )
}

private val PERSISTENT_SCOPES = setOf("session", "forever", "always")

private fun logRejection(action: A2uiAction, reason: ToolApprovalRejection, path: String = "admit") {
    Telemetry.event(
        PendingToolApprovalRegistry.TAG, "approval.rejected",
        "reason" to reason.name,
        "path" to path,
        "origin" to action.origin.name,
        "surfaceId" to action.surfaceId,
        "conversationId" to action.conversationId,
        level = Telemetry.Level.WARN,
    )
}
