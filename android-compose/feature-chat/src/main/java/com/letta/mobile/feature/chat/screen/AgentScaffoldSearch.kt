package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.components.highlightSearchMatches
import com.letta.mobile.ui.components.rememberSearchHighlightColors
import com.letta.mobile.ui.components.searchResultSnippet
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.util.formatRelativeTime

@Composable
internal fun ChatSearchResultsContent(
    searchQuery: String,
    results: List<ParsedSearchMessage>,
    isSearching: Boolean,
    conversations: List<Conversation>,
    currentConversationId: String?,
    onResultClick: (ParsedSearchMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightColors = rememberSearchHighlightColors()
    val conversationsById = remember(conversations) { conversations.associateBy { it.id.value } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "chat-search-header") {
            ChatSearchResultsHeader(isSearching = isSearching)
        }

        if (!isSearching && results.isEmpty()) {
            item(key = "chat-search-empty") {
                ChatSearchResultsEmptyState()
            }
        }

        itemsIndexed(
            items = results,
            key = { index, result -> chatSearchResultKey(result, index) },
        ) { _, result ->
            ChatSearchResultCard(
                result = result,
                searchQuery = searchQuery,
                conversationsById = conversationsById,
                currentConversationId = currentConversationId,
                highlightColors = highlightColors,
                onResultClick = onResultClick,
            )
        }
    }
}

@Composable
private fun ChatSearchResultsHeader(isSearching: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.screen_home_search_messages_section),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (isSearching) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ChatSearchResultsEmptyState() {
    Text(
        text = stringResource(R.string.screen_home_search_no_results),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
    )
}

@Composable
private fun ChatSearchResultCard(
    result: ParsedSearchMessage,
    searchQuery: String,
    conversationsById: Map<String, Conversation>,
    currentConversationId: String?,
    highlightColors: com.letta.mobile.ui.components.SearchHighlightColors,
    onResultClick: (ParsedSearchMessage) -> Unit,
) {
    val conversation = result.conversationId?.let(conversationsById::get)
    val isCurrentConversation = result.conversationId != null && result.conversationId == currentConversationId
    val conversationScope = when {
        isCurrentConversation -> "Current conversation"
        result.conversationId != null -> "Previous conversation"
        else -> "Conversation unknown"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onResultClick(result) },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            ChatSearchResultCardHeader(
                conversationScope = conversationScope,
                isCurrentConversation = isCurrentConversation,
                result = result,
            )
            conversation?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = highlightSearchMatches(
                    searchResultSnippet(result.content.orEmpty(), searchQuery),
                    searchQuery,
                    highlightColors,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ChatSearchResultCardHeader(
    conversationScope: String,
    isCurrentConversation: Boolean,
    result: ParsedSearchMessage,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            LettaIcons.ChatOutline,
            contentDescription = null,
            tint = if (isCurrentConversation) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = conversationScope,
            style = MaterialTheme.typography.labelSmall,
            color = if (isCurrentConversation) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
        )
        result.date?.let(::formatRelativeTime)?.takeIf { it.isNotBlank() }?.let { timeText ->
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@androidx.annotation.VisibleForTesting
internal fun chatSearchResultKey(result: ParsedSearchMessage, index: Int): String {
    val identity = result.messageId
        ?: result.conversationId?.let { conversationId ->
            "$conversationId-${result.content.hashCode()}"
        }
        ?: result.content.hashCode().toString()
    return "chat-search-$identity-$index"
}
