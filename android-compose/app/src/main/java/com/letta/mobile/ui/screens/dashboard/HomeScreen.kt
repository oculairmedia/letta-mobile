package com.letta.mobile.ui.screens.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.ExpandableTitleSearch
import com.letta.mobile.ui.components.LettaInputBar
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.ui.theme.statValue
import java.util.Locale

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
    onNavigateToEditAgent: (agentId: String) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    ExpandableTitleSearch(
                        query = uiState.searchQuery,
                        onQueryChange = viewModel::updateSearchQuery,
                        onClear = viewModel::clearSearch,
                        expanded = isSearchExpanded,
                        onExpandedChange = { isSearchExpanded = it },
                        placeholder = stringResource(R.string.screen_home_search_placeholder),
                        openSearchContentDescription = stringResource(R.string.action_search),
                        closeSearchContentDescription = stringResource(R.string.action_close),
                        titleContent = {
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
                        },
                    )
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
            onNavigateToAgents = onNavigateToAgents,
            onNavigateToConversations = onNavigateToConversations,
            onNavigateToTools = onNavigateToTools,
            onNavigateToBlocks = onNavigateToBlocks,
            onNavigateToChat = onNavigateToChat,
            onNavigateToChatMessage = onNavigateToChatMessage,
            onNavigateToEditAgent = onNavigateToEditAgent,
            onClearFavorite = viewModel::clearFavorite,
            onUnpinAgent = viewModel::unpinAgent,
            modifier = Modifier.padding(paddingValues),
        )
    }
}

@Composable
private fun HomeContent(
    state: DashboardUiState,
    onNavigateToAgents: () -> Unit,
    onNavigateToConversations: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToBlocks: () -> Unit,
    onNavigateToChat: (String, String?) -> Unit,
    onNavigateToChatMessage: (String, String, String) -> Unit,
    onNavigateToEditAgent: (String) -> Unit,
    onClearFavorite: () -> Unit,
    onUnpinAgent: (String) -> Unit,
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

        if (state.isSearchActive) {
            SearchResultsContent(
                agentResults = state.agentResults,
                messageResults = state.messageResults,
                toolResults = state.toolResults,
                blockResults = state.blockResults,
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
                onToolClick = { onNavigateToTools() },
                onBlockClick = { onNavigateToBlocks() },
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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

                UsageAnalyticsCard(
                    usageSummary = state.usageSummary,
                    isLoading = state.isUsageLoading,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )

                FavoriteAgentCard(
                    favoriteAgentId = state.favoriteAgentId,
                    favoriteAgentName = state.favoriteAgentName,
                    onNavigateToChat = { onNavigateToChat(it, null) },
                    onSetFavorite = onNavigateToAgents,
                    onClearFavorite = onClearFavorite,
                    onConfigure = { id -> onNavigateToEditAgent(id) },
                )

                if (state.pinnedAgents.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    state.pinnedAgents.forEach { pinned ->
                        PinnedAgentCard(
                            name = pinned.name,
                            onClick = { onNavigateToChat(pinned.id, null) },
                            onUnpin = { onUnpinAgent(pinned.id) },
                            onConfigure = { onNavigateToEditAgent(pinned.id) },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                }
            }

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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UsageAnalyticsCard(
    usageSummary: DashboardUsageSummary?,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.screen_home_usage_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.screen_home_usage_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = LettaIcons.Sparkles,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            when {
                isLoading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        ContainedLoadingIndicator()
                    }
                }

                usageSummary == null || usageSummary.sampledSteps == 0 -> {
                    Text(
                        text = stringResource(R.string.screen_home_usage_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        UsageMetricCard(
                            label = stringResource(R.string.screen_home_usage_total_label),
                            value = formatNumber(usageSummary.totalTokens),
                            icon = LettaIcons.Database,
                            modifier = Modifier.weight(1f),
                        )
                        UsageMetricCard(
                            label = stringResource(R.string.screen_home_usage_hourly_label),
                            value = formatNumber(usageSummary.averageTokensPerHour),
                            icon = LettaIcons.AccessTime,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    HorizontalDivider()

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.screen_home_usage_model_split_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        usageSummary.modelUsage.take(5).forEachIndexed { index, modelUsage ->
                            ModelUsageRow(modelUsage = modelUsage)
                            if (index < usageSummary.modelUsage.take(5).lastIndex) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageMetricCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = value, style = MaterialTheme.typography.statValue)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelUsageRow(
    modelUsage: ModelTokenUsage,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = modelUsage.model,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.screen_home_usage_model_tokens_label, formatNumber(modelUsage.totalTokens)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.screen_home_usage_model_share_label, modelUsage.sharePercent),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteAgentCard(
    favoriteAgentId: String?,
    favoriteAgentName: String?,
    onNavigateToChat: (String) -> Unit,
    onSetFavorite: () -> Unit,
    onClearFavorite: () -> Unit,
    onConfigure: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (favoriteAgentId != null && favoriteAgentName != null) {
        var showMenu by remember { mutableStateOf(false) }
        val haptic = LocalHapticFeedback.current

        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .combinedClickable(
                    onClick = { onNavigateToChat(favoriteAgentId) },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showMenu = true
                    },
                ),
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

        ActionSheet(
            show = showMenu,
            onDismiss = { showMenu = false },
            title = favoriteAgentName,
        ) {
            ActionSheetItem(
                text = stringResource(R.string.action_configure_agent),
                icon = LettaIcons.Edit,
                onClick = { showMenu = false; onConfigure(favoriteAgentId) },
            )
            ActionSheetItem(
                text = stringResource(R.string.action_remove_favorite),
                icon = LettaIcons.FavoriteBorder,
                onClick = { showMenu = false; onClearFavorite() },
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedAgentCard(
    name: String,
    onClick: () -> Unit,
    onUnpin: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showMenu = true
                },
            ),
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

    ActionSheet(
        show = showMenu,
        onDismiss = { showMenu = false },
        title = name,
    ) {
        ActionSheetItem(
            text = stringResource(R.string.action_configure_agent),
            icon = LettaIcons.Edit,
            onClick = { showMenu = false; onConfigure() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_unpin_agent),
            icon = LettaIcons.PinOff,
            onClick = { showMenu = false; onUnpin() },
        )
    }
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchResultsContent(
    agentResults: List<Agent>,
    messageResults: List<ParsedSearchMessage>,
    toolResults: List<Tool>,
    blockResults: List<Block>,
    isSearching: Boolean,
    searchQuery: String,
    onAgentClick: (String) -> Unit,
    onMessageClick: (ParsedSearchMessage) -> Unit,
    onToolClick: (String) -> Unit,
    onBlockClick: (String) -> Unit,
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

        if (toolResults.isNotEmpty()) {
            item(key = "tools-header") {
                Text(
                    text = stringResource(R.string.screen_home_search_tools_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(toolResults, key = { "tool-${it.id}" }) { tool ->
                Card(
                    onClick = { onToolClick(tool.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            LettaIcons.Tool,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = highlightMatches(tool.name, searchQuery, highlightColor, highlightTextColor),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            tool.description?.let { desc ->
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

        if (blockResults.isNotEmpty()) {
            item(key = "blocks-header") {
                Text(
                    text = stringResource(R.string.screen_home_search_blocks_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(blockResults, key = { "block-${it.id}" }) { block ->
                Card(
                    onClick = { onBlockClick(block.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            LettaIcons.ViewModule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = highlightMatches(block.label ?: "Unnamed", searchQuery, highlightColor, highlightTextColor),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            block.description?.let { desc ->
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
                    LoadingIndicator()
                }
            }
        }

        if (!isSearching && agentResults.isEmpty() && messageResults.isEmpty() && toolResults.isEmpty() && blockResults.isEmpty()) {
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

private fun formatNumber(value: Int): String = String.format(Locale.US, "%,d", value)

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
