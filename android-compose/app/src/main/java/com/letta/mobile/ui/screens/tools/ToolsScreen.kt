package com.letta.mobile.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
    if (state.tools.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Build,
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
                    onRemove = { onRemoveTool(tool) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolCard(
    tool: Tool,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showRemoveDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = "Tool",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tool.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                tool.description?.let { description ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                tool.toolType?.let { toolType ->
                    Spacer(modifier = Modifier.height(8.dp))
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = toolType,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }

            IconButton(onClick = { showRemoveDialog = true }) {
                Icon(Icons.Default.Close, "Remove")
            }
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
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ShimmerBox(widthFraction = 0.5f, height = 16.dp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                ShimmerBox(widthFraction = 0.8f, height = 12.dp)
                Spacer(modifier = Modifier.height(8.dp))
                ShimmerBox(widthFraction = 0.25f, height = 24.dp, cornerRadius = 12.dp)
            }
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}
