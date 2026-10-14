package com.letta.mobile.data.a2ui

import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

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
    /** Submit [submission] to [conversationKey] (the conversation the client registered, not the action's own). */
    data class Admitted(
        val submission: A2uiToolApprovalSubmission,
        val conversationKey: String,
    ) : ToolApprovalAdmission

    data class Rejected(val reason: ToolApprovalRejection) : ToolApprovalAdmission
}

/**
 * letta-mobile-by4vm.2: the tool approvals this client itself is showing as pending, and the only
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
    private data class Key(val approvalRequestId: String, val callId: String)

    private data class Entry(val conversationKey: String, val allowedDecisions: Set<ToolApprovalDecision>)

    private val lock = SynchronizedObject()
    private val pending = LinkedHashMap<Key, Entry>()

    /** Answered or expired pairs, by conversation; insertion-ordered so the oldest is evicted first. */
    private val retired = LinkedHashMap<Key, String>()

    fun register(
        conversationKey: String,
        approvalRequestId: String,
        callId: String,
        allowedDecisions: Set<ToolApprovalDecision> = ToolApprovalDecision.All,
    ) = synchronized(lock) {
        val key = Key(approvalRequestId, callId)
        if (key !in retired) pending[key] = Entry(conversationKey, allowedDecisions)
    }

    /**
     * Makes [conversationKey]'s entries exactly [pendingCalls] (request id to its tool call ids),
     * minus anything already retired; retired pairs no longer pending are forgotten.
     */
    fun sync(conversationKey: String, pendingCalls: Map<String, Collection<String>>) = synchronized(lock) {
        val now = pendingCalls.flatMap { (requestId, callIds) -> callIds.map { Key(requestId, it) } }.toSet()
        pending.entries.removeAll { (key, entry) -> entry.conversationKey == conversationKey && key !in now }
        retired.entries.removeAll { (key, conversation) -> conversation == conversationKey && key !in now }
        now.filter { it !in retired && it !in pending }
            .forEach { pending[it] = Entry(conversationKey, ToolApprovalDecision.All) }
    }

    /** Turn end: every pending approval in [conversationKey] can no longer be answered from A2UI. */
    fun expire(conversationKey: String) = synchronized(lock) {
        val ended = pending.filterValues { it.conversationKey == conversationKey }.keys
        ended.forEach { retireLocked(it, conversationKey) }
    }

    /**
     * Admits or refuses [action], answered while the client showed [conversationKey]; null when it
     * is not a `tool_approval_response` at all. Refusals are logged here.
     */
    fun admit(action: A2uiAction, conversationKey: String?): ToolApprovalAdmission? {
        if (!action.isToolApprovalResponse) return null
        val admission = synchronized(lock) { admitLocked(action, conversationKey) }
        when (admission) {
            is ToolApprovalAdmission.Rejected -> logRejection(action, admission.reason)
            is ToolApprovalAdmission.Admitted -> logUnpersistedScope(admission.submission)
        }
        return admission
    }

    private fun admitLocked(action: A2uiAction, conversationKey: String?): ToolApprovalAdmission {
        if (action.origin != A2uiActionOrigin.ToolApprovalCard) return rejected(ToolApprovalRejection.NotFromApprovalCard)
        val submission = action.toToolApprovalSubmission() ?: return rejected(ToolApprovalRejection.Malformed)
        val key = Key(submission.approvalRequestId, submission.callId)
        val entry = pending[key] ?: return rejected(missingReason(key))
        val reason = when {
            entry.conversationKey != conversationKey -> ToolApprovalRejection.ConversationMismatch
            submission.decision !in entry.allowedDecisions -> ToolApprovalRejection.DecisionNotAllowed
            else -> null
        }
        if (reason != null) return rejected(reason)
        retireLocked(key, entry.conversationKey)
        return ToolApprovalAdmission.Admitted(submission, entry.conversationKey)
    }

    private fun missingReason(key: Key): ToolApprovalRejection = when {
        key in retired -> ToolApprovalRejection.Retired
        pending.keys.any { it.approvalRequestId == key.approvalRequestId } -> ToolApprovalRejection.CallMismatch
        else -> ToolApprovalRejection.UnknownRequest
    }

    private fun retireLocked(key: Key, conversationKey: String) {
        pending.remove(key)
        retired.remove(key)
        retired[key] = conversationKey
        while (retired.size > retiredCap) {
            val victim = retired.keys.first()
            retired.remove(victim)
            Telemetry.event(TAG, "retired.evictedOverCap", "approvalRequestId" to victim.approvalRequestId, "cap" to retiredCap, level = Telemetry.Level.WARN)
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
 * The guard for an A2UI action headed to the wire as a plain user action: false (and logged) for a
 * `tool_approval_response`, which may only become an approval through [PendingToolApprovalRegistry.admit].
 * A client without that registry path drops approval answers rather than let the server match them.
 */
fun A2uiAction.mayForwardAsUserAction(): Boolean {
    if (!isToolApprovalResponse) return true
    logRejection(this, ToolApprovalRejection.UnknownRequest, path = "forward")
    return false
}

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
        "approvalRequestId" to submission.approvalRequestId,
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
