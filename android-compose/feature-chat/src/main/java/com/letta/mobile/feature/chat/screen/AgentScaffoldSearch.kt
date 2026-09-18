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
import com.letta.mobile.ui.theme.LettaDimens

internal data class ChatSearchResultsParams(
    val searchQuery: String,
    val results: List<ParsedSearchMessage>,
    val isSearching: Boolean,
    val conversations: List<Conversation>,
    val currentConversationId: String?,
    val onResultClick: (ParsedSearchMessage) -> Unit,
)

@Composable
internal fun ChatSearchResultsContent(
    params: ChatSearchResultsParams,
    modifier: Modifier = Modifier,
) {
    val highlightColors = rememberSearchHighlightColors()
    val conversationsById = remember(params.conversations) {
        params.conversations.associateBy { it.id.value }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(LettaDimens.Space.md),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        item(key = "chat-search-header") {
            ChatSearchResultsHeader(isSearching = params.isSearching)
        }

        if (!params.isSearching && params.results.isEmpty()) {
            item(key = "chat-search-empty") {
                ChatSearchResultsEmptyState()
            }
        }

        itemsIndexed(
            items = params.results,
            key = { index, result -> chatSearchResultKey(result, index) },
        ) { _, result ->
            ChatSearchResultCard(
                params = ChatSearchResultCardParams(
                    result = result,
                    searchQuery = params.searchQuery,
                    conversationsById = conversationsById,
                    currentConversationId = params.currentConversationId,
                    highlightColors = highlightColors,
                    onResultClick = params.onResultClick,
                ),
            )
        }
    }
}

@Composable
private fun ChatSearchResultsHeader(isSearching: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.screen_home_search_messages_section),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (isSearching) {
            CircularProgressIndicator(modifier = Modifier.size(LettaDimens.Space.lg), strokeWidth = LettaDimens.Space.hair)
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
            .padding(LettaDimens.Space.xxl),
    )
}

private data class ChatSearchResultCardParams(
    val result: ParsedSearchMessage,
    val searchQuery: String,
    val conversationsById: Map<String, Conversation>,
    val currentConversationId: String?,
    val highlightColors: com.letta.mobile.ui.components.SearchHighlightColors,
    val onResultClick: (ParsedSearchMessage) -> Unit,
)

@Composable
private fun ChatSearchResultCard(params: ChatSearchResultCardParams) {
    val conversation = params.result.conversationId?.let(params.conversationsById::get)
    val isCurrentConversation = params.result.conversationId != null &&
        params.result.conversationId == params.currentConversationId
    val conversationScope = when {
        isCurrentConversation -> "Current conversation"
        params.result.conversationId != null -> "Previous conversation"
        else -> "Conversation unknown"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { params.onResultClick(params.result) },
    ) {
        Column(modifier = Modifier.padding(LettaDimens.Space.md)) {
            ChatSearchResultCardHeader(
                conversationScope = conversationScope,
                isCurrentConversation = isCurrentConversation,
                result = params.result,
            )
            conversation?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = LettaDimens.Space.hair),
                )
            }
            Text(
                text = highlightSearchMatches(
                    searchResultSnippet(params.result.content.orEmpty(), params.searchQuery),
                    params.searchQuery,
                    params.highlightColors,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = LettaDimens.Space.sm),
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
            modifier = Modifier.size(LettaDimens.Space.lg),
        )
        Spacer(modifier = Modifier.width(LettaDimens.Space.sm))
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
            Spacer(modifier = Modifier.width(LettaDimens.Space.sm))
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
