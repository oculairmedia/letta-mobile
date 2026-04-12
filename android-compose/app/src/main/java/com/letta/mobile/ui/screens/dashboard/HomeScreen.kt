package com.letta.mobile.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.ui.components.LettaInputBar
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.ui.theme.statValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToAgents: () -> Unit,
    onNavigateToConversations: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToBlocks: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToChat: (agentId: String, initialMessage: String?) -> Unit,
    onNavigateToChatMessage: (agentId: String, conversationId: String, messageId: String) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Letta")
                        if (uiState.isConnected) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                LettaIcons.Circle,
                                contentDescription = "Connected",
                                tint = MaterialTheme.customColors.onlineColor,
                                modifier = Modifier.size(8.dp),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(LettaIcons.Settings, contentDescription = "Settings")
                    }
                },
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        HomeContent(
            state = uiState,
            onSearchQueryChange = viewModel::updateSearchQuery,
            onClearSearch = viewModel::clearSearch,
            onNavigateToAgents = onNavigateToAgents,
            onNavigateToConversations = onNavigateToConversations,
            onNavigateToTools = onNavigateToTools,
            onNavigateToBlocks = onNavigateToBlocks,
            onNavigateToChat = onNavigateToChat,
            onNavigateToChatMessage = onNavigateToChatMessage,
            modifier = Modifier.padding(paddingValues),
        )
    }
}

@Composable
private fun HomeContent(
    state: DashboardUiState,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onNavigateToAgents: () -> Unit,
    onNavigateToConversations: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToBlocks: () -> Unit,
    onNavigateToChat: (String, String?) -> Unit,
    onNavigateToChatMessage: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        state.error?.let { error ->
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        UberSearchBar(
            query = state.searchQuery,
            onQueryChange = onSearchQueryChange,
            onClear = onClearSearch,
        )

        if (state.isSearchActive) {
            SearchResultsContent(
                agentResults = state.agentResults,
                messageResults = state.messageResults,
                isSearching = state.isSearching,
                searchQuery = state.searchQuery,
                onAgentClick = { agentId -> onNavigateToChat(agentId, null) },
                onMessageClick = { parsed ->
                    val agentId = parsed.agentId ?: return@SearchResultsContent
                    val convId = parsed.conversationId
                    val msgId = parsed.messageId
                    if (convId != null && msgId != null) {
                        onNavigateToChatMessage(agentId, convId, msgId)
                    } else {
                        onNavigateToChat(agentId, null)
                    }
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatCard(
                    label = "Agents",
                    value = state.agentCount?.toString(),
                    icon = LettaIcons.People,
                    onClick = onNavigateToAgents,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "Chats",
                    value = state.conversationCount?.toString(),
                    icon = LettaIcons.Chat,
                    onClick = onNavigateToConversations,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "Tools",
                    value = state.toolCount?.toString(),
                    icon = LettaIcons.Tool,
                    onClick = onNavigateToTools,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "Blocks",
                    value = state.blockCount?.toString(),
                    icon = LettaIcons.ViewModule,
                    onClick = onNavigateToBlocks,
                    modifier = Modifier.weight(1f),
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.serverUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            FavoriteAgentCard(
                favoriteAgentId = state.favoriteAgentId,
                favoriteAgentName = state.favoriteAgentName,
                onNavigateToChat = { onNavigateToChat(it, null) },
                onSetFavorite = onNavigateToAgents,
            )

            if (state.pinnedAgents.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                state.pinnedAgents.forEach { pinned ->
                    PinnedAgentCard(
                        name = pinned.name,
                        onClick = { onNavigateToChat(pinned.id, null) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (state.favoriteAgentId != null) {
                var homeChatText by remember { mutableStateOf("") }
                LettaInputBar(
                    text = homeChatText,
                    onTextChange = { homeChatText = it },
                    onSend = { message ->
                        onNavigateToChat(state.favoriteAgentId, message)
                        homeChatText = ""
                    },
                    placeholder = stringResource(R.string.screen_home_chat_placeholder),
                    sendContentDescription = stringResource(R.string.action_send_message),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun UberSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme

    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        placeholder = {
            Text(
                stringResource(R.string.screen_home_search_placeholder),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        leadingIcon = {
            Icon(
                LettaIcons.Search,
                contentDescription = stringResource(R.string.action_search),
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        LettaIcons.Clear,
                        contentDescription = "Clear",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(24.dp),
        colors = TextFieldDefaults.colors(
            unfocusedContainerColor = colorScheme.surfaceContainerHigh,
            focusedContainerColor = colorScheme.surfaceContainerHigh,
            unfocusedIndicatorColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = colorScheme.primary,
        ),
    )
}

@Composable
private fun FavoriteAgentCard(
    favoriteAgentId: String?,
    favoriteAgentName: String?,
    onNavigateToChat: (String) -> Unit,
    onSetFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (favoriteAgentId != null && favoriteAgentName != null) {
        Card(
            onClick = { onNavigateToChat(favoriteAgentId) },
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    LettaIcons.Agent,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = favoriteAgentName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = stringResource(R.string.screen_home_favorite_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
        }
    } else {
        Card(
            onClick = onSetFavorite,
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    LettaIcons.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.screen_home_set_favorite_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinnedAgentCard(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                LettaIcons.Agent,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.screen_home_pinned_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
            Icon(
                LettaIcons.Pin,
                contentDescription = "Pinned",
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}


@Composable
private fun SearchResultsContent(
    agentResults: List<Agent>,
    messageResults: List<ParsedSearchMessage>,
    isSearching: Boolean,
    searchQuery: String,
    onAgentClick: (String) -> Unit,
    onMessageClick: (ParsedSearchMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val highlightTextColor = MaterialTheme.colorScheme.primary

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (agentResults.isNotEmpty()) {
            item(key = "agents-header") {
                Text(
                    text = stringResource(R.string.screen_home_search_agents_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            items(agentResults, key = { "agent-${it.id}" }) { agent ->
                Card(
                    onClick = { onAgentClick(agent.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            LettaIcons.Agent,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = highlightMatches(agent.name, searchQuery, highlightColor, highlightTextColor),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            agent.description?.let { desc ->
                                Text(
                                    text = highlightMatches(desc, searchQuery, highlightColor, highlightTextColor),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (messageResults.isNotEmpty()) {
            item(key = "messages-header") {
                Text(
                    text = stringResource(R.string.screen_home_search_messages_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(messageResults.size, key = { "msg-$it" }) { index ->
                val msg = messageResults[index]
                Card(
                    onClick = { onMessageClick(msg) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                LettaIcons.Chat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = msg.role?.replaceFirstChar { it.uppercase() } ?: "Message",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = highlightMatches(msg.content ?: "", searchQuery, highlightColor, highlightTextColor),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (isSearching) {
            item(key = "loading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        if (!isSearching && agentResults.isEmpty() && messageResults.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.screen_home_search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
    }
}

private fun highlightMatches(
    text: String,
    query: String,
    highlightColor: Color,
    matchTextColor: Color = Color.Unspecified,
) = buildAnnotatedString {
    if (query.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    val lowerText = text.lowercase()
    val lowerQuery = query.trim().lowercase()
    var cursor = 0
    var matched = false
    while (cursor < text.length) {
        val matchIndex = lowerText.indexOf(lowerQuery, cursor)
        if (matchIndex < 0) {
            append(text.substring(cursor))
            break
        }
        matched = true
        append(text.substring(cursor, matchIndex))
        withStyle(
            SpanStyle(
                background = highlightColor,
                fontWeight = FontWeight.Bold,
                color = if (matchTextColor != Color.Unspecified) matchTextColor else Color.Unspecified,
            )
        ) {
            append(text.substring(matchIndex, matchIndex + lowerQuery.length))
        }
        cursor = matchIndex + lowerQuery.length
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatCard(
    label: String,
    value: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.statValue,
                )
            } else {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.statValue,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
