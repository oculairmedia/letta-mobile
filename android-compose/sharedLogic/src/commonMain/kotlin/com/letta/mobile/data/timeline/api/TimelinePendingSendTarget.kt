package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.timeline.CanonicalPendingLocalStore.Delivery

/** Attempt-aware pending lifecycle, with compatibility delegation for writers without durable state. */
interface TimelinePendingSendTarget {
    /** Reject queued retries whose echo or dismissal already removed the pending attempt. */
    suspend fun isPendingSendCurrent(attempt: TimelinePendingSendAttempt): Boolean = true

    /** Retain dispatch ownership independently of viewport and runtime replacement. */
    suspend fun ownPendingSend(attempt: TimelinePendingSendAttempt, active: () -> Boolean) {}

    /** Attempt-aware outcomes; legacy writers retain their existing behavior. */
    suspend fun markPendingSend(attempt: TimelinePendingSendAttempt, delivery: Delivery) {
        if (delivery == Delivery.Failed) {
            markExternalTransportLocalFailed(attempt.agent.value, attempt.conversation.value, attempt.identity.value)
        } else {
            markExternalTransportLocalSent(attempt.agent.value, attempt.conversation.value, attempt.identity.value)
        }
    }

    suspend fun markExternalTransportLocalSent(agentId: String?, conversationId: String, otid: String)
    suspend fun markExternalTransportLocalFailed(agentId: String?, conversationId: String, otid: String)
}
