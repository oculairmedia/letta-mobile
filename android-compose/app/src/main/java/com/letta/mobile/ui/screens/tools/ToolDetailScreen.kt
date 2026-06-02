package com.letta.mobile.ui.screens.tools

import com.letta.mobile.ui.theme.LettaCodeFont

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.navigation.optionalSharedElement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.MultiFieldInputDialog
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.components.StatusChip
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
                        TagDrillInSource(TagDrillInEntityType.TOOL, state.data.id.value),
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
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.common_error),
            message = deleteError,
            confirmText = stringResource(R.string.action_dismiss),
            onConfirm = { viewModel.clearDeleteState() },
            onDismiss = { viewModel.clearDeleteState() },
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
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ToolDetailHeader(
                tool = tool,
                onTagClick = onTagClick,
            )
        }

        tool.sourceCode?.let { code ->
            item {
                ToolCodeSection(
                    title = stringResource(R.string.screen_tool_detail_source_code),
                    icon = LettaIcons.Code,
                    content = code,
                    initiallyExpanded = true,
                    onCopy = {
                        copyToClipboard(
                            context = context,
                            label = context.getString(R.string.screen_tool_detail_source_code),
                            text = code,
                        )
                    },
                )
            }
        }

        tool.jsonSchema?.let { schema ->
            item {
                ToolCodeSection(
                    title = stringResource(R.string.screen_tool_detail_json_schema),
                    icon = LettaIcons.Schema,
                    content = schema.toString(),
                    initiallyExpanded = false,
                    onCopy = {
                        copyToClipboard(
                            context = context,
                            label = context.getString(R.string.screen_tool_detail_json_schema),
                            text = schema.toString(),
                        )
                    },
                )
            }
        }

        if (tool.toolType != null || tool.sourceType != null) {
            item {
                CardGroup(title = { Text(stringResource(R.string.screen_tool_detail_overview_title)) }) {
                    tool.toolType?.let { toolType ->
                        item(
                            headlineContent = { Text(stringResource(R.string.common_type)) },
                            supportingContent = { Text(toolType) },
                        )
                    }
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
                                    text = agent.id.value,
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
                                IconButton(onClick = { onDetachAgent(agent.id.value) }) {
                                    Icon(
                                        imageVector = LettaIcons.Close,
                                        contentDescription = stringResource(R.string.action_remove),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
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
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolDetailHeader(
    tool: Tool,
    onTagClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CardGroup(modifier = modifier) {
        item(
            headlineContent = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                StatusChip(
                                    status = if (isEditableTool(tool)) {
                                        stringResource(R.string.screen_tool_detail_editable)
                                    } else {
                                        stringResource(R.string.screen_tool_detail_read_only)
                                    }
                                )
                                tool.sourceType?.let { sourceType ->
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(sourceType, style = MaterialTheme.typography.labelSmall) },
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = tool.description ?: stringResource(R.string.screen_tool_detail_no_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (tool.description != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (tool.tags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            tool.tags.forEach { tag ->
                                AssistChip(
                                    onClick = { onTagClick(tag) },
                                    label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                }
            },
        )
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
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    CardGroup {
                        agents.forEach { agent ->
                            val isSelected = agent.id.value in selection
                            item(
                                onClick = {
                                    selection = if (isSelected) selection - agent.id.value else selection + agent.id.value
                                },
                                leadingContent = {
                                    androidx.compose.material3.Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = null,
                                    )
                                },
                                headlineContent = { Text(agent.name, style = MaterialTheme.typography.bodyMedium) },
                                supportingContent = {
                                    Text(
                                        text = agent.id.value,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = LettaCodeFont,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
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
            CardGroup {
                item(
                    headlineContent = {
                        FormItem(label = { Text(stringResource(R.string.common_description)) }) {
                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                            )
                        }
                    }
                )
                item(
                    headlineContent = {
                        FormItem(label = { Text(stringResource(R.string.screen_tool_detail_tags_label)) }) {
                            OutlinedTextField(
                                value = tagsText,
                                onValueChange = { tagsText = it },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                )
                item(
                    headlineContent = {
                        FormItem(
                            label = { Text(stringResource(R.string.screen_tool_detail_source_code)) },
                            description = { Text(stringResource(R.string.screen_tool_detail_source_code_helper)) }
                        ) {
                            OutlinedTextField(
                                value = sourceCode,
                                onValueChange = { sourceCode = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 8,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = LettaCodeFont),
                            )
                        }
                    }
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
private fun ToolCodeSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: String,
    initiallyExpanded: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(content) { mutableStateOf(initiallyExpanded) }

    CardGroup(title = { Text(title) }, modifier = modifier) {
        item(
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            headlineContent = { Text(title) },
            supportingContent = {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = LettaCodeFont),
                    maxLines = if (expanded) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis,
                    color = if (expanded) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .heightIn(min = if (expanded) 0.dp else 112.dp),
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCopy) {
                        Icon(
                            imageVector = LettaIcons.Copy,
                            contentDescription = stringResource(R.string.action_copy),
                            modifier = Modifier.size(LettaIconSizing.Toolbar),
                        )
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) LettaIcons.ExpandLess else LettaIcons.ExpandMore,
                            contentDescription = if (expanded) {
                                stringResource(R.string.screen_tool_detail_collapse)
                            } else {
                                stringResource(R.string.screen_tool_detail_expand)
                            },
                        )
                    }
                }
            },
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, context.getString(R.string.action_copied), Toast.LENGTH_SHORT).show()
}

private fun isEditableTool(tool: Tool): Boolean {
    return tool.toolType == CUSTOM_TOOL_TYPE
}
