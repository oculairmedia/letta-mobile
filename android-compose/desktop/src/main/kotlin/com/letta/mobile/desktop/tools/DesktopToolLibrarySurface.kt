package com.letta.mobile.desktop.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.Tool

@Composable
fun DesktopToolLibrarySurface(
    state: DesktopToolLibraryState,
    onRefresh: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onTagToggled: (String) -> Unit,
    onClearTags: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filteredTools = state.filteredTools
    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ToolsHeader(
                state = state,
                onRefresh = onRefresh,
            )
        }
        state.errorMessage?.let { message ->
            item {
                ToolsErrorBanner(message)
            }
        }
        item {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChanged,
                label = { Text("Search tools") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.allTags.isNotEmpty()) {
            item {
                TagFilters(
                    tags = state.allTags,
                    selectedTags = state.selectedTags,
                    onTagToggled = onTagToggled,
                    onClearTags = onClearTags,
                )
            }
        }
        if (state.isLoading && state.tools.isEmpty()) {
            item {
                ToolsInfoCard("Loading tools from the active backend.")
            }
        } else if (filteredTools.isEmpty()) {
            item {
                ToolsInfoCard(state.emptyMessage)
            }
        } else {
            if (state.isLoadingMcpTools) {
                item {
                    ToolsInfoCard("Loading MCP tools. Server-backed tools will appear when handshakes complete.")
                }
            }
            items(
                items = filteredTools,
                key = { tool -> tool.id.value },
            ) { tool ->
                ToolRow(
                    tool = tool,
                    isMcpTool = tool.id.value in state.mcpToolIds,
                )
            }
            if (state.hasMorePages && state.searchQuery.isBlank()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        TextButton(
                            onClick = onLoadMore,
                            enabled = !state.isLoadingMore,
                        ) {
                            Text(if (state.isLoadingMore) "Loading more" else "Load more tools")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsHeader(
    state: DesktopToolLibraryState,
    onRefresh: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = "Skills & Tools",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${state.filteredTools.size} visible / ${state.tools.size} loaded",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onRefresh,
            enabled = !state.isLoading,
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.isLoading) "Refreshing" else "Refresh")
        }
    }
}

@Composable
private fun TagFilters(
    tags: List<String>,
    selectedTags: Set<String>,
    onTagToggled: (String) -> Unit,
    onClearTags: () -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = selectedTags.isEmpty(),
                onClick = onClearTags,
                label = { Text("All") },
            )
        }
        items(
            items = tags,
            key = { tag -> tag },
        ) { tag ->
            FilterChip(
                selected = tag in selectedTags,
                onClick = { onTagToggled(tag) },
                label = { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun ToolRow(
    tool: Tool,
    isMcpTool: Boolean,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 3.dp)
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = tool.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isMcpTool) {
                        MetadataPill("MCP", MaterialTheme.colorScheme.tertiary)
                    }
                }
                tool.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetadataPill(tool.toolType ?: tool.sourceType ?: "tool", MaterialTheme.colorScheme.primary)
                    tool.tags.take(3).forEach { tag ->
                        MetadataPill(tag, MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsInfoCard(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ToolsErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun MetadataPill(
    text: String,
    color: Color,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
