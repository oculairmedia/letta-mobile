package com.letta.mobile.ui.screens.tools

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerBox
import com.letta.mobile.ui.components.shimmerColor
import com.letta.mobile.ui.icons.LettaIcons

@Composable
fun ToolsScreen(
    modifier: Modifier = Modifier,
    viewModel: ToolsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        is UiState.Loading -> ToolsSkeletonList(modifier = modifier)
        is UiState.Error -> ErrorContent(
            message = state.message,
            onRetry = { viewModel.loadTools() },
            modifier = modifier
        )
        is UiState.Success -> ToolsContent(
            state = state.data,
            onRemoveTool = { viewModel.removeTool(it.id) },
            modifier = modifier
        )
    }
}

@Composable
private fun ToolsContent(
    state: ToolsUiState,
    onRemoveTool: (Tool) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTool by remember { mutableStateOf<Tool?>(null) }

    if (state.tools.isEmpty()) {
        EmptyState(
            icon = LettaIcons.Tool,
            message = stringResource(R.string.screen_tools_empty_attached),
            modifier = modifier.fillMaxSize()
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = state.tools,
                key = { it.id }
            ) { tool ->
                ToolCard(
                    tool = tool,
                    onClick = { selectedTool = tool },
                    onRemove = { onRemoveTool(tool) },
                )
            }
        }
    }

    selectedTool?.let { tool ->
        ToolDetailDialog(
            tool = tool,
            onDismiss = { selectedTool = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCard(
    tool: Tool,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showRemoveDialog by remember { mutableStateOf(false) }
    val truncatedDesc = tool.description?.take(80)?.let {
        if ((tool.description?.length ?: 0) > 80) "$it..." else it
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showRemoveDialog = true },
            ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = LettaIcons.Tool,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                truncatedDesc?.let { desc ->
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = LettaIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    ConfirmDialog(
        show = showRemoveDialog,
        title = stringResource(R.string.screen_tools_dialog_remove_title),
        message = stringResource(R.string.screen_tools_dialog_remove_confirm, tool.name),
        confirmText = stringResource(R.string.action_remove),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showRemoveDialog = false; onRemove() },
        onDismiss = { showRemoveDialog = false },
        destructive = true,
    )
}

@Composable
private fun ToolDetailDialog(
    tool: Tool,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LettaIcons.Tool, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(tool.name)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tool.description?.let { desc ->
                    Text(text = desc, style = MaterialTheme.typography.bodyMedium)
                }
                tool.toolType?.let { type ->
                    Row {
                        Text(
                            text = stringResource(R.string.common_type) + ": ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text = type, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (tool.tags.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.common_tags) + ": " + tool.tags.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun ToolsSkeletonList(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(4) {
            ToolCardSkeleton()
        }
    }
}

@Composable
private fun ToolCardSkeleton(modifier: Modifier = Modifier) {
    val color = shimmerColor()
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                ShimmerBox(widthFraction = 0.5f, height = 14.dp)
                Spacer(modifier = Modifier.height(4.dp))
                ShimmerBox(widthFraction = 0.8f, height = 12.dp)
            }
        }
    }
}
