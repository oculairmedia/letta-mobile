package com.letta.mobile.data.search

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.UserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * letta-mobile-ckmub.3.9: one message whose text matched a search - which conversation it is in, who
 * it belongs to, and the text itself (the row shows a snippet of it).
 */
data class MessageSearchHit(
    val conversationId: String,
    val agentId: String?,
    val messageId: String?,
    val text: String,
    val date: String? = null,
)

/** What a message search looks for: the typed [text], narrowed to [agentId]'s conversations when set. */
data class MessageSearchQuery(val text: String, val agentId: String? = null)

/**
 * Searches message text for the unified search. A host either asks its backend (a REST message
 * search) or keeps a [ConversationMessageIndex] over the App Server's per-conversation reads.
 */
fun interface MessageSearchSource {
    suspend fun search(query: MessageSearchQuery): List<MessageSearchHit>
}

/** A backend search result as a hit; null when it names no conversation to open or has no text. */
fun ParsedSearchMessage.toSearchHit(): MessageSearchHit? {
    val conversation = conversationId?.takeIf { it.isNotBlank() } ?: return null
    val body = content?.takeIf { it.isNotBlank() } ?: return null
    return MessageSearchHit(conversation, agentId, messageId, body, date)
}

/** A conversation the index may read: its id, its agent and a [revision] that changes when it gets new messages. */
data class IndexedConversation(val id: String, val agentId: String?, val revision: String)

/** Reads a conversation's newest messages, newest first (the App Server's `conversation_messages_list`). */
fun interface ConversationMessageReader {
    suspend fun recentMessages(conversationId: String, limit: Int): List<LettaMessage>
}

/** How much the index reads: the newest [conversations] conversations, [messagesPerConversation] each, [parallelReads] at a time. */
data class MessageIndexLimits(
    val conversations: Int = DEFAULT_CONVERSATIONS,
    val messagesPerConversation: Int = DEFAULT_MESSAGES,
    val parallelReads: Int = DEFAULT_PARALLEL_READS,
    val results: Int = DEFAULT_RESULTS,
) {
    companion object {
        const val DEFAULT_CONVERSATIONS = 40
        const val DEFAULT_MESSAGES = 100
        const val DEFAULT_PARALLEL_READS = 4
        const val DEFAULT_RESULTS = 20
    }
}

/**
 * The host-side message index for a backend with no message search of its own (the App Server): the
 * newest messages of the newest conversations, read once per conversation [IndexedConversation.revision]
 * and matched with [TextMatch] like every other search in the app. [conversations] is read per search,
 * newest first; a conversation that fails to read is skipped, never fatal to the search.
 */
class ConversationMessageIndex(
    private val reader: ConversationMessageReader,
    private val conversations: () -> List<IndexedConversation>,
    private val limits: MessageIndexLimits = MessageIndexLimits(),
) : MessageSearchSource {
    private val mutex = Mutex()
    private val cache = HashMap<String, IndexedMessages>()

    override suspend fun search(query: MessageSearchQuery): List<MessageSearchHit> {
        if (query.text.isBlank()) return emptyList()
        val targets = conversations()
            .filter { query.agentId == null || it.agentId == query.agentId }
            .take(limits.conversations)
        val indexed = targets.chunked(limits.parallelReads).flatMap { batch ->
            coroutineScope { batch.map { async { messagesOf(it) } }.awaitAll() }
        }
        return indexed.asSequence()
            .flatMap { it.hits.asSequence() }
            .filter { TextMatch.matches(query.text, it.text) }
            .take(limits.results)
            .toList()
    }

    private suspend fun messagesOf(conversation: IndexedConversation): IndexedMessages {
        mutex.withLock { cache[conversation.id]?.takeIf { it.revision == conversation.revision } }?.let { return it }
        val read = readOrNull(conversation) ?: return IndexedMessages(conversation.revision, emptyList())
        val messages = IndexedMessages(conversation.revision, read.mapNotNull { it.toHit(conversation) })
        mutex.withLock { cache[conversation.id] = messages }
        return messages
    }

    private suspend fun readOrNull(conversation: IndexedConversation): List<LettaMessage>? {
        val result = runCatching { reader.recentMessages(conversation.id, limits.messagesPerConversation) }
        (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        return result.getOrNull()
    }

    /** One conversation's searchable messages, newest first, at the revision they were read. */
    private class IndexedMessages(val revision: String, val hits: List<MessageSearchHit>)
}

/** What the person and the agent said; reasoning, tool traffic and system text are not searched. */
private fun LettaMessage.toHit(conversation: IndexedConversation): MessageSearchHit? {
    val body = when (this) {
        is UserMessage -> content
        is AssistantMessage -> content
        else -> null
    }?.takeIf { it.isNotBlank() } ?: return null
    return MessageSearchHit(conversation.id, conversation.agentId, id, body, date)
}
