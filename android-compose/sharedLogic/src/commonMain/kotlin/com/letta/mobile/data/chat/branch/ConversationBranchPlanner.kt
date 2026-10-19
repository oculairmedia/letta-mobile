package com.letta.mobile.data.chat.branch

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UserMessage

/** Where a new branch of a conversation starts. */
sealed interface BranchPoint {
    /** Fork the conversation through [messageId], inclusive. */
    data class Through(val messageId: String) : BranchPoint

    /** Nothing comes before the branch: it starts as an empty conversation of the same agent. */
    data object Empty : BranchPoint
}

/**
 * One message as the planner sees it: its server id, whether it is a prompt (a user message,
 * which starts an exchange), whether the server has stored it under [id], the client id ([alias],
 * the otid) the page may know it by before the server id lands, and its [text] (the last resort
 * for finding a streamed row in the server's list when no id is shared).
 */
data class BranchMessage(
    val id: String,
    val isPrompt: Boolean,
    val stored: Boolean = true,
    val alias: String? = null,
    val text: String? = null,
) {
    private val keys: Set<String>
        get() = setOfNotNull(id, alias, BranchIds.serverIdOf(id)).filterTo(mutableSetOf()) { it.isNotBlank() }

    /** Same message: by server id, by the client id either side knows it by, or by a client rewrite of the id. */
    fun matches(target: BranchMessage): Boolean = keys.any { it in target.keys }

    companion object {
        private const val USER_ROLE = "user"
        private const val USER_MESSAGE_TYPE = "user_message"

        fun of(message: UiMessage): BranchMessage = BranchMessage(
            id = message.id,
            isPrompt = message.role == USER_ROLE,
            stored = ConversationBranchPlanner.isStored(message) && BranchIds.isServerId(message),
            alias = message.clientMessageId?.takeIf { it.isNotBlank() },
            text = message.content.takeIf { !message.isReasoning && it.isNotBlank() },
        )

        /** A message read back from the server: stored by definition, its id a fork point upstream accepts. */
        fun of(message: LettaMessage): BranchMessage = BranchMessage(
            id = message.id,
            isPrompt = message.messageType == USER_MESSAGE_TYPE,
            alias = message.otid?.takeIf { it.isNotBlank() },
            text = when (message) {
                is UserMessage -> message.content
                is AssistantMessage -> message.content
                else -> null
            }?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * The ids a timeline row can carry that the server never stored. letta-code's
 * `conversation_fork` only accepts the id of a projected stored message (letta.js
 * `selectLocalMessagesForFork`); anything else answers "Message not found".
 */
internal object BranchIds {
    /** The Iroh serve path's stable rewrite of a streamed assistant / reasoning id (`cm-stream-<message_id|otid>`). */
    private val streamPrefixes = listOf("cm-stream-", "cm-reason-")

    /** Optimistic ids a row has before the server's echo replaces them. */
    private val optimisticPrefixes = listOf("cm-", "client-", "local-", "lm-", "pending-")

    /** The suffix the timeline gives a confirmed reasoning row (`TimelineEventToUiMessage`). */
    private const val REASONING_SUFFIX = ":REASONING"

    /** The id the server knows a rewritten row by; null when [id] is not a known rewrite. */
    fun serverIdOf(id: String): String? {
        if (id.endsWith(REASONING_SUFFIX)) return id.removeSuffix(REASONING_SUFFIX)
        val prefix = streamPrefixes.firstOrNull(id::startsWith) ?: return null
        return id.removePrefix(prefix).takeIf { it.isNotBlank() }
    }

    /** The row's id is a server id: not a rewrite, not optimistic, and not just the otid of an unechoed send. */
    fun isServerId(message: UiMessage): Boolean {
        val id = message.id
        return id.isNotBlank() &&
            serverIdOf(id) == null &&
            optimisticPrefixes.none(id::startsWith) &&
            id != message.clientMessageId
    }
}

/**
 * Picks the message a fork goes through, from a
 * conversation's messages (oldest first): the server's own list when the backend can read it (its
 * ids are the ones a fork accepts), else the timeline the page shows.
 *
 * Only messages the server has stored can be a fork point: a pending or failed send, a client-side
 * error row, and a row still known by a streamed or optimistic id have no server id to fork through.
 */
object ConversationBranchPlanner {
    private const val USER_ROLE = "user"

    /** The message is stored on the server (as far as its flags tell), so it may be a fork point. */
    fun isStored(message: UiMessage): Boolean =
        message.id.isNotBlank() && !message.isPending && !message.isSendFailed && !message.isError

    /** "Fork from here" applies to a stored prompt or reply. */
    fun canFork(message: UiMessage): Boolean = isStored(message) && !message.isReasoning

    /** "Edit and resend" applies to a stored prompt of the user's that has text to edit. */
    fun canEdit(message: UiMessage): Boolean =
        message.role == USER_ROLE && isStored(message) && message.content.isNotBlank() &&
            message.agentMessageProvenance == null

    /** "Rerun" applies to a stored prompt of the user's whose images (if any) can be sent again. */
    fun canRerun(message: UiMessage): Boolean =
        message.role == USER_ROLE && isStored(message) && message.agentMessageProvenance == null &&
            (message.content.isNotBlank() || message.attachments.isNotEmpty()) &&
            message.attachments.all { it.base64.isNotEmpty() }

    /**
     * Where [target] sits in [messages]: by id (or a client rewrite of it), else by its text among
     * messages of the same kind, counting [fromEnd] equal messages back from the newest. -1 when
     * [messages] does not hold it.
     */
    fun locate(messages: List<BranchMessage>, target: BranchMessage, fromEnd: Int = 0): Int {
        val byId = messages.indexOfFirst { it.matches(target) }
        if (byId >= 0) return byId
        val text = target.text?.trim()?.takeIf { it.isNotEmpty() } ?: return -1
        val sameText = messages.indices.filter { messages[it].isPrompt == target.isPrompt && messages[it].text?.trim() == text }
        return sameText.getOrNull(sameText.size - 1 - fromEnd) ?: -1
    }

    /**
     * The point a "Fork from here" on [target] forks through. A reply forks through itself; a prompt
     * keeps the exchange it started, so the fork ends with the last stored message before the next
     * prompt (the reply, after any tool calls), or with the prompt when nothing answered it yet.
     * Null when [messages] does not hold [target] under an id a fork can use.
     */
    fun forkPoint(messages: List<BranchMessage>, target: BranchMessage, fromEnd: Int = 0): BranchPoint? {
        val index = locate(messages, target, fromEnd)
        if (index < 0) return null
        val found = messages[index]
        if (!found.stored) return null
        if (!found.isPrompt) return BranchPoint.Through(found.id)
        val exchangeEnd = messages.subList(index + 1, messages.size)
            .takeWhile { !it.isPrompt }
            .lastOrNull { it.stored }
        return BranchPoint.Through((exchangeEnd ?: found).id)
    }

    /**
     * The point an "Edit and resend" (or a rerun) of [target] forks through: the last stored message
     * before it, so the fork holds the history up to the prompt and the prompt is sent into it.
     * [BranchPoint.Empty] when the prompt opened the conversation. Null when [messages] does not
     * hold [target], or when nothing stored precedes it and [historyComplete] is false (older
     * history is not loaded, so the real predecessor is unknown).
     */
    fun editPoint(
        messages: List<BranchMessage>,
        target: BranchMessage,
        historyComplete: Boolean = true,
        fromEnd: Int = 0,
    ): BranchPoint? {
        val index = locate(messages, target, fromEnd)
        if (index < 0) return null
        val prior = messages.subList(0, index).lastOrNull { it.stored }
        return when {
            prior != null -> BranchPoint.Through(prior.id)
            historyComplete && index == 0 -> BranchPoint.Empty
            else -> null
        }
    }
}
