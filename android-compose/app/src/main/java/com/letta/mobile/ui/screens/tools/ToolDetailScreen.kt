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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.navigation.optionalSharedElement
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.MultiFieldInputDialog
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.components.TagDrillInDialog
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.tags.TagDrillInEntityType
import com.letta.mobile.ui.tags.TagDrillInSource
import com.letta.mobile.ui.tags.TagDrillInViewModel
import com.letta.mobile.ui.theme.LettaTopBarDefaults

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
    val tagDrillInViewModel: TagDrillInViewModel = hiltViewModel()
    val tagDrillInState by tagDrillInViewModel.uiState.collectAsStateWithLifecycle()
    var showActionSheet by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAttachDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(deleteState) {
        if (deleteState is UiState.Success) {
            viewModel.clearDeleteState()
            onNavigateBack()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    val toolName = (uiState as? UiState.Success)?.data?.name
                    Text(toolName ?: stringResource(R.string.screen_tool_detail_title))
                },
                colors = LettaTopBarDefaults.largeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    val tool = (uiState as? UiState.Success)?.data
                    if (tool != null) {
                        IconButton(onClick = { showActionSheet = true }) {
                            Icon(LettaIcons.MoreVert, stringResource(R.string.action_more))
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
                onTagClick = { tag ->
                    tagDrillInViewModel.showTag(
                        tag,
                        TagDrillInSource(TagDrillInEntityType.TOOL, state.data.id),
                    )
                },
                modifier = Modifier.padding(paddingValues),
            )
        }
    }

    val tool = (uiState as? UiState.Success)?.data
    if (tool != null) {
        ActionSheet(
            show = showActionSheet,
            onDismiss = { showActionSheet = false },
            title = tool.name,
        ) {
            ActionSheetItem(
                text = stringResource(R.string.screen_tool_detail_attach_action),
                icon = LettaIcons.Tool,
                onClick = {
                    showActionSheet = false
                    showAttachDialog = true
                },
            )
            if (isEditableTool(tool)) {
                ActionSheetItem(
                    text = stringResource(R.string.screen_tool_detail_edit_action),
                    icon = LettaIcons.Edit,
                    onClick = {
                        showActionSheet = false
                        showEditDialog = true
                    },
                )
                ActionSheetItem(
                    text = stringResource(R.string.screen_tool_detail_delete_action),
                    icon = LettaIcons.Delete,
                    onClick = {
                        showActionSheet = false
                        showDeleteDialog = true
                    },
                    destructive = true,
                )
            }
        }
    }

    if (tool != null && showEditDialog) {
        EditToolDialog(
            tool = tool,
            onDismiss = { showEditDialog = false },
            onSave = { description, sourceCode, tags ->
                viewModel.updateTool(description, sourceCode, tags)
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

    TagDrillInDialog(
        state = tagDrillInState,
        onDismiss = tagDrillInViewModel::dismiss,
    )

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
    onTagClick: (String) -> Unit,
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
                        imageVector = LettaIcons.Tool,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(LettaIconSizing.Toolbar)
                            .optionalSharedElement("tool_icon_${tool.id}"),
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
            CardGroup(title = { Text(stringResource(R.string.screen_tool_detail_overview_title)) }) {
                item(
                    headlineContent = { Text(stringResource(R.string.common_type)) },
                    supportingContent = { Text(tool.toolType ?: stringResource(R.string.common_unknown)) },
                )
                tool.sourceType?.let { sourceType ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_tool_detail_source_type)) },
                        supportingContent = { Text(sourceType) },
                    )
                }
                item(
                    headlineContent = { Text(stringResource(R.string.common_status)) },
                    supportingContent = {
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

        if (tool.tags.isNotEmpty()) {
            item {
                CardGroup(title = { Text(stringResource(R.string.common_tags)) }) {
                    item(
                        headlineContent = {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                tool.tags.forEach { tag ->
                                    AssistChip(
                                        onClick = { onTagClick(tag) },
                                        label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }

        if (tool.createdAt != null || tool.updatedAt != null) {
            item {
                CardGroup(title = { Text(stringResource(R.string.common_metadata)) }) {
                    tool.createdAt?.let { ts ->
                        item(
                            headlineContent = { Text(stringResource(R.string.common_created)) },
                            supportingContent = { Text(ts) },
                        )
                    }
                    tool.updatedAt?.let { ts ->
                        item(
                            headlineContent = { Text(stringResource(R.string.common_updated)) },
                            supportingContent = { Text(ts) },
                        )
                    }
                }
            }
        }

        item {
            CardGroup(title = { Text(stringResource(R.string.common_agents)) }) {
                if (attachedAgents.isEmpty()) {
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_tool_detail_no_attached_agents)) },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.screen_tool_detail_attach_agents_helper),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                } else {
                    attachedAgents.forEach { agent ->
                        item(
                            headlineContent = { Text(agent.name) },
                            supportingContent = {
                                Text(
                                    text = agent.id,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = LettaIcons.People,
                                    contentDescription = null,
                                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                                )
                            },
                            trailingContent = {
                                TextButton(onClick = { onDetachAgent(agent.id) }) {
                                    Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                                }
                            },
                        )
                    }
                }
            }
        }

        tool.jsonSchema?.let { schema ->
            item {
                CardGroup(title = { Text(stringResource(R.string.screen_tool_detail_json_schema)) }) {
                    item(
                        headlineContent = {
                            CollapsibleCodeBlock(
                                title = stringResource(R.string.screen_tool_detail_json_schema),
                                icon = LettaIcons.Schema,
                                content = schema.toString(),
                            )
                        },
                    )
                }
            }
        }

        tool.sourceCode?.let { code ->
            item {
                CardGroup(title = { Text(stringResource(R.string.screen_tool_detail_source_code)) }) {
                    item(
                        headlineContent = {
                            CollapsibleCodeBlock(
                                title = stringResource(R.string.screen_tool_detail_source_code),
                                icon = LettaIcons.Code,
                                content = code,
                            )
                        },
                    )
                }
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

    MultiFieldInputDialog(
        show = true,
        title = stringResource(R.string.screen_tool_detail_attach_action),
        confirmText = stringResource(R.string.action_attach),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = selection.isNotEmpty(),
        onConfirm = { onAttach(selection.toList()) },
        content = {
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
                            Text(
                                if (agent.id in selection) stringResource(R.string.action_remove) else stringResource(R.string.action_attach)
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun EditToolDialog(
    tool: Tool,
    onDismiss: () -> Unit,
    onSave: (String?, String, List<String>?) -> Unit,
) {
    var description by remember(tool.id) { mutableStateOf(tool.description.orEmpty()) }
    var tagsText by remember(tool.id) { mutableStateOf(tool.tags.joinToString(", ")) }
    var sourceCode by remember(tool.id) { mutableStateOf(tool.sourceCode.orEmpty()) }

    MultiFieldInputDialog(
        show = true,
        title = stringResource(R.string.screen_tool_detail_edit_title),
        confirmText = stringResource(R.string.action_save),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = sourceCode.isNotBlank(),
        onConfirm = {
            onSave(
                description.trim().ifBlank { null },
                sourceCode,
                tagsText.split(',').map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { null },
            )
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    )
}

@Composable
private fun DeleteToolDialog(
    toolName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        show = true,
        title = stringResource(R.string.screen_tool_detail_delete_title),
        message = stringResource(R.string.screen_tool_detail_delete_confirm, toolName),
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
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
                imageVector = if (expanded) LettaIcons.ExpandLess else LettaIcons.ExpandMore,
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
