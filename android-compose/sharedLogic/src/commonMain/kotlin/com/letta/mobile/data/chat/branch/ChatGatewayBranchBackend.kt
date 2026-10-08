package com.letta.mobile.data.chat.branch

import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras

/** How many of the newest stored messages a branch reads when the page does not hold its message. */
internal const val BRANCH_HISTORY_WINDOW = 200

private const val NO_CREATE = "This backend can't start a new conversation, so the first prompt can't be edited."

/**
 * The [BranchBackend] of a [ChatGateway] that can fork ([ConversationForkGateway]); null when it
 * cannot, so the page hides "Fork from here" and "Edit and resend". An edit of a conversation's
 * first prompt needs [ChatGatewayExtras.createConversation]; without it that one edit reports
 * itself unavailable.
 */
fun ChatGateway.branchBackendOrNull(): BranchBackend? {
    val forks = this as? ConversationForkGateway ?: return null
    val extras = this as? ChatGatewayExtras
    return BranchBackend(
        fork = forks::forkConversation,
        createEmpty = { agentId ->
            extras?.createConversation(agentId) ?: throw BranchUnavailableException(NO_CREATE)
        },
        history = { conversationId ->
            val newest = listConversationMessages(conversationId, limit = BRANCH_HISTORY_WINDOW, after = null, order = "desc")
            BranchHistory(newest.asReversed().map(BranchMessage::of), complete = newest.size < BRANCH_HISTORY_WINDOW)
        },
    )
}
