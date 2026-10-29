package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.timeline.api.TimelinePendingSendAttempt

internal fun QueuedChatSend.pendingAttempt(agent: String) = TimelinePendingSendAttempt(
    AgentId(agent), ConversationId(conversationId.value), MessageId(id.value), attempt,
)

internal suspend fun ConversationTurnState.markPendingOutcome(
    writer: TimelineExternalTransportWriter,
    agent: String,
    delivery: CanonicalPendingLocalStore.Delivery,
) {
    val attempt = inFlightSend?.pendingAttempt(agent) ?: TimelinePendingSendAttempt(
        AgentId(agent), ConversationId(ownerConversationId), MessageId(otid ?: return), 0,
    )
    writer.markPendingSend(attempt, delivery)
}
