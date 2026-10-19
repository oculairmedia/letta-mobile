package com.letta.mobile.data.chat.branch

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.chat.runtime.ConversationSummaryGateway
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.timeline.TimelineTransport

/** How many of the newest stored messages a branch reads to place its fork point. */
internal const val BRANCH_HISTORY_WINDOW = 200

private const val NO_CREATE = "This backend can't start a new conversation, so the first prompt can't be edited."

/**
 * The conversation's newest stored messages (oldest first), the list a branch
 * places its fork point on. Any [TimelineTransport] can read it: the desktop gateways, and
 * Android's canonical transport (local runtime, Iroh admin_rpc, or REST).
 */
suspend fun TimelineTransport.branchHistory(conversationId: String): BranchHistory {
    val newest = listConversationMessages(ConversationId(conversationId), limit = BRANCH_HISTORY_WINDOW, after = null, order = "desc")
    return BranchHistory(newest.asReversed().map(BranchMessage::of), complete = newest.size < BRANCH_HISTORY_WINDOW)
}

/**
 * The [BranchBackend] of a [ChatGateway] that can fork ([ConversationForkGateway]); null when it
 * cannot, so the page hides "Fork from here", "Edit and resend" and "Rerun". An edit of a
 * conversation's first prompt needs [ChatGatewayExtras.createConversation]; without it that one
 * edit reports itself unavailable. A fork can be named when the gateway can retitle
 * ([ConversationSummaryGateway]).
 */
fun ChatGateway.branchBackendOrNull(): BranchBackend? {
    val forks = this as? ConversationForkGateway ?: return null
    val extras = this as? ChatGatewayExtras
    val summaries = this as? ConversationSummaryGateway
    return BranchBackend(
        fork = forks::forkConversation,
        createEmpty = { agentId ->
            extras?.createConversation(agentId) ?: throw BranchUnavailableException(NO_CREATE)
        },
        history = { conversationId -> branchHistory(conversationId) },
        rename = summaries?.let(::renameThrough),
    )
}

/** A named fork's rename, through the gateway's summary (title) write. */
private fun renameThrough(gateway: ConversationSummaryGateway): suspend (Conversation, String) -> Conversation =
    { conversation, name -> gateway.setConversationSummary(ConversationSummaryUpdate(conversation.id, ConversationSummary(name))) }
