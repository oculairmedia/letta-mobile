package com.letta.mobile.ui.shell.header

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.home.conversationRecency
import com.letta.mobile.data.search.ConversationMessageIndex
import com.letta.mobile.data.search.ConversationMessageReader
import com.letta.mobile.data.search.IndexedConversation
import com.letta.mobile.data.search.MessageSearchHit
import com.letta.mobile.data.search.MessageSearchQuery
import com.letta.mobile.data.search.MessageSearchSource
import com.letta.mobile.ui.search.LettaSearchSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The unified search's results for [query] over [sources], the one place the header's search and the
 * command palette get them: while the query covers message text and the
 * sources have a [UnifiedSearchSources.messageSearch], it runs (a short pause after the last
 * keystroke) and its hits join the other sections. A search that fails leaves the message section empty.
 */
@Composable
fun rememberUnifiedSearchSections(query: UnifiedSearchQuery, sources: UnifiedSearchSources): List<LettaSearchSection> {
    val messageSearch = sources.messageSearch
    val messageQuery = messageQueryFor(query, sources).takeIf { messageSearch != null }
    var hits by remember { mutableStateOf(MessageHits.None) }
    LaunchedEffect(messageSearch, messageQuery) {
        val asked = messageQuery ?: return@LaunchedEffect
        delay(MESSAGE_SEARCH_DEBOUNCE_MILLIS)
        hits = MessageHits(asked, searchOrEmpty(messageSearch, asked))
    }
    val current = hits.takeIf { it.query == messageQuery }?.hits.orEmpty()
    return remember(query, sources, current) { UnifiedShellSearch.sections(query, sources, current) }
}

/**
 * A host-side message index (a [ConversationMessageIndex]) over [conversations], for a backend with no
 * message search of its own (the App Server): it reads the newest conversations through [reader],
 * each again only once its update time (its revision) moves. One per [reader].
 */
@Composable
fun rememberConversationMessageIndex(
    conversations: List<ChatConversationSummary>,
    reader: ConversationMessageReader,
): MessageSearchSource {
    val latest by rememberUpdatedState(conversations)
    return remember(reader) { ConversationMessageIndex(reader, conversations = { indexedNewestFirst(latest) }) }
}

private fun indexedNewestFirst(conversations: List<ChatConversationSummary>): List<IndexedConversation> =
    conversations
        .sortedByDescending { conversationRecency(it.updatedAtLabel) }
        .map { IndexedConversation(id = it.id, agentId = it.agentId, revision = it.updatedAtLabel) }

/** What the message search is asked for [query], or null when it should not run (no text, or out of scope). */
internal fun messageQueryFor(query: UnifiedSearchQuery, sources: UnifiedSearchSources): MessageSearchQuery? {
    if (query.text.isBlank() || !query.includes(UnifiedSearchKind.Messages)) return null
    return MessageSearchQuery(
        text = query.text.trim(),
        agentId = sources.agentId.takeIf { query.filterToAgent },
        selection = query.message,
    )
}

private suspend fun searchOrEmpty(source: MessageSearchSource?, query: MessageSearchQuery): List<MessageSearchHit> {
    source ?: return emptyList()
    val result = runCatching { source.search(query) }
    (result.exceptionOrNull() as? CancellationException)?.let { throw it }
    return result.getOrDefault(emptyList())
}

/** The hits of one message query, so a stale answer never shows under a newer query. */
private class MessageHits(val query: MessageSearchQuery?, val hits: List<MessageSearchHit>) {
    companion object {
        val None = MessageHits(null, emptyList())
    }
}

private const val MESSAGE_SEARCH_DEBOUNCE_MILLIS = 200L
