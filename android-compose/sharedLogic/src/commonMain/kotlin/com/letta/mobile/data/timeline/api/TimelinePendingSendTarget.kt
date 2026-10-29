package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore.Delivery

/** Attempt-aware pending lifecycle, with compatibility delegation for writers without durable state. */
interface TimelinePendingSendTarget {
    /** Reserve durable pending capacity before invoking transport acceptance; false keeps the send queued. */
    suspend fun acceptPendingSend(
        attempt: TimelinePendingSendAttempt,
        content: String,
        attachments: List<MessageContentPart.Image>,
        accept: () -> Boolean,
    ): Boolean = accept()

    /** Reject queued retries whose echo or dismissal already removed the pending attempt. */
    suspend fun isPendingSendCurrent(attempt: TimelinePendingSendAttempt): Boolean = true

    /** Retain dispatch ownership independently of viewport and runtime replacement. */
    suspend fun ownPendingSend(attempt: TimelinePendingSendAttempt, active: () -> Boolean) {}

    /** Attempt-aware outcomes; legacy writers retain their existing behavior. */
    suspend fun markPendingSend(attempt: TimelinePendingSendAttempt, delivery: Delivery) {
        if (delivery == Delivery.Failed) {
            markExternalTransportLocalFailed(attempt.agent, attempt.conversation, attempt.identity)
        } else {
            markExternalTransportLocalSent(attempt.agent, attempt.conversation, attempt.identity)
        }
    }

    /** Record transport acceptance for the named optimistic message. */
    suspend fun markExternalTransportLocalSent(agentId: AgentId?, conversationId: ConversationId, otid: MessageId)
    /** Retain the named optimistic message as a retryable failure. */
    suspend fun markExternalTransportLocalFailed(agentId: AgentId?, conversationId: ConversationId, otid: MessageId)
}
