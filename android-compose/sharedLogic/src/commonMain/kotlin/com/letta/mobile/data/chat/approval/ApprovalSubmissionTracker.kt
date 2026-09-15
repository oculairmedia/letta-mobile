package com.letta.mobile.data.chat.approval

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which approval requests the user has answered and the app is still waiting on, shared by every
 * chat client (letta-mobile-2dsi5.4). An answer is in flight from the moment it is submitted until
 * the conversation's timeline no longer shows that request as pending; a failed or cancelled submit
 * clears it at once so the card becomes actionable again.
 *
 * Holding it until the timeline agrees - not merely until the submit call returns - is what keeps
 * a card from turning actionable again between a successful submit and the server's decision
 * reaching the timeline, which invited a second answer to the same request.
 */
class ApprovalSubmissionTracker {
    private val conversationByRequest = LinkedHashMap<String, String>()
    private val _submitting = MutableStateFlow<Set<String>>(emptySet())

    /** Request ids with an answer in flight, in submission order. */
    val submitting: StateFlow<Set<String>> = _submitting.asStateFlow()

    /** The most recently submitted request still in flight, or null. */
    val latest: String? get() = _submitting.value.lastOrNull()

    fun isSubmitting(requestId: String): Boolean = requestId in _submitting.value

    /** Marks [requestId], answered in [conversationId], as in flight. */
    fun begin(requestId: String, conversationId: String) {
        conversationByRequest.remove(requestId)
        conversationByRequest[requestId] = conversationId
        publish()
    }

    /** The submit failed or was cancelled: the request is actionable again. */
    fun clear(requestId: String) {
        if (conversationByRequest.remove(requestId) != null) publish()
    }

    /**
     * The timeline for [conversationId] now shows [pendingRequestIds] as pending. Every in-flight
     * request of that conversation that is no longer pending has been decided: clear it. Returns the
     * cleared ids.
     */
    fun reconcile(conversationId: String, pendingRequestIds: Set<String>): Set<String> {
        val decided = conversationByRequest
            .filter { (requestId, conversation) -> conversation == conversationId && requestId !in pendingRequestIds }
            .keys
            .toSet()
        if (decided.isEmpty()) return emptySet()
        decided.forEach(conversationByRequest::remove)
        publish()
        return decided
    }

    private fun publish() {
        _submitting.update { LinkedHashSet(conversationByRequest.keys) }
    }
}
