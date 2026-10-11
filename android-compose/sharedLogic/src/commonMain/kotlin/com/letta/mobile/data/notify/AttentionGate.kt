package com.letta.mobile.data.notify

import com.letta.mobile.data.presence.ConversationRunState
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.Flow

/**
 * Whether the person is already looking at a conversation, so a notification about it would be
 * noise. Desktop answers "the window has focus"; Android answers "the app is in the foreground and
 * this conversation is on screen".
 */
fun interface AttentionFocus {
    fun isAttending(conversationId: String): Boolean
}

/** Why an [AttentionEvent] was not posted. */
enum class AttentionSuppression {
    /** This [AttentionEvent.dedupKey] was already handled. */
    Duplicate,

    /** Approval notifications are switched off for every agent. */
    ApprovalsDisabled,

    /** The person muted this agent. */
    Muted,

    /** The person is already looking at the conversation. */
    Attended,
}

sealed interface AttentionDecision {
    data class Post(val event: AttentionEvent) : AttentionDecision

    data class Suppress(val reason: AttentionSuppression) : AttentionDecision
}

/**
 * letta-mobile-bzvro.27 (F27): the one decision of whether an [AttentionEvent] becomes a notification.
 *
 * Each [AttentionEvent.dedupKey] is decided once, whatever the outcome, so an approval request
 * replayed by a reconnect or rebuilt by a resync posts at most one notification, and one first
 * seen while the person was looking never posts later. The remembered keys are bounded
 * ([capacity], oldest dropped first); an evicted key is long settled.
 *
 * Thread-safe: the run registry and platform completion signals call in from different threads.
 */
class AttentionGate(
    private val prefs: () -> AttentionPrefs,
    private val focus: AttentionFocus,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val lock = SynchronizedObject()
    private val decided = LinkedHashSet<String>()

    fun decide(event: AttentionEvent): AttentionDecision {
        if (!firstSighting(event.dedupKey)) return AttentionDecision.Suppress(AttentionSuppression.Duplicate)
        val reason = suppressionFor(event, prefs())
        return if (reason == null) AttentionDecision.Post(event) else AttentionDecision.Suppress(reason)
    }

    private fun suppressionFor(event: AttentionEvent, current: AttentionPrefs): AttentionSuppression? = when {
        event is AttentionEvent.ApprovalNeeded && !current.approvalNotificationsEnabled -> AttentionSuppression.ApprovalsDisabled
        current.isMuted(event.agentId) -> AttentionSuppression.Muted
        focus.isAttending(event.conversationId) -> AttentionSuppression.Attended
        else -> null
    }

    private fun firstSighting(key: String): Boolean = synchronized(lock) {
        if (!decided.add(key)) return@synchronized false
        while (decided.size > capacity) decided.remove(decided.first())
        true
    }

    companion object {
        const val DEFAULT_CAPACITY = 512
    }
}

/**
 * Watches the run registry and posts each newly parked approval the [gate] lets through. Platforms
 * launch this in an app-lifetime scope and supply only [post] (the OS notification).
 */
class ApprovalAttentionMonitor(private val gate: AttentionGate) {
    suspend fun watch(runs: Flow<Map<String, ConversationRunState>>, post: suspend (AttentionEvent.ApprovalNeeded) -> Unit) {
        var previous = emptyMap<String, ConversationRunState>()
        runs.collect { current ->
            AttentionEvents.approvalsRaised(previous, current).forEach { event ->
                if (gate.decide(event) is AttentionDecision.Post) post(event)
            }
            previous = current
        }
    }
}
