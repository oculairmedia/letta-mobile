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
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.components.highlightSearchMatches
import com.letta.mobile.ui.components.rememberSearchHighlightColors
import com.letta.mobile.ui.components.searchResultSnippet
import com.letta.mobile.ui.icons.LettaIcons
import androidx.compose.material3.Text

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

    var agentsExpanded by rememberSaveable { mutableStateOf(true) }
    var toolsExpanded by rememberSaveable { mutableStateOf(true) }
    var blocksExpanded by rememberSaveable { mutableStateOf(true) }
    var messagesExpanded by rememberSaveable { mutableStateOf(true) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (agentResults.isNotEmpty()) {
            item(key = "agents-header") {
                CollapsibleSectionHeader(
                    title = stringResource(R.string.screen_home_search_agents_section),
                    count = agentResults.size,
                    expanded = agentsExpanded,
                    onToggle = { agentsExpanded = !agentsExpanded },
                )
            }
            if (agentsExpanded) {
                items(agentResults, key = { "agent-${it.id}" }) { agent ->
                    Card(
                        onClick = { onAgentClick(agent) },
                        modifier = Modifier.fillMaxWidth().animateItem(),
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
                                    text = highlightSearchMatches(agent.name, searchQuery, highlightColors),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                agent.description?.let { desc ->
                                    Text(
                                        text = highlightSearchMatches(desc, searchQuery, highlightColors),
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

        if (toolResults.isNotEmpty()) {
            item(key = "tools-header") {
                CollapsibleSectionHeader(
                    title = stringResource(R.string.screen_home_search_tools_section),
                    count = toolResults.size,
                    expanded = toolsExpanded,
                    onToggle = { toolsExpanded = !toolsExpanded },
                    topPadding = true,
                )
            }
            if (toolsExpanded) {
                items(toolResults, key = { "tool-${it.id}" }) { tool ->
                    Card(
                        onClick = { onToolClick(tool.id.value) },
                        modifier = Modifier.fillMaxWidth().animateItem(),
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
                                    text = highlightSearchMatches(tool.name, searchQuery, highlightColors),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                tool.description?.let { desc ->
                                    Text(
                                        text = highlightSearchMatches(desc, searchQuery, highlightColors),
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

        if (blockResults.isNotEmpty()) {
            item(key = "blocks-header") {
                CollapsibleSectionHeader(
                    title = stringResource(R.string.screen_home_search_blocks_section),
                    count = blockResults.size,
                    expanded = blocksExpanded,
                    onToggle = { blocksExpanded = !blocksExpanded },
                    topPadding = true,
                )
            }
            if (blocksExpanded) {
                items(blockResults, key = { "block-${it.id}" }) { block ->
                    Card(
                        onClick = { onBlockClick(block.id.value) },
                        modifier = Modifier.fillMaxWidth().animateItem(),
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
                                val blockLabel = block.label ?: stringResource(R.string.screen_home_search_unnamed_block)
                                Text(
                                    text = highlightSearchMatches(blockLabel, searchQuery, highlightColors),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                block.description?.let { desc ->
                                    Text(
                                        text = highlightSearchMatches(desc, searchQuery, highlightColors),
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

        if (messageResults.isNotEmpty()) {
            item(key = "messages-header") {
                CollapsibleSectionHeader(
                    title = stringResource(R.string.screen_home_search_messages_section),
                    count = messageResults.size,
                    expanded = messagesExpanded,
                    onToggle = { messagesExpanded = !messagesExpanded },
                    topPadding = true,
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
