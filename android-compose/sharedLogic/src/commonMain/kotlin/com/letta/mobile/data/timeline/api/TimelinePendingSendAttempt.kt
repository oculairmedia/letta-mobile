package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId

/** Durable attempt identity, captured before callbacks can outlive a retry. */
data class TimelinePendingSendAttempt(
    val agent: AgentId,
    val conversation: ConversationId,
    val identity: MessageId,
    val generation: Long,
)
