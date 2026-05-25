package com.letta.mobile.feature.editagent

import com.letta.mobile.ui.theme.LettaCodeFont

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
import com.letta.mobile.feature.editagent.R
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
    onResetMessages: () -> Unit,
    onDeleteAgent: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    lazyListState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
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

    // letta-mobile-cygd: tab gating + section picker bottom sheet
    // removed. All sections (Basics, Models, Memory, Tools, Runtime,
    // Advanced) render unconditionally in the LazyColumn below; the
    // user scrolls between them. CardGroup titles act as in-line
    // section headers. A future follow-up can re-introduce a
    // jump-to-section index from the screen title.

    LaunchedEffect(maxContextWindow, state.contextWindow) {
        if (maxContextWindow != null && state.contextWindow > maxContextWindow) {
            onContextWindowChange(maxContextWindow)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(EditAgentTestTags.CONTENT_LIST),
        state = lazyListState,
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
                        fontFamily = LettaCodeFont,
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

        // letta-mobile-cygd: section-picker Surface (qfn9) removed.
        // All sections render inline below; no more "Section: X" jump
        // affordance. A future PR can resurface validation warnings via
        // an aggregate banner here if/when needed.

        // ── Basics ──
        // letta-mobile-cygd: tab gating removed; all sections render
        // sequentially so the user can scroll the entire agent config
        // without bouncing through the section picker.
        // letta-mobile-mpr4: each section gets a stickyHeader so the
        // current section name pins to the top while its content
        // scrolls under it.
        stickyHeader(key = SectionAnchors.BASICS) {
            EditAgentSectionHeader(stringResource(R.string.screen_agent_edit_section_basics))
        }
        run {
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

        // ── Models ──
        stickyHeader(key = SectionAnchors.MODELS) {
            EditAgentSectionHeader(stringResource(R.string.screen_agent_edit_section_models))
        }
        run {
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

        // ── Advanced ──
        stickyHeader(key = SectionAnchors.ADVANCED) {
            EditAgentSectionHeader(stringResource(R.string.screen_agent_edit_section_advanced))
        }
        run {
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

        // ── Memory ──
        stickyHeader(key = SectionAnchors.MEMORY) {
            EditAgentSectionHeader(stringResource(R.string.screen_agent_edit_section_memory))
        }
        run {
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

        // ── Tools ──
        stickyHeader(key = SectionAnchors.TOOLS) {
            EditAgentSectionHeader(stringResource(R.string.screen_agent_edit_section_tools))
        }
        run {
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

        // ── Runtime ──
        stickyHeader(key = SectionAnchors.RUNTIME) {
            EditAgentSectionHeader(stringResource(R.string.screen_agent_edit_section_runtime))
        }
        // ── Danger Zone ──
        // letta-mobile-cygd: Reset Messages and Delete Agent moved here
        // from the top-bar action sheet so destructive actions live in a
        // single, unmistakable spot at the bottom of the scroll surface
        // (errorContainer styling). Confirm dialogs are still owned by
        // EditAgentScreen, hooked via onResetMessages / onDeleteAgent.
        stickyHeader(key = SectionAnchors.DANGER) {
            EditAgentSectionHeader(
                title = stringResource(R.string.screen_create_project_danger_zone_title),
                isDanger = true,
            )
        }
        item(key = "danger_zone") {
            DangerZoneSection(
                onResetMessages = onResetMessages,
                onDeleteAgent = onDeleteAgent,
            )
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
            availableBlocks = state.availableBlocks,
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

    // letta-mobile-cygd: section-picker bottom sheet (qfn9) removed.
    // All sections render inline above. The hasValidationWarning helper
    // is kept below for future re-introduction of an aggregate warning
    // banner.
}

/**
 * letta-mobile-mpr4: stable keys for each section's stickyHeader.
 *
 * Used as both the LazyColumn item key (so Compose can identify the
 * header across recompositions) AND as the lookup key when the title-
 * tap Index sheet asks `EditAgentScreen` to scroll the list to a
 * specific section. Strings rather than an enum so we can include
 * "danger" without polluting [EditAgentConfigTab], which still drives
 * validation reporting elsewhere.
 */
internal object SectionAnchors {
    const val BASICS: String = "section_header_basics"
    const val MODELS: String = "section_header_models"
    const val MEMORY: String = "section_header_memory"
    const val TOOLS: String = "section_header_tools"
    const val RUNTIME: String = "section_header_runtime"
    const val ADVANCED: String = "section_header_advanced"
    const val DANGER: String = "section_header_danger"
}

/**
 * letta-mobile-mpr4: visual treatment for the in-list section pin.
 * Stays at the top of the LazyColumn viewport while the user scrolls
 * the section's contents; swaps out for the next section's pin as the
 * user crosses into it. Danger Zone variant uses the error palette so
 * the destructive section stays self-evident.
 */
@Composable
private fun EditAgentSectionHeader(
    title: String,
    isDanger: Boolean = false,
) {
    val container = if (isDanger) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (isDanger) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = container,
        contentColor = content,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun DangerZoneSection(
    onResetMessages: () -> Unit,
    onDeleteAgent: () -> Unit,
) {
    Spacer(modifier = Modifier.height(24.dp))
    CardGroup(
        title = {
            Text(
                text = stringResource(R.string.screen_create_project_danger_zone_title),
                color = MaterialTheme.colorScheme.error,
            )
        },
    ) {
        item(
            headlineContent = {
                androidx.compose.material3.OutlinedButton(
                    onClick = onResetMessages,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        LettaIcons.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_reset_messages))
                }
            },
        )
        item(
            headlineContent = {
                androidx.compose.material3.Button(
                    onClick = onDeleteAgent,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(
                        LettaIcons.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.screen_agents_dialog_delete_title))
                }
            },
        )
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
