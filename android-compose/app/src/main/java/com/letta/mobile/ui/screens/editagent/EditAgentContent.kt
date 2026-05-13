package com.letta.mobile.ui.screens.editagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.common.LocalSnackbarDispatcher

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun EditAgentContent(
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
    onAttachExistingBlocks: (List<String>) -> Unit,
    onDeleteBlock: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onAttachTool: (String) -> Unit,
    onAttachTools: (List<String>) -> Unit,
    onDetachTool: (String) -> Unit,
    onToolRulesJsonChange: (String) -> Unit,
    onAddAgentSecret: () -> Unit,
    onAgentSecretKeyChange: (Int, String) -> Unit,
    onAgentSecretValueChange: (Int, String) -> Unit,
    onRemoveAgentSecret: (Int) -> Unit,
    onAddToolEnvironmentVariable: () -> Unit,
    onToolEnvironmentVariableKeyChange: (Int, String) -> Unit,
    onToolEnvironmentVariableValueChange: (Int, String) -> Unit,
    onRemoveToolEnvironmentVariable: (Int) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onProviderTypeChange: (String) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    onMaxOutputTokensChange: (Int) -> Unit,
    onParallelToolCallsChange: (Boolean) -> Unit,
    onModelProviderNameChange: (String) -> Unit,
    onModelProviderCategoryChange: (String) -> Unit,
    onModelEnableReasonerChange: (Boolean) -> Unit,
    onModelReasoningEffortChange: (String) -> Unit,
    onModelMaxReasoningTokensChange: (String) -> Unit,
    onModelReasoningJsonChange: (String) -> Unit,
    onModelFrequencyPenaltyChange: (String) -> Unit,
    onModelVerbosityChange: (String) -> Unit,
    onModelStrictToolCallingChange: (Boolean) -> Unit,
    onModelResponseFormatJsonChange: (String) -> Unit,
    onModelResponseSchemaJsonChange: (String) -> Unit,
    onModelThinkingConfigJsonChange: (String) -> Unit,
    onModelPutInnerThoughtsInKwargsChange: (Boolean) -> Unit,
    onModelToolCallParserChange: (String) -> Unit,
    onModelAnthropicEffortChange: (String) -> Unit,
    onContextWindowChange: (Int) -> Unit,
    onEnableSleeptimeChange: (Boolean) -> Unit,
    onSummarizationPromptChange: (String) -> Unit,
    onCompactionClipCharsChange: (Int) -> Unit,
    onSlidingWindowPercentageChange: (Float) -> Unit,
    onPromptAcknowledgementChange: (Boolean) -> Unit,
    onCompactionModeChange: (String) -> Unit,
    onCompactionModelChange: (String) -> Unit,
    onCompactionModelSettingsJsonChange: (String) -> Unit,
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
    var showLlmPicker by remember { mutableStateOf(false) }
    var showEmbeddingPicker by remember { mutableStateOf(false) }
    var showCompactionModelPicker by remember { mutableStateOf(false) }
    val embeddingDropdownModels = remember(embeddingModels) {
        embeddingModels.map {
            LlmModel(
                id = it.id,
                name = it.displayName,
                handle = it.handle ?: it.embeddingModel,
                providerType = it.providerType,
            )
        }
    }
    val selectedLlmModel = remember(state.model, llmModels) {
        llmModels.firstOrNull { model ->
            model.handle.equals(state.model, ignoreCase = true) ||
                model.name.equals(state.model, ignoreCase = true) ||
                model.displayName.equals(state.model, ignoreCase = true)
        }
    }
    val selectedEmbeddingModel = remember(state.embedding, embeddingDropdownModels) {
        embeddingDropdownModels.firstOrNull { model ->
            model.handle.equals(state.embedding, ignoreCase = true) ||
                model.name.equals(state.embedding, ignoreCase = true) ||
                model.displayName.equals(state.embedding, ignoreCase = true)
        }
    }
    val selectedCompactionModel = remember(state.compactionModel, llmModels) {
        llmModels.firstOrNull { model ->
            model.handle.equals(state.compactionModel, ignoreCase = true) ||
                model.name.equals(state.compactionModel, ignoreCase = true) ||
                model.displayName.equals(state.compactionModel, ignoreCase = true)
        }
    }
    val maxContextWindow = selectedLlmModel?.contextWindow?.takeIf { it > 0 }
        ?: state.agent?.llmConfig?.contextWindow?.takeIf { it > 0 }
        ?: state.agent?.contextWindowLimit?.takeIf { it > 0 }

    var selectedTab by rememberSaveable { mutableStateOf(EditAgentConfigTab.Basics) }
    val tabs = remember { EditAgentConfigTab.entries.toList() }
    var showSectionPicker by rememberSaveable { mutableStateOf(false) }
    val sectionPickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(maxContextWindow, state.contextWindow) {
        if (maxContextWindow != null && state.contextWindow > maxContextWindow) {
            onContextWindowChange(maxContextWindow)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(EditAgentTestTags.CONTENT_LIST),
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

        // letta-mobile-qfn9: PrimaryTabRow with 6 fixed-width tabs wraps
        // text mid-word on 360–411dp screens (Basic\ns, Mode\ls, Runti\nme).
        item(key = "tabs") {
            Surface(
                onClick = { showSectionPicker = true },
                shape = LettaCardDefaults.listShape,
                color = LettaCardDefaults.listContainerColor,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(EditAgentTestTags.SECTION_PICKER_TRIGGER),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.screen_agent_edit_section_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val activeHasWarning = selectedTab.hasValidationWarning(state)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = selectedTab.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (activeHasWarning) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                            if (activeHasWarning) {
                                // letta-mobile-w3dl: non-color accessible cue.
                                // The '•' glyph alone fails screen readers and
                                // color-perception users — pair it with an
                                // Icon that carries a contentDescription.
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    LettaIcons.Error,
                                    contentDescription = stringResource(R.string.screen_agent_edit_validation_warning),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    // letta-mobile-qfn9: aggregate validation indicator. When
                    // a section other than the active one has a warning, the
                    // user could previously see the '•' on the affected tab.
                    // Surface that via a label here so warnings remain visible
                    // even when the affected section isn't selected.
                    val otherWarnings = tabs.count { it != selectedTab && it.hasValidationWarning(state) }
                    if (otherWarnings > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(
                                LettaIcons.Error,
                                contentDescription = stringResource(
                                    R.string.screen_agent_edit_validation_warning_count,
                                    otherWarnings,
                                ),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = otherWarnings.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Icon(
                        LettaIcons.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (selectedTab == EditAgentConfigTab.Basics) {
            // ── Identity ──
            item(key = "identity") {
                CardGroup(title = { Text(stringResource(R.string.screen_agent_edit_identity_section)) }) {
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

            // ── System Prompt ──
            item(key = "system_prompt") {
                CardGroup(title = { Text(stringResource(R.string.common_system_prompt) + " (${state.systemPrompt.length} chars)") }) {
                    item(
                        headlineContent = {
                            OutlinedTextField(
                                value = state.systemPrompt,
                                onValueChange = onSystemPromptChange,
                                label = { Text(stringResource(R.string.common_system_prompt), style = MaterialTheme.typography.bodySmall) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 5,
                                textStyle = MaterialTheme.typography.bodySmall,
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

        if (selectedTab == EditAgentConfigTab.Models) {
            // ── Model ──
            item(key = "model") {
                CardGroup(title = { Text(stringResource(R.string.common_model)) }) {
                    item(
                        headlineContent = {
                            SearchPickerField(
                                label = stringResource(R.string.common_model),
                                title = selectedLlmModel?.displayName ?: state.model,
                                supporting = selectedLlmModel?.handle ?: state.model,
                                onClick = {
                                    onLoadModels()
                                    showLlmPicker = true
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            SearchPickerField(
                                label = stringResource(R.string.screen_agent_edit_embedding_model),
                                title = selectedEmbeddingModel?.displayName ?: state.embedding,
                                supporting = selectedEmbeddingModel?.handle ?: state.embedding,
                                onClick = {
                                    onLoadModels()
                                    showEmbeddingPicker = true
                                },
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
                    item(
                        headlineContent = {
                            ContextWindowLimitSlider(
                                value = state.contextWindow,
                                maxValue = maxContextWindow,
                                onValueChange = onContextWindowChange,
                            )
                        },
                    )
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
        }

        if (selectedTab == EditAgentConfigTab.Advanced) {
            item(key = "primary_model_advanced") {
                PrimaryModelAdvancedSection(
                    state = state,
                    onModelProviderNameChange = onModelProviderNameChange,
                    onModelProviderCategoryChange = onModelProviderCategoryChange,
                    onModelEnableReasonerChange = onModelEnableReasonerChange,
                    onModelReasoningEffortChange = onModelReasoningEffortChange,
                    onModelMaxReasoningTokensChange = onModelMaxReasoningTokensChange,
                    onModelReasoningJsonChange = onModelReasoningJsonChange,
                    onModelFrequencyPenaltyChange = onModelFrequencyPenaltyChange,
                    onModelVerbosityChange = onModelVerbosityChange,
                    onModelStrictToolCallingChange = onModelStrictToolCallingChange,
                    onModelResponseFormatJsonChange = onModelResponseFormatJsonChange,
                    onModelResponseSchemaJsonChange = onModelResponseSchemaJsonChange,
                    onModelThinkingConfigJsonChange = onModelThinkingConfigJsonChange,
                    onModelPutInnerThoughtsInKwargsChange = onModelPutInnerThoughtsInKwargsChange,
                    onModelToolCallParserChange = onModelToolCallParserChange,
                    onModelAnthropicEffortChange = onModelAnthropicEffortChange,
                )
            }
        }

        if (selectedTab == EditAgentConfigTab.Memory) {
            item(key = "advanced_compaction") {
                AdvancedCompactionSection(
                    state = state,
                    onSummarizationPromptChange = onSummarizationPromptChange,
                    onCompactionClipCharsChange = onCompactionClipCharsChange,
                    onSlidingWindowPercentageChange = onSlidingWindowPercentageChange,
                    onPromptAcknowledgementChange = onPromptAcknowledgementChange,
                    onCompactionModeChange = onCompactionModeChange,
                    onCompactionModelChange = onCompactionModelChange,
                    onCompactionModelSettingsJsonChange = onCompactionModelSettingsJsonChange,
                    compactionModelTitle = selectedCompactionModel?.displayName
                        ?: state.compactionModel.ifBlank { stringResource(R.string.screen_agent_edit_compaction_model_default) },
                    compactionModelSupporting = selectedCompactionModel?.handle ?: state.compactionModel,
                    onOpenCompactionModelPicker = { showCompactionModelPicker = true },
                )
            }

            // Memory Blocks
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
        }

        if (selectedTab == EditAgentConfigTab.Tools) {
            item(key = "tool_environment") {
                ToolEnvironmentSection(
                    state = state,
                    onAddAgentSecret = onAddAgentSecret,
                    onAgentSecretKeyChange = onAgentSecretKeyChange,
                    onAgentSecretValueChange = onAgentSecretValueChange,
                    onRemoveAgentSecret = onRemoveAgentSecret,
                    onAddToolEnvironmentVariable = onAddToolEnvironmentVariable,
                    onToolEnvironmentVariableKeyChange = onToolEnvironmentVariableKeyChange,
                    onToolEnvironmentVariableValueChange = onToolEnvironmentVariableValueChange,
                    onRemoveToolEnvironmentVariable = onRemoveToolEnvironmentVariable,
                )
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

            item(key = "tool_rules") {
                ToolRulesSection(
                    state = state,
                    onToolRulesJsonChange = onToolRulesJsonChange,
                )
            }
        }

        if (selectedTab == EditAgentConfigTab.Runtime) {
            item(key = "client_mode") {
                EditAgentClientModeSection(
                    state = state,
                    onClientModeEnabledChange = onClientModeEnabledChange,
                    onClientModeBaseUrlChange = onClientModeBaseUrlChange,
                    onClientModeApiKeyChange = onClientModeApiKeyChange,
                    onTestClientModeConnection = onTestClientModeConnection,
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
        FullScreenBlockPickerDialog(
            excludedBlockIds = state.blocks.map { it.id },
            onDismiss = { showAttachBlockDialog = false },
            onConfirm = { selectedIds ->
                onAttachExistingBlocks(selectedIds)
                showAttachBlockDialog = false
            },
        )
    }

    if (showToolPicker) {
        FullScreenToolPickerDialog(
            tools = state.availableTools.filter { candidate ->
                state.attachedTools.none { attached -> attached.id == candidate.id }
            },
            selectedToolIds = emptyList(),
            title = stringResource(R.string.screen_agent_edit_attach_tools),
            onDismiss = { showToolPicker = false },
            onConfirm = { selectedIds ->
                onAttachTools(selectedIds)
                showToolPicker = false
            },
        )
    }

    if (showLlmPicker) {
        FullScreenModelPickerDialog(
            title = stringResource(R.string.common_model),
            placeholder = stringResource(R.string.screen_models_search_hint),
            models = llmModels,
            selectedValue = state.model,
            onDismiss = { showLlmPicker = false },
            onModelSelected = {
                onModelChange(it)
                showLlmPicker = false
            },
        )
    }

    if (showEmbeddingPicker) {
        FullScreenModelPickerDialog(
            title = stringResource(R.string.screen_agent_edit_embedding_model),
            placeholder = stringResource(R.string.screen_models_search_hint),
            models = embeddingDropdownModels,
            selectedValue = state.embedding,
            onDismiss = { showEmbeddingPicker = false },
            onModelSelected = {
                onEmbeddingChange(it)
                showEmbeddingPicker = false
            },
        )
    }

    if (showCompactionModelPicker) {
        FullScreenModelPickerDialog(
            title = stringResource(R.string.screen_agent_edit_compaction_model),
            placeholder = stringResource(R.string.screen_models_search_hint),
            models = llmModels,
            selectedValue = state.compactionModel,
            onDismiss = { showCompactionModelPicker = false },
            onModelSelected = {
                onCompactionModelChange(it)
                showCompactionModelPicker = false
            },
        )
    }

    // letta-mobile-qfn9: section picker bottom sheet. Replaces the wrapping
    // PrimaryTabRow with a one-line trigger and a sheet that lists every
    // section. Same validation warning marker ('•') applied per row.
    if (showSectionPicker) {
        ModalBottomSheet(
            onDismissRequest = { showSectionPicker = false },
            sheetState = sectionPickerSheetState,
            modifier = Modifier.testTag(EditAgentTestTags.SECTION_PICKER_SHEET),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.screen_agent_edit_sections_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                tabs.forEach { tab ->
                    val hasWarning = tab.hasValidationWarning(state)
                    val isSelected = tab == selectedTab
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedTab = tab
                                showSectionPicker = false
                            }
                            .testTag(EditAgentTestTags.tab(tab.label)),
                        headlineContent = {
                            Text(
                                text = if (hasWarning) "${tab.label} •" else tab.label,
                                color = when {
                                    hasWarning -> MaterialTheme.colorScheme.error
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        leadingContent = {
                            if (isSelected) {
                                Icon(
                                    LettaIcons.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Spacer(modifier = Modifier.size(24.dp))
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                        ),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

internal fun EditAgentConfigTab.hasValidationWarning(state: EditAgentUiState): Boolean = when (this) {
    EditAgentConfigTab.Advanced -> listOf(
        state.modelReasoningJson,
        state.modelResponseFormatJson,
        state.modelResponseSchemaJson,
        state.modelThinkingConfigJson,
    ).any(::isInvalidJsonObjectIfPresent) ||
        isInvalidWholeNumberIfPresent(state.modelMaxReasoningTokens) ||
        isInvalidNumberIfPresent(state.modelFrequencyPenalty)
    EditAgentConfigTab.Memory -> isInvalidJsonObjectIfPresent(state.compactionModelSettingsJson)
    EditAgentConfigTab.Tools -> isInvalidJsonArrayIfPresent(state.toolRulesJson) ||
        state.agentSecrets.hasDuplicateKeys() ||
        state.toolEnvironmentVariables.hasDuplicateKeys()
    EditAgentConfigTab.Basics,
    EditAgentConfigTab.Models,
    EditAgentConfigTab.Runtime,
    -> false
}
