package com.letta.mobile.data.chat.routing

import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.IConversationRepository

/**
 * letta-mobile-i9h61.3 — pick "the conversation where an inter-agent
 * message lives" on the OTHER agent's side.
 *
 * Each agent owns its own conversation id space, and the sender's
 * `agent_message_send` tool result does not carry the recipient-side
 * conversation id (the routing decision is made on the recipient's
 * wrapper at receive time). But the routing policy is deterministic and
 * lives in this repo: [IrohAgentMessageRouter] picks the most-recent
 * INTERACTIVE conversation using
 * `lastMessageAt ?: updatedAt ?: createdAt`. Because that policy is pure
 * AND reads the same data source (the shared local backend store) as
 * the router, we can run the *identical* computation client-side against
 * the target agent's conversation list and land on the same conversation
 * the router chose — no letta-code change, no tool-result change.
 *
 * Reads via [IConversationRepository.listConversationsForAgent]
 * (letta-mobile-i9h61.3.2), which dispatches to the wrapper's
 * conversation.list_agent admin_rpc. The picker and the router share one
 * implementation (IrohAgentMessageRouter.pickMostRecentInteractive) so
 * they can never disagree about "which conversation."
 *
 * Remaining limitations (honest, not hidden):
 *   - The picker sees the target agent's conversation list as of the
 *     call. If the recipient created a newer INTERACTIVE conversation
 *     between the exchange and the tap, the recency pick differs from
 *     what the router chose at receive time. That is inherent to any
 *     client-side re-derivation and is why the bead still tracks an
 *     appserver-side `routingConversationId` as the deterministic answer.
 *   - If the target agent has zero INTERACTIVE conversations, returns
 *     null and the caller falls through to appserver-resolved fresh
 *     conversation creation.
 *   - HTTP / appserver gateways without conversation.list_agent return
 *     emptyList() and the picker falls through to the null branch.
 */
suspend fun pickOtherAgentConversation(
    repo: IConversationRepository,
    agentId: AgentId,
): String? {
    return runCatching {
        IrohAgentMessageRouter.pickMostRecentInteractive(
            repo.listConversationsForAgent(agentId),
        )?.id?.value
    }.getOrNull()
}
