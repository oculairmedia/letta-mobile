package com.letta.mobile.data.swarm

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.model.UserMessage

/**
 * Reading a send back from the conversation history. The persisted user
 * message is the first one after the send's anchor whose `otid` is the client message id or, when
 * the host does not echo it, whose text is exactly the text sent.
 */
internal class SwarmHistory(private val directory: SwarmDirectory) {
    suspend fun findPersisted(conversation: ConversationId, entry: OutboxEntry): Lookup<MessageId?> =
        directory.messages(conversation, after = entry.anchor, limit = HISTORY_PAGE).then { messages ->
            Lookup.Found(messages.firstOrNull { it.isPersistedFormOf(entry) }?.id?.let(::MessageId))
        }

    /** The assistant messages after [after] (oldest first), as the reply. */
    suspend fun replyAfter(conversation: ConversationId, after: MessageId): Lookup<SwarmReply> =
        directory.messages(conversation, after = after, limit = HISTORY_PAGE).then { messages ->
            Lookup.Found(messages.toReply())
        }

    companion object {
        const val HISTORY_PAGE: Int = 100
    }
}

private fun LettaMessage.isPersistedFormOf(entry: OutboxEntry): Boolean =
    this is UserMessage && (otid == entry.id.value || content == entry.text)

/** Joins the assistant messages' text, bounded to [SwarmLimits.MAX_REPLY_CHARS]. */
internal fun List<LettaMessage>.toReply(): SwarmReply {
    val assistant = filterIsInstance<AssistantMessage>().filter { it.content.isNotBlank() }
    if (assistant.isEmpty()) return SwarmReply.Empty
    val joined = assistant.joinToString("\n\n") { it.content }
    return SwarmReply(
        text = joined.take(SwarmLimits.MAX_REPLY_CHARS),
        messageIds = assistant.map { MessageId(it.id) }.distinct(),
        truncated = joined.length > SwarmLimits.MAX_REPLY_CHARS,
    )
}
