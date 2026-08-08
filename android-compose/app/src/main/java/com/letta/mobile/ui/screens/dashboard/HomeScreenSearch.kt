package com.letta.mobile.ui.screens.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.ui.components.highlightSearchMatches
import com.letta.mobile.ui.components.rememberSearchHighlightColors
import com.letta.mobile.ui.components.searchResultSnippet
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.preview.LettaPreviewFrame
import androidx.compose.material3.Text

private fun <T> androidx.compose.foundation.lazy.LazyListScope.SearchSection(
    keyPrefix: String,
    headerTitle: String,
    items: List<T>,
    expanded: Boolean,
    onExpandedChange: () -> Unit,
    topPadding: Boolean,
    cardIcon: androidx.compose.ui.graphics.vector.ImageVector,
    primaryText: (T) -> androidx.compose.ui.text.AnnotatedString,
    secondaryText: (T) -> androidx.compose.ui.text.AnnotatedString?,
    onClick: (T) -> Unit,
    idOf: (T) -> Any,
) {
    if (items.isEmpty()) return
    item(key = "$keyPrefix-header") {
        CollapsibleSectionHeader(
            state = CollapsibleSectionHeaderState(
                title = headerTitle,
                count = items.size,
                expanded = expanded,
                topPadding = topPadding,
            ),
            onToggle = onExpandedChange,
        )
    }
    if (expanded) {
        items(items, key = { "$keyPrefix-${idOf(it)}" }) { item ->
            Card(
                onClick = { onClick(item) },
                modifier = Modifier.fillMaxWidth().animateItem(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        cardIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = primaryText(item),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        secondaryText(item)?.let { secondary ->
                            Text(
                                text = secondary,
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
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun SearchResultsContent(
    agentResults: List<Agent>,
    messageResults: List<ParsedSearchMessage>,
    toolResults: List<Tool>,
    blockResults: List<Block>,
    isSearching: Boolean,
    searchQuery: String,
    onAgentClick: (Agent) -> Unit,
    onMessageClick: (ParsedSearchMessage) -> Unit,
    onToolClick: (String) -> Unit,
    onBlockClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightColors = rememberSearchHighlightColors()
    val agentsHeader = stringResource(R.string.screen_home_search_agents_section)
    val toolsHeader = stringResource(R.string.screen_home_search_tools_section)
    val blocksHeader = stringResource(R.string.screen_home_search_blocks_section)
    val unnamedBlock = stringResource(R.string.screen_home_search_unnamed_block)

    var agentsExpanded by rememberSaveable { mutableStateOf(true) }
    var toolsExpanded by rememberSaveable { mutableStateOf(true) }
    var blocksExpanded by rememberSaveable { mutableStateOf(true) }
    var messagesExpanded by rememberSaveable { mutableStateOf(true) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SearchSection(
            keyPrefix = "agents",
            headerTitle = agentsHeader,
            items = agentResults,
            expanded = agentsExpanded,
            onExpandedChange = { agentsExpanded = !agentsExpanded },
            topPadding = false,
            cardIcon = LettaIcons.Agent,
            primaryText = { highlightSearchMatches(it.name, searchQuery, highlightColors) },
            secondaryText = { it.description?.let { desc -> highlightSearchMatches(desc, searchQuery, highlightColors) } },
            onClick = { onAgentClick(it) },
            idOf = { it.id },
        )

        SearchSection(
            keyPrefix = "tools",
            headerTitle = toolsHeader,
            items = toolResults,
            expanded = toolsExpanded,
            onExpandedChange = { toolsExpanded = !toolsExpanded },
            topPadding = true,
            cardIcon = LettaIcons.Tool,
            primaryText = { highlightSearchMatches(it.name, searchQuery, highlightColors) },
            secondaryText = { it.description?.let { desc -> highlightSearchMatches(desc, searchQuery, highlightColors) } },
            onClick = { onToolClick(it.id.value) },
            idOf = { it.id },
        )

        SearchSection(
            keyPrefix = "blocks",
            headerTitle = blocksHeader,
            items = blockResults,
            expanded = blocksExpanded,
            onExpandedChange = { blocksExpanded = !blocksExpanded },
            topPadding = true,
            cardIcon = LettaIcons.ViewModule,
            primaryText = { block ->
                val label = block.label ?: unnamedBlock
                highlightSearchMatches(label, searchQuery, highlightColors)
            },
            secondaryText = { it.description?.let { desc -> highlightSearchMatches(desc, searchQuery, highlightColors) } },
            onClick = { onBlockClick(it.id.value) },
            idOf = { it.id },
        )

        if (messageResults.isNotEmpty()) {
            item(key = "messages-header") {
                CollapsibleSectionHeader(
                    state = CollapsibleSectionHeaderState(
                        title = stringResource(R.string.screen_home_search_messages_section),
                        count = messageResults.size,
                        expanded = messagesExpanded,
                        topPadding = true,
                    ),
                    onToggle = { messagesExpanded = !messagesExpanded },
                )
            }
            if (messagesExpanded) {
                itemsIndexed(
                    items = messageResults,
                    // letta-mobile-w3dl: identity-based key (was position-based
                    // "msg-$index") so animateItem() can track inserts/moves
                    // across result refreshes. Compose key lambdas must be
                    // unique — fall back to a position-based slug only when
                    // messageId is unexpectedly null (rare).
                    key = { index, msg -> msg.messageId ?: "msg-$index" },
                ) { _, msg ->
                    Card(
                        onClick = { onMessageClick(msg) },
                        modifier = Modifier.fillMaxWidth().animateItem(),
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
                                    text = msg.role?.replaceFirstChar { it.uppercase() }
                                        ?: stringResource(R.string.screen_home_search_message_role_fallback),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = highlightSearchMatches(
                                    searchResultSnippet(msg.content.orEmpty(), searchQuery),
                                    searchQuery,
                                    highlightColors,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (isSearching && messageResults.isEmpty()) {
            item(key = "messages-loading") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.screen_home_search_messages_section),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
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

// region Previews

private const val previewSearchQuery = "plan"

private val previewSearchAgents = listOf(
    Agent(id = AgentId("agent-1"), name = "Planner", model = "letta/letta-free", description = "Plans the week"),
)

private val previewSearchTools = listOf(
    Tool(id = ToolId("tool-1"), name = "web_search", description = "Search the web for planning data"),
)

private val previewSearchBlocks = listOf(
    Block(id = BlockId("block-1"), label = "plan_scratchpad", value = "week 32", description = "Scratchpad for plans"),
)

private val previewSearchMessages = listOf(
    ParsedSearchMessage(
        messageId = "msg-1",
        agentId = "agent-1",
        role = "assistant",
        content = "Here is the plan for this week.",
        date = "2026-08-07T18:30:00Z",
        conversationId = "conv-1",
    ),
)

@PreviewLightDark
@Composable
private fun SearchResultsContentPreview() {
    LettaPreviewFrame {
        SearchResultsContent(
            agentResults = previewSearchAgents,
            messageResults = previewSearchMessages,
            toolResults = previewSearchTools,
            blockResults = previewSearchBlocks,
            isSearching = false,
            searchQuery = previewSearchQuery,
            onAgentClick = {},
            onMessageClick = {},
            onToolClick = {},
            onBlockClick = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun SearchResultsContentEmptyPreview() {
    LettaPreviewFrame {
        SearchResultsContent(
            agentResults = emptyList(),
            messageResults = emptyList(),
            toolResults = emptyList(),
            blockResults = emptyList(),
            isSearching = false,
            searchQuery = previewSearchQuery,
            onAgentClick = {},
            onMessageClick = {},
            onToolClick = {},
            onBlockClick = {},
        )
    }
}

// endregion
