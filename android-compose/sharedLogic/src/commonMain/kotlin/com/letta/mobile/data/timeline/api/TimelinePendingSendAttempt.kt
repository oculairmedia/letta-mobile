package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId

/** Durable attempt identity, captured before callbacks can outlive a retry. */
data class TimelinePendingSendAttempt(
    /** Agent captured by the sender before dispatch. */
    val agent: AgentId,
    /** Conversation that owns the optimistic row. */
    val conversation: ConversationId,
    /** Stable client message identity, reused across retries. */
    val identity: MessageId,
    /** Retry generation used to reject delayed outcomes from an older attempt. */
    val generation: Long,
)
