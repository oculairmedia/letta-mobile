package com.letta.mobile.data.notify

import com.letta.mobile.data.presence.ConversationRunState

/**
 *  (F27): something an agent did that may deserve an OS notification while the
 * person is not looking. Platforms post these; whether one is posted at all is [AttentionGate]'s call.
 */
sealed interface AttentionEvent {
    val agentId: String
    val conversationId: String

    /** Identity for de-duplication: the same key is announced at most once per process. */
    val dedupKey: String

    /** The agent's turn is parked on a tool approval only the person can give. */
    data class ApprovalNeeded(
        override val agentId: String,
        override val conversationId: String,
        /** The approval request's id; replays and resyncs of the same request carry the same one. */
        val requestId: String,
        val toolName: String?,
    ) : AttentionEvent {
        override val dedupKey: String get() = "approval:$requestId"
    }

    /**
     * The agent finished a turn. [turnKey] tells one finished turn of a conversation from the next.
     * Hosts raise it from their existing completion signal (desktop's finished-reply toast, Android's
     * reply notifications) so mute and focus apply to it the same way.
     */
    data class TurnDone(
        override val agentId: String,
        override val conversationId: String,
        val turnKey: String,
    ) : AttentionEvent {
        override val dedupKey: String get() = "done:$conversationId:$turnKey"
    }
}

/**
 * Derives [AttentionEvent]s from the app-wide run registry, the one place both platforms already
 * fold every runtime event into (see [com.letta.mobile.data.presence.RunPhaseReducer]). Pure.
 */
object AttentionEvents {

    /**
     * Approvals parked in [current] that [previous] did not already show under the same request id.
     * A replayed approval keeps its id, so it is not raised again here; [AttentionGate] still
     * remembers announced ids for the case where a resync rebuilt the run from rest.
     */
    fun approvalsRaised(
        previous: Map<String, ConversationRunState>,
        current: Map<String, ConversationRunState>,
    ): List<AttentionEvent.ApprovalNeeded> = current.values.mapNotNull { state ->
        val requestId = state.awaitingApprovalId?.takeIf { state.awaitingApproval } ?: return@mapNotNull null
        val before = previous[state.conversationId]
        if (before?.awaitingApproval == true && before.awaitingApprovalId == requestId) return@mapNotNull null
        AttentionEvent.ApprovalNeeded(
            agentId = state.agentId,
            conversationId = state.conversationId,
            requestId = requestId,
            toolName = state.toolName,
        )
    }
}
