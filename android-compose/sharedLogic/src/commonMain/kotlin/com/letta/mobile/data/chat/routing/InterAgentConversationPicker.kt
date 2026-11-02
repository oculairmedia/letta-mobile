package com.letta.mobile.data.chat.routing

import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.IConversationRepository

/** Finds the default inbox using the same lane lookup as the receiver. */
suspend fun pickOtherAgentConversation(
    repo: IConversationRepository,
    agentId: AgentId,
): String? {
    return runCatching {
        IrohAgentMessageRouter.pickLane(
            repo.listConversationsForAgent(agentId), "inbox",
        )?.id?.value
    }.getOrNull()
}
