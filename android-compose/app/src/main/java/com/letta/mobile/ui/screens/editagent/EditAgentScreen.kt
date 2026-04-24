package com.letta.mobile.ui.screens.editagent

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.ModelDropdown
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.screens.settings.ClientModeConnectionState
import com.letta.mobile.ui.screens.tools.ToolPickerDialog
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showActionSheet by remember { mutableStateOf(false) }
    var showCloneDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_agent_edit_title)) },
                colors = LettaTopBarDefaults.largeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.saveAgent {
                            snackbar.dispatch(context.getString(R.string.screen_agent_edit_agent_saved))
                            onNavigateBack()
                        }
                    }) {
                        Icon(LettaIcons.Save, contentDescription = stringResource(R.string.action_save_changes))
                    }
                    IconButton(onClick = { showActionSheet = true }) {
                        Icon(LettaIcons.MoreVert, contentDescription = "More actions")
                    }
                },
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
            is UiState.Success -> {
                EditAgentContent(
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
                    onClientModeEnabledChange = { viewModel.updateClientModeEnabled(it) },
                    onClientModeBaseUrlChange = { viewModel.updateClientModeBaseUrl(it) },
                    onClientModeApiKeyChange = { viewModel.updateClientModeApiKey(it) },
                    onTestClientModeConnection = { viewModel.testClientModeConnection() },
                    contentPadding = paddingValues,
                )

                // ActionSheet
                ActionSheet(
                    show = showActionSheet,
                    onDismiss = { showActionSheet = false },
                    title = "Actions",
                ) {
                    ActionSheetItem(
                        text = stringResource(R.string.action_save_settings),
                        icon = LettaIcons.Check,
                        onClick = {
                            showActionSheet = false
                            viewModel.saveAgent {
                                snackbar.dispatch(context.getString(R.string.screen_agent_edit_agent_saved))
                                onNavigateBack()
                            }
                        },
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.action_export_agent),
                        icon = LettaIcons.Share,
                        onClick = {
                            showActionSheet = false
                            viewModel.exportAgent { exportData ->
                                val exported = shareAgentExport(context, exportData)
                                snackbar.dispatch(
                                    context.getString(
                                        if (exported) R.string.screen_settings_export_ready else R.string.screen_settings_export_unavailable
                                    )
                                )
                            }
                        },
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.action_clone_agent),
                        icon = LettaIcons.Copy,
                        onClick = {
                            showActionSheet = false
                            showCloneDialog = true
                        },
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.action_reset_messages),
                        icon = LettaIcons.Refresh,
                        onClick = {
                            showActionSheet = false
                            showResetDialog = true
                        },
                        destructive = true,
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.screen_agents_dialog_delete_title),
                        icon = LettaIcons.Delete,
                        onClick = {
                            showActionSheet = false
                            showDeleteDialog = true
                        },
                        destructive = true,
                    )
                }

                // Confirm dialogs
                ConfirmDialog(
                    show = showResetDialog,
                    title = stringResource(R.string.screen_settings_reset_messages_title),
                    message = stringResource(R.string.screen_settings_reset_messages_confirm),
                    confirmText = stringResource(R.string.action_reset_messages),
                    dismissText = stringResource(R.string.action_cancel),
                    onConfirm = {
                        showResetDialog = false
                        viewModel.resetMessages {
                            snackbar.dispatch(context.getString(R.string.screen_settings_messages_reset))
                        }
                    },
                    onDismiss = { showResetDialog = false },
                    destructive = true,
                )

                ConfirmDialog(
                    show = showDeleteDialog,
                    title = stringResource(R.string.screen_agents_dialog_delete_title),
                    message = stringResource(R.string.screen_agents_dialog_delete_confirm_permanent),
                    confirmText = stringResource(R.string.action_delete),
                    dismissText = stringResource(R.string.action_cancel),
                    onConfirm = {
                        showDeleteDialog = false
                        viewModel.deleteAgent(onNavigateBack)
                    },
                    onDismiss = { showDeleteDialog = false },
                    destructive = true,
                )

                if (showCloneDialog) {
                    CloneAgentDialog(
                        initialName = state.data.name,
                        isCloning = state.data.isCloning,
                        onDismiss = { showCloneDialog = false },
                        onClone = { cloneName, overrideExistingTools, stripMessages ->
                            showCloneDialog = false
                            viewModel.cloneAgent(
                                cloneName = cloneName,
                                overrideExistingTools = overrideExistingTools,
                                stripMessages = stripMessages,
                            ) { response ->
                                snackbar.dispatch(
                                    context.getString(
                                        if (response.agentIds.size == 1) R.string.screen_settings_clone_success_single else R.string.screen_settings_clone_success_multiple,
                                        response.agentIds.size,
                                    )
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
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
    onClientModeEnabledChange: (Boolean) -> Unit,
    onClientModeBaseUrlChange: (String) -> Unit,
    onClientModeApiKeyChange: (String) -> Unit,
    onTestClientModeConnection: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarDispatcher.current
    var showToolPicker by remember { mutableStateOf(false) }
    var showAddBlockDialog by remember { mutableStateOf(false) }
    var showAttachBlockDialog by remember { mutableStateOf(false) }
    var selectedTool by remember { mutableStateOf<Tool?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Header ──
        item(key = "header") {
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
                    if (state.agentType.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.common_type) + ": ${state.agentType}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.common_id), state.agentId))
                    snackbar.dispatch(context.getString(R.string.screen_agent_edit_agent_id_copied))
                }) {
                    Icon(LettaIcons.Copy, contentDescription = stringResource(R.string.screen_agent_edit_copy_agent_id))
                }
            }
        }

        // ── Identity ──
        item(key = "identity") {
            CardGroup(title = { Text("Identity") }) {
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = onNameChange,
                            label = { Text(stringResource(R.string.common_name)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = state.description,
                            onValueChange = onDescriptionChange,
                            label = { Text(stringResource(R.string.common_description)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    },
                )
            }
        }

        // ── Model ──
        item(key = "model") {
            CardGroup(title = { Text(stringResource(R.string.common_model)) }) {
                item(
                    headlineContent = {
                        ModelDropdown(
                            selectedModel = state.model,
                            models = llmModels,
                            onModelSelected = onModelChange,
                            onLoadModels = onLoadModels,
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.common_model),
                        )
                    },
                )
                item(
                    headlineContent = {
                        ModelDropdown(
                            selectedModel = state.embedding,
                            models = embeddingModels.map {
                                LlmModel(
                                    id = it.id,
                                    name = it.displayName,
                                    providerType = it.providerType,
                                    contextWindow = it.embeddingDim,
                                )
                            },
                            onModelSelected = onEmbeddingChange,
                            onLoadModels = onLoadModels,
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.screen_agent_edit_embedding_model),
                        )
                    },
                )
            }
        }

        // ── LLM Configuration ──
        item(key = "llm_config") {
            CardGroup(title = { Text(stringResource(R.string.screen_agent_edit_llm_configuration)) }) {
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = state.providerType,
                            onValueChange = onProviderTypeChange,
                            label = { Text(stringResource(R.string.screen_agent_edit_provider_type)) },
                            placeholder = { Text(stringResource(R.string.screen_agents_create_provider_placeholder)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    },
                )
                item(
                    headlineContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        }
                    },
                )
                if (state.contextWindow > 0) {
                    item(
                        headlineContent = { Text(stringResource(R.string.common_context_window)) },
                        trailingContent = {
                            Text(
                                text = state.contextWindow.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
                item(
                    headlineContent = { Text(stringResource(R.string.common_parallel_tool_calls)) },
                    trailingContent = {
                        Switch(checked = state.parallelToolCalls, onCheckedChange = onParallelToolCallsChange)
                    },
                )
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = state.maxOutputTokens.toString(),
                            onValueChange = { it.toIntOrNull()?.let(onMaxOutputTokensChange) },
                            label = { Text(stringResource(R.string.common_max_output_tokens)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.common_enable_sleeptime)) },
                    trailingContent = {
                        Switch(checked = state.enableSleeptime, onCheckedChange = onEnableSleeptimeChange)
                    },
                )
            }
        }

        item(key = "client_mode") {
            EditAgentClientModeSection(
                state = state,
                onClientModeEnabledChange = onClientModeEnabledChange,
                onClientModeBaseUrlChange = onClientModeBaseUrlChange,
                onClientModeApiKeyChange = onClientModeApiKeyChange,
                onTestClientModeConnection = onTestClientModeConnection,
            )
        }

        // ── Memory Blocks ──
        item(key = "memory_blocks") {
            CardGroup(title = { Text("${stringResource(R.string.screen_agent_memory_blocks_section)} (${state.blocks.size})") }) {
                state.blocks.forEach { block ->
                    item(
                        headlineContent = {
                            MemoryBlockItem(
                                block = block,
                                onValueChange = { onBlockValueChange(block.label, it) },
                                onDescriptionChange = { onBlockDescriptionChange(block.label, it) },
                                onLimitChange = { onBlockLimitChange(block.label, it) },
                                onDelete = { onDeleteBlock(block.id) },
                            )
                        },
                    )
                }
                item(
                    onClick = { showAddBlockDialog = true },
                    headlineContent = { Text(stringResource(R.string.screen_agent_edit_add_memory_block)) },
                    leadingContent = { Icon(LettaIcons.Add, contentDescription = null, modifier = Modifier.size(LettaIconSizing.Toolbar)) },
                )
                item(
                    onClick = { showAttachBlockDialog = true },
                    headlineContent = { Text(stringResource(R.string.screen_agent_edit_attach_existing_block)) },
                    leadingContent = { Icon(LettaIcons.Add, contentDescription = null, modifier = Modifier.size(LettaIconSizing.Toolbar)) },
                )
            }
        }

        // ── Tools ──
        item(key = "tools") {
            CardGroup(title = { Text(stringResource(R.string.common_tools) + " (${state.attachedTools.size})") }) {
                if (state.attachedTools.isEmpty()) {
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.screen_tools_empty_attached),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                } else {
                    state.attachedTools.forEach { tool ->
                        item(
                            onClick = { selectedTool = tool },
                            headlineContent = {
                                Text(tool.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = tool.description?.let { desc ->
                                {
                                    Text(
                                        text = desc,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            leadingContent = {
                                Icon(
                                    LettaIcons.Tool,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )
                    }
                }
                item(
                    onClick = { showToolPicker = true },
                    headlineContent = { Text(stringResource(R.string.screen_agent_edit_attach_tools)) },
                    leadingContent = { Icon(LettaIcons.Add, contentDescription = null, modifier = Modifier.size(LettaIconSizing.Toolbar)) },
                )
            }
        }

        // ── System Prompt ──
        item(key = "system_prompt") {
            CardGroup(title = { Text(stringResource(R.string.common_system_prompt) + " (${state.systemPrompt.length} chars)") }) {
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = state.systemPrompt,
                            onValueChange = onSystemPromptChange,
                            label = { Text(stringResource(R.string.common_system_prompt)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 5,
                        )
                    },
                )
            }
        }

        // ── Tags ──
        item(key = "tags") {
            var newTag by remember { mutableStateOf("") }
            CardGroup(title = { Text(stringResource(R.string.common_tags)) }) {
                if (state.tags.isNotEmpty()) {
                    item(
                        headlineContent = {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                state.tags.forEach { tag ->
                                    InputChip(
                                        selected = false,
                                        onClick = { onRemoveTag(tag) },
                                        label = { Text(tag) },
                                        trailingIcon = {
                                            Icon(
                                                LettaIcons.Close,
                                                contentDescription = stringResource(R.string.screen_agent_edit_remove_tag),
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
                item(
                    headlineContent = {
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
                    },
                )
            }
        }
    }

    // ── Dialogs ──

    selectedTool?.let { tool ->
        ToolDetailDialog(
            tool = tool,
            onDismiss = { selectedTool = null },
        )
    }

    if (showAddBlockDialog) {
        AddBlockDialog(
            onDismiss = { showAddBlockDialog = false },
            onAdd = { label, value, description, limit ->
                onAddBlock(label, value, description, limit)
                showAddBlockDialog = false
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

@Composable
private fun EditAgentClientModeSection(
    state: EditAgentUiState,
    onClientModeEnabledChange: (Boolean) -> Unit,
    onClientModeBaseUrlChange: (String) -> Unit,
    onClientModeApiKeyChange: (String) -> Unit,
    onTestClientModeConnection: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardGroup(title = { Text(stringResource(R.string.screen_settings_client_mode_section)) }) {
            item(
                headlineContent = {
                    FormItem(
                        label = { Text(stringResource(R.string.screen_settings_client_mode_enable)) },
                        description = {
                            Text(stringResource(R.string.screen_settings_client_mode_enable_description))
                        },
                        tail = {
                            Switch(
                                checked = state.clientModeEnabled,
                                onCheckedChange = onClientModeEnabledChange,
                            )
                        },
                    )
                },
            )
        }

        if (state.clientModeEnabled) {
            CardGroup {
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = state.clientModeBaseUrl,
                            onValueChange = onClientModeBaseUrlChange,
                            label = { Text(stringResource(R.string.screen_settings_client_mode_server_url)) },
                            placeholder = {
                                Text(stringResource(R.string.screen_settings_client_mode_server_url_placeholder))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    },
                )
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = state.clientModeApiKey,
                            onValueChange = onClientModeApiKeyChange,
                            label = { Text(stringResource(R.string.screen_settings_client_mode_api_key)) },
                            placeholder = {
                                Text(stringResource(R.string.screen_settings_client_mode_api_key_placeholder))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.screen_settings_client_mode_api_key_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                item(
                    headlineContent = {
                        FormItem(
                            label = { Text(stringResource(R.string.screen_settings_client_mode_test_connection)) },
                            description = {
                                val statusText = clientModeConnectionStatusText(state.clientModeConnectionState)
                                if (statusText != null) {
                                    Text(
                                        text = statusText,
                                        color = clientModeConnectionStatusColor(state.clientModeConnectionState),
                                    )
                                } else {
                                    Text(stringResource(R.string.screen_settings_client_mode_test_connection_helper))
                                }
                            },
                            tail = {
                                OutlinedButton(
                                    onClick = onTestClientModeConnection,
                                    enabled = state.clientModeConnectionState !is ClientModeConnectionState.Testing &&
                                        state.clientModeBaseUrl.isNotBlank(),
                                ) {
                                    if (state.clientModeConnectionState is ClientModeConnectionState.Testing) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Text(stringResource(R.string.screen_settings_client_mode_test_connection_action))
                                    }
                                }
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun clientModeConnectionStatusText(state: ClientModeConnectionState): String? {
    val formatter = remember {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
    }

    return when (state) {
        ClientModeConnectionState.Idle -> null
        ClientModeConnectionState.Testing -> stringResource(R.string.screen_settings_client_mode_testing)
        is ClientModeConnectionState.Success -> stringResource(
            R.string.screen_settings_client_mode_success,
            formatter.format(Instant.ofEpochMilli(state.testedAtMillis).atZone(ZoneId.systemDefault())),
        )
        is ClientModeConnectionState.Failure -> stringResource(
            R.string.screen_settings_client_mode_failure,
            state.message,
            formatter.format(Instant.ofEpochMilli(state.testedAtMillis).atZone(ZoneId.systemDefault())),
        )
    }
}

@Composable
private fun clientModeConnectionStatusColor(state: ClientModeConnectionState) = when (state) {
    ClientModeConnectionState.Idle,
    ClientModeConnectionState.Testing,
    -> MaterialTheme.colorScheme.onSurfaceVariant
    is ClientModeConnectionState.Success -> MaterialTheme.colorScheme.tertiary
    is ClientModeConnectionState.Failure -> MaterialTheme.colorScheme.error
}

// ---------------------------------------------------------------------------
// Memory block item
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryBlockItem(
    block: EditAgentUiState.BlockState,
    onValueChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onLimitChange: (Int?) -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = if (block.readOnly) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showDeleteConfirm = true },
                )
        },
    ) {
        OutlinedTextField(
            value = block.value,
            onValueChange = onValueChange,
            label = { Text(block.label) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = !block.readOnly,
            supportingText = block.limit?.let { limit ->
                { Text("${block.value.length}/$limit chars") }
            },
        )
        OutlinedTextField(
            value = block.description.orEmpty(),
            onValueChange = onDescriptionChange,
            label = { Text(stringResource(R.string.common_description)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = !block.readOnly,
        )
        OutlinedTextField(
            value = block.limit?.toString().orEmpty(),
            onValueChange = { value ->
                if (value.isBlank() || value.toIntOrNull() != null) {
                    onLimitChange(value.toIntOrNull())
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
    }

    ConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.screen_agent_edit_detach_block_title, block.label),
        message = stringResource(R.string.screen_agent_edit_detach_block_message),
        confirmText = stringResource(R.string.action_remove),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showDeleteConfirm = false; onDelete() },
        onDismiss = { showDeleteConfirm = false },
        destructive = true,
    )
}

// ---------------------------------------------------------------------------
// Add block dialog
// ---------------------------------------------------------------------------

@Composable
private fun AddBlockDialog(
    onDismiss: () -> Unit,
    onAdd: (label: String, value: String, description: String, limit: Int?) -> Unit,
) {
    var newLabel by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }
    var newLimit by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
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
                onClick = { onAdd(newLabel, newValue, newDescription, newLimit.toIntOrNull()) },
                enabled = newLabel.isNotBlank(),
            ) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ---------------------------------------------------------------------------
// Clone dialog
// ---------------------------------------------------------------------------

@Composable
private fun CloneAgentDialog(
    initialName: String,
    isCloning: Boolean,
    onDismiss: () -> Unit,
    onClone: (cloneName: String?, overrideExistingTools: Boolean, stripMessages: Boolean) -> Unit,
) {
    var cloneName by remember(initialName) { mutableStateOf(if (initialName.isBlank()) "" else "$initialName Copy") }
    var overrideExistingTools by remember { mutableStateOf(true) }
    var stripMessages by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_settings_clone_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.screen_settings_clone_dialog_helper),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = cloneName,
                    onValueChange = { cloneName = it },
                    label = { Text(stringResource(R.string.screen_settings_clone_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.screen_agents_import_override_tools_title), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.screen_agents_import_override_tools_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = overrideExistingTools, onCheckedChange = { overrideExistingTools = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.screen_agents_import_strip_messages_title), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.screen_agents_import_strip_messages_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = stripMessages, onCheckedChange = { stripMessages = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onClone(cloneName.ifBlank { null }, overrideExistingTools, stripMessages)
                },
                enabled = !isCloning,
            ) {
                Text(stringResource(R.string.action_clone_agent))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCloning) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Tool detail dialog
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun shareAgentExport(context: Context, exportData: String): Boolean {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, exportData)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.screen_settings_export_subject))
    }

    val chooser = Intent.createChooser(shareIntent, context.getString(R.string.action_export_agent))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    try {
        context.startActivity(chooser)
        return true
    } catch (_: ActivityNotFoundException) {
        return false
    }
}
