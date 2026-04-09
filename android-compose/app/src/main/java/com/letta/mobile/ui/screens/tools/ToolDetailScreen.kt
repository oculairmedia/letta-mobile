package com.letta.mobile.ui.screens.tools

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schema
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: ToolDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val toolName = (uiState as? UiState.Success)?.data?.name
                    Text(toolName ?: stringResource(R.string.screen_tool_detail_title))
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(
                modifier = Modifier.padding(paddingValues).padding(16.dp),
            )
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadTool() },
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> ToolDetailContent(
                tool = state.data,
                modifier = Modifier.padding(paddingValues),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolDetailContent(
    tool: Tool,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = tool.name,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = tool.description
                        ?: stringResource(R.string.screen_tool_detail_no_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tool.description != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        // Type + source type chips
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tool.toolType?.let { type ->
                    AssistChip(
                        onClick = {},
                        label = { Text(type) },
                    )
                }
                tool.sourceType?.let { sourceType ->
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                "${stringResource(R.string.screen_tool_detail_source_type)}: $sourceType"
                            )
                        },
                    )
                }
            }
        }

        // Tags
        if (!tool.tags.isNullOrEmpty()) {
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.common_tags),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tool.tags.forEach { tag ->
                        AssistChip(
                            onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }

        // Timestamps
        if (tool.createdAt != null || tool.updatedAt != null) {
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                tool.createdAt?.let { ts ->
                    Text(
                        text = "Created: $ts",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                tool.updatedAt?.let { ts ->
                    Text(
                        text = "Updated: $ts",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // JSON Schema
        tool.jsonSchema?.let { schema ->
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                CollapsibleCodeBlock(
                    title = stringResource(R.string.screen_tool_detail_json_schema),
                    icon = Icons.Default.Schema,
                    content = schema.toString(),
                )
            }
        }

        // Source Code
        tool.sourceCode?.let { code ->
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                CollapsibleCodeBlock(
                    title = stringResource(R.string.screen_tool_detail_source_code),
                    icon = Icons.Default.Code,
                    content = code,
                )
            }
        }
    }
}

@Composable
private fun CollapsibleCodeBlock(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        }

        if (!expanded) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
