package com.letta.mobile.ui.screens.editagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.screens.blocks.BlockPickerDialog
import com.letta.mobile.ui.components.Accordions
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.components.ModelDropdown
import com.letta.mobile.ui.screens.tools.ToolPickerDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAgentScreen(
    onNavigateBack: () -> Unit,
    viewModel: EditAgentViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val llmModels by viewModel.llmModels.collectAsStateWithLifecycle()
    val embeddingModels by viewModel.embeddingModels.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_agent_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadAgent() },
                modifier = Modifier.padding(paddingValues)
            )
            is UiState.Success -> EditAgentContent(
                state = state.data,
                llmModels = llmModels,
                embeddingModels = embeddingModels,
                onNameChange = { viewModel.updateName(it) },
                onDescriptionChange = { viewModel.updateDescription(it) },
                onModelChange = { viewModel.updateModel(it) },
                onEmbeddingChange = { viewModel.updateEmbedding(it) },
                onLoadModels = { viewModel.loadModels() },
                onBlockValueChange = { label, value -> viewModel.updateBlockValue(label, value) },
                onBlockDescriptionChange = { label, value -> viewModel.updateBlockDescription(label, value) },
                onBlockLimitChange = { label, value -> viewModel.updateBlockLimit(label, value) },
                onAddBlock = { label, value, description, limit -> viewModel.addBlock(label, value, description, limit) },
                onAttachExistingBlock = { viewModel.attachExistingBlock(it) },
                onDeleteBlock = { viewModel.deleteBlock(it) },
                onAddTag = { viewModel.addTag(it) },
                onRemoveTag = { viewModel.removeTag(it) },
                onAttachTool = { viewModel.attachTool(it) },
                onDetachTool = { viewModel.detachTool(it) },
                onSystemPromptChange = { viewModel.updateSystemPrompt(it) },
                onProviderTypeChange = { viewModel.updateProviderType(it) },
                onTemperatureChange = { viewModel.updateTemperature(it) },
                onMaxOutputTokensChange = { viewModel.updateMaxOutputTokens(it) },
                onParallelToolCallsChange = { viewModel.updateParallelToolCalls(it) },
                onEnableSleeptimeChange = { viewModel.updateEnableSleeptime(it) },
                onSave = {
                    viewModel.saveAgent {
                        snackbar.dispatch(context.getString(R.string.screen_agent_edit_agent_saved))
                        onNavigateBack()
                    }
                },
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditAgentContent(
    state: EditAgentUiState,
    llmModels: List<LlmModel>,
    embeddingModels: List<EmbeddingModel>,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onEmbeddingChange: (String) -> Unit,
    onLoadModels: () -> Unit,
    onBlockValueChange: (String, String) -> Unit,
    onBlockDescriptionChange: (String, String) -> Unit,
    onBlockLimitChange: (String, Int?) -> Unit,
    onAddBlock: (String, String, String, Int?) -> Unit,
    onAttachExistingBlock: (String) -> Unit,
    onDeleteBlock: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onAttachTool: (String) -> Unit,
    onDetachTool: (String) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onProviderTypeChange: (String) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    onMaxOutputTokensChange: (Int) -> Unit,
    onParallelToolCallsChange: (Boolean) -> Unit,
    onEnableSleeptimeChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarDispatcher.current
    var showToolPicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.name.ifBlank { stringResource(R.string.screen_agent_edit_default_name) },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = state.agentId,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.common_id), state.agentId))
                snackbar.dispatch(context.getString(R.string.screen_agent_edit_agent_id_copied))
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.screen_agent_edit_copy_agent_id))
            }
        }

        if (state.agentType.isNotBlank()) {
            Text(
                text = stringResource(R.string.common_type) + ": ${state.agentType}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.common_name)) },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChange,
            label = { Text(stringResource(R.string.common_description)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        ModelDropdown(
            selectedModel = state.model,
            models = llmModels,
            onModelSelected = onModelChange,
            onLoadModels = onLoadModels,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.common_model),
        )

        ModelDropdown(
            selectedModel = state.embedding,
            models = embeddingModels.map { LlmModel(id = it.id, name = it.displayName, providerType = it.providerType, contextWindow = it.embeddingDim) },
            onModelSelected = onEmbeddingChange,
            onLoadModels = onLoadModels,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.screen_agent_edit_embedding_model),
        )

        HorizontalDivider()

        var llmConfigExpanded by remember { mutableStateOf(false) }
        Accordions(
            title = stringResource(R.string.screen_agent_edit_llm_configuration),
            subtitle = stringResource(R.string.screen_agent_edit_temperature_subtitle, state.temperature),
            expanded = llmConfigExpanded,
            onExpandedChange = { llmConfigExpanded = it },
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.providerType,
                    onValueChange = onProviderTypeChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_provider_type)) },
                    placeholder = { Text(stringResource(R.string.screen_agents_create_provider_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    stringResource(R.string.screen_agent_edit_temperature_value, state.temperature),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.temperature,
                    onValueChange = onTemperatureChange,
                    valueRange = 0f..2f,
                    steps = 39,
                )

                if (state.contextWindow > 0) {
                    Text(
                        stringResource(R.string.screen_agent_edit_context_window, state.contextWindow),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.common_parallel_tool_calls), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = state.parallelToolCalls, onCheckedChange = onParallelToolCallsChange)
                }

                OutlinedTextField(
                    value = state.maxOutputTokens.toString(),
                    onValueChange = { it.toIntOrNull()?.let(onMaxOutputTokensChange) },
                    label = { Text(stringResource(R.string.common_max_output_tokens)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.common_enable_sleeptime), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = state.enableSleeptime, onCheckedChange = onEnableSleeptimeChange)
                }
            }
        }

        HorizontalDivider()

        var memoryExpanded by remember { mutableStateOf(true) }
        var showAddBlockDialog by remember { mutableStateOf(false) }
        var showAttachBlockDialog by remember { mutableStateOf(false) }
        Accordions(
            title = stringResource(R.string.screen_agent_memory_blocks_section),
            subtitle = "${state.blocks.size} blocks",
            expanded = memoryExpanded,
            onExpandedChange = { memoryExpanded = it },
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.blocks.forEach { block ->
                    var showDeleteConfirm by remember { mutableStateOf(false) }
                    val editableModifier = if (block.readOnly) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { showDeleteConfirm = true },
                            )
                    }

                    OutlinedTextField(
                        value = block.value,
                        onValueChange = { onBlockValueChange(block.label, it) },
                        label = { Text(block.label) },
                        modifier = editableModifier,
                        minLines = 2,
                        enabled = !block.readOnly,
                        supportingText = block.limit?.let { limit ->
                            { Text("${block.value.length}/$limit chars") }
                        },
                    )
                    OutlinedTextField(
                        value = block.description.orEmpty(),
                        onValueChange = { onBlockDescriptionChange(block.label, it) },
                        label = { Text(stringResource(R.string.common_description)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        enabled = !block.readOnly,
                    )
                    OutlinedTextField(
                        value = block.limit?.toString().orEmpty(),
                        onValueChange = { value ->
                            if (value.isBlank() || value.toIntOrNull() != null) {
                                onBlockLimitChange(block.label, value.toIntOrNull())
                            }
                        },
                        label = { Text(stringResource(R.string.screen_agent_edit_character_limit)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !block.readOnly,
                    )
                    if (block.isTemplate || block.readOnly) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (block.isTemplate) {
                                InputChip(
                                    selected = false,
                                    onClick = {},
                                    label = { Text(stringResource(R.string.screen_agent_edit_block_template)) },
                                )
                            }
                            if (block.readOnly) {
                                InputChip(
                                    selected = false,
                                    onClick = {},
                                    label = { Text(stringResource(R.string.screen_agent_edit_block_read_only)) },
                                )
                            }
                        }
                    }
                    ConfirmDialog(
                        show = showDeleteConfirm,
                        title = stringResource(R.string.screen_agent_edit_detach_block_title, block.label),
                        message = stringResource(R.string.screen_agent_edit_detach_block_message),
                        confirmText = stringResource(R.string.action_remove),
                        dismissText = stringResource(R.string.action_cancel),
                        onConfirm = { showDeleteConfirm = false; onDeleteBlock(block.id) },
                        onDismiss = { showDeleteConfirm = false },
                        destructive = true,
                    )
                }
                OutlinedButton(
                    onClick = { showAddBlockDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.screen_agent_edit_add_memory_block))
                }
                OutlinedButton(
                    onClick = { showAttachBlockDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.screen_agent_edit_attach_existing_block))
                }
            }
        }
        if (showAddBlockDialog) {
            var newLabel by remember { mutableStateOf("") }
            var newValue by remember { mutableStateOf("") }
            var newDescription by remember { mutableStateOf("") }
            var newLimit by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddBlockDialog = false },
                title = { Text(stringResource(R.string.screen_agent_edit_add_memory_block)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newLabel,
                            onValueChange = { newLabel = it },
                            label = { Text(stringResource(R.string.common_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = newValue,
                            onValueChange = { newValue = it },
                            label = { Text(stringResource(R.string.common_value)) },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = newDescription,
                            onValueChange = { newDescription = it },
                            label = { Text(stringResource(R.string.common_description)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                        OutlinedTextField(
                            value = newLimit,
                            onValueChange = { value ->
                                if (value.isBlank() || value.toIntOrNull() != null) {
                                    newLimit = value
                                }
                            },
                            label = { Text(stringResource(R.string.screen_agent_edit_character_limit)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onAddBlock(newLabel, newValue, newDescription, newLimit.toIntOrNull())
                            showAddBlockDialog = false
                        },
                        enabled = newLabel.isNotBlank(),
                    ) { Text(stringResource(R.string.action_create)) }
                },
                dismissButton = {
                    TextButton(onClick = { showAddBlockDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }
        if (showAttachBlockDialog) {
            BlockPickerDialog(
                excludedBlockIds = state.blocks.map { it.id },
                onDismiss = { showAttachBlockDialog = false },
                onConfirm = { selectedIds ->
                    selectedIds.forEach(onAttachExistingBlock)
                    showAttachBlockDialog = false
                },
            )
        }

        HorizontalDivider()

        var toolsExpanded by remember { mutableStateOf(true) }
        var selectedTool by remember { mutableStateOf<Tool?>(null) }
        Accordions(
            title = stringResource(R.string.common_tools),
            subtitle = stringResource(R.string.screen_agent_edit_attached_tools_count, state.attachedTools.size),
            expanded = toolsExpanded,
            onExpandedChange = { toolsExpanded = it },
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.attachedTools.isEmpty()) {
                    Text(
                        text = stringResource(R.string.screen_tools_empty_attached),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.attachedTools.forEach { tool ->
                        AttachedToolRow(
                            tool = tool,
                            onClick = { selectedTool = tool },
                            onDetach = { onDetachTool(tool.id) },
                        )
                    }
                }

                OutlinedButton(
                    onClick = { showToolPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.screen_agent_edit_attach_tools))
                }
            }
        }

        selectedTool?.let { tool ->
            ToolDetailDialog(
                tool = tool,
                onDismiss = { selectedTool = null },
            )
        }

        HorizontalDivider()

        var systemExpanded by remember { mutableStateOf(false) }
        Accordions(
            title = stringResource(R.string.common_system_prompt),
            subtitle = "${state.systemPrompt.length} chars",
            expanded = systemExpanded,
            onExpandedChange = { systemExpanded = it },
        ) {
            OutlinedTextField(
                value = state.systemPrompt,
                onValueChange = onSystemPromptChange,
                label = { Text(stringResource(R.string.common_system_prompt)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                minLines = 5
            )
        }

        HorizontalDivider()

        Text(stringResource(R.string.common_tags), style = MaterialTheme.typography.titleMedium)

        var newTag by remember { mutableStateOf("") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.tags.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = { onRemoveTag(tag) },
                    label = { Text(tag) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.screen_agent_edit_remove_tag),
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newTag,
                onValueChange = { newTag = it },
                label = { Text(stringResource(R.string.screen_agent_edit_new_tag)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = { onAddTag(newTag); newTag = "" },
                enabled = newTag.isNotBlank(),
            ) { Text(stringResource(R.string.action_add)) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.action_save_changes))
        }

        if (showToolPicker) {
            ToolPickerDialog(
                tools = state.availableTools.filter { candidate ->
                    state.attachedTools.none { attached -> attached.id == candidate.id }
                },
                selectedToolIds = emptyList(),
                title = stringResource(R.string.screen_agent_edit_attach_tools),
                onDismiss = { showToolPicker = false },
                onConfirm = { selectedIds ->
                    selectedIds.forEach(onAttachTool)
                    showToolPicker = false
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachedToolRow(
    tool: Tool,
    onClick: () -> Unit,
    onDetach: () -> Unit,
) {
    var showDetachDialog by remember { mutableStateOf(false) }
    val truncatedDesc = tool.description?.take(80)?.let {
        if ((tool.description?.length ?: 0) > 80) "$it..." else it
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showDetachDialog = true },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Build,
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
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    ConfirmDialog(
        show = showDetachDialog,
        title = stringResource(R.string.screen_tools_dialog_remove_title),
        message = stringResource(R.string.screen_tools_dialog_remove_confirm, tool.name),
        confirmText = stringResource(R.string.action_remove),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showDetachDialog = false; onDetach() },
        onDismiss = { showDetachDialog = false },
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
                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(20.dp))
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
