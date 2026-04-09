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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schema
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

private const val CUSTOM_TOOL_TYPE = "custom"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: ToolDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val deleteState by viewModel.deleteState.collectAsStateWithLifecycle()
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAttachDialog by remember { mutableStateOf(false) }

    LaunchedEffect(deleteState) {
        if (deleteState is UiState.Success) {
            viewModel.clearDeleteState()
            onNavigateBack()
        }
    }

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
                actions = {
                    val tool = (uiState as? UiState.Success)?.data
                    if (tool != null) {
                        IconButton(onClick = { showAttachDialog = true }) {
                            Icon(Icons.Default.Build, stringResource(R.string.screen_tool_detail_attach_action))
                        }
                        if (isEditableTool(tool)) {
                            IconButton(onClick = { showEditDialog = true }) {
                                Icon(Icons.Default.Edit, stringResource(R.string.screen_tool_detail_edit_action))
                            }
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Default.Delete, stringResource(R.string.screen_tool_detail_delete_action))
                            }
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(
                modifier = Modifier
                    .padding(paddingValues)
                    .padding(16.dp),
            )
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadTool() },
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> ToolDetailContent(
                tool = state.data,
                attachedAgents = agentState.attachedAgents,
                onDetachAgent = viewModel::detachFromAgent,
                modifier = Modifier.padding(paddingValues),
            )
        }
    }

    val tool = (uiState as? UiState.Success)?.data
    if (tool != null && showEditDialog) {
        EditToolDialog(
            tool = tool,
            onDismiss = { showEditDialog = false },
            onSave = { name, description, sourceCode, tags ->
                viewModel.updateTool(name, description, sourceCode, tags)
                showEditDialog = false
            },
        )
    }

    if (tool != null && showDeleteDialog) {
        DeleteToolDialog(
            toolName = tool.name,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                viewModel.deleteTool()
                showDeleteDialog = false
            },
        )
    }

    if (showAttachDialog) {
        AgentAttachDialog(
            agents = agentState.availableAgents,
            onDismiss = { showAttachDialog = false },
            onAttach = { selectedIds ->
                selectedIds.forEach(viewModel::attachToAgent)
                showAttachDialog = false
            },
        )
    }

    val deleteError = (deleteState as? UiState.Error)?.message
    if (deleteError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearDeleteState() },
            title = { Text(stringResource(R.string.common_error)) },
            text = { Text(deleteError) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearDeleteState() }) {
                    Text(stringResource(R.string.action_dismiss))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolDetailContent(
    tool: Tool,
    attachedAgents: List<com.letta.mobile.data.model.Agent>,
    onDetachAgent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                    text = tool.description ?: stringResource(R.string.screen_tool_detail_no_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tool.description != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tool.toolType?.let { type ->
                    AssistChip(onClick = {}, label = { Text(type) })
                }
                tool.sourceType?.let { sourceType ->
                    AssistChip(
                        onClick = {},
                        label = {
                            Text("${stringResource(R.string.screen_tool_detail_source_type)}: $sourceType")
                        },
                    )
                }
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            if (isEditableTool(tool)) {
                                stringResource(R.string.screen_tool_detail_editable)
                            } else {
                                stringResource(R.string.screen_tool_detail_read_only)
                            }
                        )
                    },
                )
            }
        }

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
                    tool.tags?.forEach { tag ->
                        AssistChip(onClick = {}, label = { Text(tag, style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }
        }

        if (tool.createdAt != null || tool.updatedAt != null) {
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                tool.createdAt?.let { ts ->
                    Text(
                        text = stringResource(R.string.screen_tool_detail_created, ts),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                tool.updatedAt?.let { ts ->
                    Text(
                        text = stringResource(R.string.screen_tool_detail_updated, ts),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.common_agents),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (attachedAgents.isEmpty()) {
                Text(
                    text = stringResource(R.string.screen_tool_detail_no_attached_agents),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                attachedAgents.forEach { agent ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(agent.name, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onDetachAgent(agent.id) }) {
                            Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

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
private fun AgentAttachDialog(
    agents: List<com.letta.mobile.data.model.Agent>,
    onDismiss: () -> Unit,
    onAttach: (List<String>) -> Unit,
) {
    var selection by remember(agents) { mutableStateOf(emptySet<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_tool_detail_attach_action)) },
        text = {
            if (agents.isEmpty()) {
                Text(
                    text = stringResource(R.string.screen_tool_detail_no_available_agents),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(agents, key = { it.id }) { agent ->
                        TextButton(
                            onClick = {
                                selection = if (agent.id in selection) selection - agent.id else selection + agent.id
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(agent.name, modifier = Modifier.weight(1f))
                            Text(if (agent.id in selection) stringResource(R.string.action_remove) else stringResource(R.string.action_attach))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAttach(selection.toList()) }, enabled = selection.isNotEmpty()) {
                Text(stringResource(R.string.action_attach))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun EditToolDialog(
    tool: Tool,
    onDismiss: () -> Unit,
    onSave: (String, String?, String, List<String>?) -> Unit,
) {
    var name by remember(tool.id) { mutableStateOf(tool.name) }
    var description by remember(tool.id) { mutableStateOf(tool.description.orEmpty()) }
    var tagsText by remember(tool.id) { mutableStateOf(tool.tags?.joinToString(", ").orEmpty()) }
    var sourceCode by remember(tool.id) { mutableStateOf(tool.sourceCode.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_tool_detail_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.common_name)) },
                    supportingText = { Text(stringResource(R.string.screen_tool_detail_name_helper)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.common_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text(stringResource(R.string.screen_tool_detail_tags_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = sourceCode,
                    onValueChange = { sourceCode = it },
                    label = { Text(stringResource(R.string.screen_tool_detail_source_code)) },
                    supportingText = { Text(stringResource(R.string.screen_tool_detail_source_code_helper)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        name.trim(),
                        description.trim().ifBlank { null },
                        sourceCode,
                        tagsText.split(',').map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { null },
                    )
                },
                enabled = name.isNotBlank() && sourceCode.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun DeleteToolDialog(
    toolName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_tool_detail_delete_title)) },
        text = { Text(stringResource(R.string.screen_tool_detail_delete_confirm, toolName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
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
                contentDescription = if (expanded) {
                    stringResource(R.string.screen_tool_detail_collapse)
                } else {
                    stringResource(R.string.screen_tool_detail_expand)
                },
            )
        }

        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            color = if (expanded) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun isEditableTool(tool: Tool): Boolean {
    return tool.toolType == CUSTOM_TOOL_TYPE
}
