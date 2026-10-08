package com.letta.mobile.data.chat.branch

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UiMessage

/** Where a new branch of a conversation starts. */
sealed interface BranchPoint {
    /** Fork the conversation through [messageId], inclusive. */
    data class Through(val messageId: String) : BranchPoint

    /** Nothing comes before the branch: it starts as an empty conversation of the same agent. */
    data object Empty : BranchPoint
}

/**
 * One message as the planner sees it: its server id, whether it is a prompt (a user message,
 * which starts an exchange), whether the server has stored it, and the client id ([alias], the
 * otid) the page may know it by before the server id lands.
 */
data class BranchMessage(
    val id: String,
    val isPrompt: Boolean,
    val stored: Boolean = true,
    val alias: String? = null,
) {
    /** Same message: by server id, or by the client id either side knows it by. */
    fun matches(target: BranchMessage): Boolean =
        id == target.id || (alias != null && alias == target.id) ||
            (target.alias != null && (alias == target.alias || id == target.alias))

    companion object {
        private const val USER_ROLE = "user"
        private const val USER_MESSAGE_TYPE = "user_message"

        fun of(message: UiMessage): BranchMessage = BranchMessage(
            id = message.id,
            isPrompt = message.role == USER_ROLE,
            stored = ConversationBranchPlanner.isStored(message),
            alias = message.clientMessageId?.takeIf { it.isNotBlank() },
        )

        /** A message read back from the server: stored by definition. */
        fun of(message: LettaMessage): BranchMessage = BranchMessage(
            id = message.id,
            isPrompt = message.messageType == USER_MESSAGE_TYPE,
            alias = message.otid?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * letta-mobile-bzvro.15 / .16 (F15, F16): picks the message a fork goes through, from a
 * conversation's messages (oldest first): the timeline the page shows, or the server's own list
 * when the page does not hold the message.
 *
 * Only messages the server has stored can be a fork point: a pending or failed send, and a
 * client-side error row, have no server id to fork through.
 */
object ConversationBranchPlanner {
    private const val USER_ROLE = "user"

    /** The message is stored on the server, so its id can be a fork point. */
    fun isStored(message: UiMessage): Boolean =
        message.id.isNotBlank() && !message.isPending && !message.isSendFailed && !message.isError

    /** "Fork from here" applies to a stored prompt or reply. */
    fun canFork(message: UiMessage): Boolean = isStored(message) && !message.isReasoning

    /** "Edit and resend" applies to a stored prompt of the user's that has text to edit. */
    fun canEdit(message: UiMessage): Boolean =
        message.role == USER_ROLE && isStored(message) && message.content.isNotBlank() &&
            message.agentMessageProvenance == null

    /**
     * The point a "Fork from here" on [target] forks through. A reply forks through itself; a prompt
     * keeps the exchange it started, so the fork ends with the reply that answered it (the last
     * stored message before the next prompt), or with the prompt when nothing answered it yet.
     * Null when [messages] does not hold [target].
     */
    fun forkPoint(messages: List<BranchMessage>, target: BranchMessage): BranchPoint? {
        val index = messages.indexOfFirst { it.matches(target) }
        if (index < 0) return null
        val found = messages[index]
        if (!found.isPrompt) return BranchPoint.Through(found.id)
        val exchangeEnd = messages.subList(index + 1, messages.size)
            .takeWhile { !it.isPrompt }
            .lastOrNull { it.stored }
        return BranchPoint.Through((exchangeEnd ?: found).id)
    }

    /**
     * The point an "Edit and resend" of [target] forks through: the last stored message before it,
     * so the fork holds the history up to the edited prompt and the edited text is sent into it.
     * [BranchPoint.Empty] when the prompt opened the conversation. Null when [messages] does not
     * hold [target], or when nothing stored precedes it and [historyComplete] is false (older
     * history is not loaded, so the real predecessor is unknown).
     */
    fun editPoint(messages: List<BranchMessage>, target: BranchMessage, historyComplete: Boolean = true): BranchPoint? {
        val index = messages.indexOfFirst { it.matches(target) }
        if (index < 0) return null
        val prior = messages.subList(0, index).lastOrNull { it.stored }
        return when {
            prior != null -> BranchPoint.Through(prior.id)
            historyComplete -> BranchPoint.Empty
            else -> null
        }
    }
}
