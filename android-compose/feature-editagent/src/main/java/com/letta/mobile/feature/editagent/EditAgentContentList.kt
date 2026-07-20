package com.letta.mobile.feature.editagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaCodeFont

/**
 * Bundled inputs for the edit-agent scroll surface. Keeps
 * [EditAgentContentList] at ≤4 arguments for CodeScene.
 */
internal data class EditAgentContentListParams(
    val state: EditAgentUiState,
    val selection: EditAgentContentModelSelection,
    val callbacks: EditAgentContentCallbacks,
    val dialogState: EditAgentContentDialogState,
    val contentPadding: PaddingValues,
    val lazyListState: LazyListState,
    val modifier: Modifier = Modifier,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun EditAgentContentList(params: EditAgentContentListParams) {
    val listPadding = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = params.contentPadding.calculateTopPadding() + 8.dp,
        bottom = params.contentPadding.calculateBottomPadding() + 16.dp,
    )
    LazyColumn(
        modifier = params.modifier
            .fillMaxSize()
            .testTag(EditAgentTestTags.CONTENT_LIST),
        state = params.lazyListState,
        contentPadding = listPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        editAgentHeaderItem(params)
        editAgentBasicsSection(params)
        editAgentModelsSection(params)
        editAgentAdvancedSection(params)
        editAgentMemorySection(params)
        editAgentToolsSection(params)
        editAgentRuntimeAndDangerSection(params)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.editAgentHeaderItem(params: EditAgentContentListParams) {
    item(key = "header") {
        EditAgentContentHeader(state = params.state)
    }
}

@Composable
private fun EditAgentContentHeader(state: EditAgentUiState) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarDispatcher.current
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
            clipboard.setPrimaryClip(
                ClipData.newPlainText(context.getString(R.string.common_id), state.agentId),
            )
            snackbar.dispatch(context.getString(R.string.screen_agent_edit_agent_id_copied))
        }) {
            Icon(LettaIcons.Copy, contentDescription = stringResource(R.string.screen_agent_edit_copy_agent_id))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
private fun LazyListScope.editAgentBasicsSection(params: EditAgentContentListParams) {
    stickyHeader(key = SectionAnchors.BASICS) {
        EditAgentSectionHeader(stringResource(R.string.screen_agent_edit_section_basics))
    }
    item(key = "identity") {
        EditAgentIdentityCard(params.state, params.callbacks)
    }
    item(key = "system_prompt") {
        EditAgentSystemPromptCard(params.state, params.callbacks)
    }
    item(key = "tags") {
        EditAgentTagsCard(params.state, params.callbacks)
    }
}

@Composable
private fun EditAgentIdentityCard(
    state: EditAgentUiState,
    callbacks: EditAgentContentCallbacks,
) {
    CardGroup(title = { Text(stringResource(R.string.screen_agent_edit_identity_section)) }) {
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = callbacks.onNameChange,
                    label = { Text(stringResource(R.string.common_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.description,
                    onValueChange = callbacks.onDescriptionChange,
                    label = { Text(stringResource(R.string.common_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            },
        )
    }
}

@Composable
private fun EditAgentSystemPromptCard(
    state: EditAgentUiState,
    callbacks: EditAgentContentCallbacks,
) {
    CardGroup(
        title = {
            Text(stringResource(R.string.common_system_prompt) + " (${state.systemPrompt.length} chars)")
        },
    ) {
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.systemPrompt,
                    onValueChange = callbacks.onSystemPromptChange,
                    label = {
                        Text(
                            stringResource(R.string.common_system_prompt),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditAgentTagsCard(
    state: EditAgentUiState,
    callbacks: EditAgentContentCallbacks,
) {
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
                                onClick = { callbacks.onRemoveTag(tag) },
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
                        onClick = {
                            callbacks.onAddTag(newTag)
                            newTag = ""
                        },
                        enabled = newTag.isNotBlank(),
                    ) { Text(stringResource(R.string.action_add)) }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.editAgentModelsSection(params: EditAgentContentListParams) {
    stickyHeader(key = SectionAnchors.MODELS) {
        EditAgentSectionHeader(stringResource(R.string.screen_agent_edit_section_models))
    }
    item(key = "model") {
        EditAgentModelPickerCard(params)
    }
    item(key = "llm_config") {
        EditAgentLlmConfigCard(params)
    }
}

@Composable
private fun EditAgentModelPickerCard(params: EditAgentContentListParams) {
    val state = params.state
    val selection = params.selection
    val callbacks = params.callbacks
    val dialogState = params.dialogState
    CardGroup(title = { Text(stringResource(R.string.common_model)) }) {
        item(
            headlineContent = {
                SearchPickerField(
                    label = stringResource(R.string.common_model),
                    title = selection.selectedLlmModel?.displayName ?: state.model,
                    supporting = selection.selectedLlmModel?.handle ?: state.model,
                    onClick = {
                        callbacks.onLoadModels()
                        dialogState.showLlmPicker.value = true
                    },
                )
            },
        )
        item(
            headlineContent = {
                SearchPickerField(
                    label = stringResource(R.string.screen_agent_edit_embedding_model),
                    title = selection.selectedEmbeddingModel?.displayName ?: state.embedding,
                    supporting = selection.selectedEmbeddingModel?.handle ?: state.embedding,
                    onClick = {
                        callbacks.onLoadModels()
                        dialogState.showEmbeddingPicker.value = true
                    },
                )
            },
        )
    }
}

@Composable
private fun EditAgentLlmConfigCard(params: EditAgentContentListParams) {
    val state = params.state
    val callbacks = params.callbacks
    CardGroup(title = { Text(stringResource(R.string.screen_agent_edit_llm_configuration)) }) {
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.providerType,
                    onValueChange = callbacks.onProviderTypeChange,
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
                        onValueChange = callbacks.onTemperatureChange,
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
                    maxValue = params.selection.maxContextWindow,
                    onValueChange = callbacks.onContextWindowChange,
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.common_parallel_tool_calls)) },
            trailingContent = {
                Switch(
                    checked = state.parallelToolCalls,
                    onCheckedChange = callbacks.onParallelToolCallsChange,
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.maxOutputTokens.toString(),
                    onValueChange = { it.toIntOrNull()?.let(callbacks.onMaxOutputTokensChange) },
                    label = { Text(stringResource(R.string.common_max_output_tokens)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.common_enable_sleeptime)) },
            trailingContent = {
                Switch(
                    checked = state.enableSleeptime,
                    onCheckedChange = callbacks.onEnableSleeptimeChange,
                )
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.editAgentAdvancedSection(params: EditAgentContentListParams) {
    stickyHeader(key = SectionAnchors.ADVANCED) {
        EditAgentSectionHeader(stringResource(R.string.screen_agent_edit_section_advanced))
    }
    item(key = "primary_model_advanced") {
        PrimaryModelAdvancedSection(
            state = params.state,
            onModelProviderNameChange = params.callbacks.onModelProviderNameChange,
            onModelProviderCategoryChange = params.callbacks.onModelProviderCategoryChange,
            onModelEnableReasonerChange = params.callbacks.onModelEnableReasonerChange,
            onModelReasoningEffortChange = params.callbacks.onModelReasoningEffortChange,
            onModelMaxReasoningTokensChange = params.callbacks.onModelMaxReasoningTokensChange,
            onModelReasoningJsonChange = params.callbacks.onModelReasoningJsonChange,
            onModelFrequencyPenaltyChange = params.callbacks.onModelFrequencyPenaltyChange,
            onModelVerbosityChange = params.callbacks.onModelVerbosityChange,
            onModelStrictToolCallingChange = params.callbacks.onModelStrictToolCallingChange,
            onModelResponseFormatJsonChange = params.callbacks.onModelResponseFormatJsonChange,
            onModelResponseSchemaJsonChange = params.callbacks.onModelResponseSchemaJsonChange,
            onModelThinkingConfigJsonChange = params.callbacks.onModelThinkingConfigJsonChange,
            onModelPutInnerThoughtsInKwargsChange = params.callbacks.onModelPutInnerThoughtsInKwargsChange,
            onModelToolCallParserChange = params.callbacks.onModelToolCallParserChange,
            onModelAnthropicEffortChange = params.callbacks.onModelAnthropicEffortChange,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.editAgentMemorySection(params: EditAgentContentListParams) {
    stickyHeader(key = SectionAnchors.MEMORY) {
        EditAgentSectionHeader(stringResource(R.string.screen_agent_edit_section_memory))
    }
    item(key = "advanced_compaction") {
        EditAgentCompactionCard(params)
    }
    item(key = "memory_blocks") {
        EditAgentMemoryBlocksCard(params)
    }
}

@Composable
private fun EditAgentCompactionCard(params: EditAgentContentListParams) {
    val state = params.state
    val selected = params.selection.selectedCompactionModel
    AdvancedCompactionSection(
        state = state,
        onSummarizationPromptChange = params.callbacks.onSummarizationPromptChange,
        onCompactionClipCharsChange = params.callbacks.onCompactionClipCharsChange,
        onSlidingWindowPercentageChange = params.callbacks.onSlidingWindowPercentageChange,
        onPromptAcknowledgementChange = params.callbacks.onPromptAcknowledgementChange,
        onCompactionModeChange = params.callbacks.onCompactionModeChange,
        onCompactionModelChange = params.callbacks.onCompactionModelChange,
        onCompactionModelSettingsJsonChange = params.callbacks.onCompactionModelSettingsJsonChange,
        compactionModelTitle = selected?.displayName
            ?: state.compactionModel.ifBlank {
                stringResource(R.string.screen_agent_edit_compaction_model_default)
            },
        compactionModelSupporting = selected?.handle ?: state.compactionModel,
        onOpenCompactionModelPicker = { params.dialogState.showCompactionModelPicker.value = true },
    )
}

@Composable
private fun EditAgentMemoryBlocksCard(params: EditAgentContentListParams) {
    val state = params.state
    val callbacks = params.callbacks
    CardGroup(
        title = {
            Text("${stringResource(R.string.screen_agent_memory_blocks_section)} (${state.blocks.size})")
        },
    ) {
        state.blocks.forEach { block ->
            item(
                headlineContent = {
                    MemoryBlockItem(
                        block = block,
                        onValueChange = { callbacks.onBlockValueChange(block.label, it) },
                        onDescriptionChange = { callbacks.onBlockDescriptionChange(block.label, it) },
                        onLimitChange = { callbacks.onBlockLimitChange(block.label, it) },
                        onDelete = { callbacks.onDeleteBlock(block.id) },
                    )
                },
            )
        }
        item(
            onClick = { params.dialogState.showAddBlockDialog.value = true },
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_add_memory_block)) },
            leadingContent = {
                Icon(
                    LettaIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(LettaIconSizing.Toolbar),
                )
            },
        )
        item(
            onClick = { params.dialogState.showAttachBlockDialog.value = true },
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_attach_existing_block)) },
            leadingContent = {
                Icon(
                    LettaIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(LettaIconSizing.Toolbar),
                )
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.editAgentToolsSection(params: EditAgentContentListParams) {
    stickyHeader(key = SectionAnchors.TOOLS) {
        EditAgentSectionHeader(stringResource(R.string.screen_agent_edit_section_tools))
    }
    item(key = "tool_environment") {
        ToolEnvironmentSection(
            state = params.state,
            onAddAgentSecret = params.callbacks.onAddAgentSecret,
            onAgentSecretKeyChange = params.callbacks.onAgentSecretKeyChange,
            onAgentSecretValueChange = params.callbacks.onAgentSecretValueChange,
            onRemoveAgentSecret = params.callbacks.onRemoveAgentSecret,
            onAddToolEnvironmentVariable = params.callbacks.onAddToolEnvironmentVariable,
            onToolEnvironmentVariableKeyChange = params.callbacks.onToolEnvironmentVariableKeyChange,
            onToolEnvironmentVariableValueChange = params.callbacks.onToolEnvironmentVariableValueChange,
            onRemoveToolEnvironmentVariable = params.callbacks.onRemoveToolEnvironmentVariable,
        )
    }
    item(key = "tools") {
        EditAgentAttachedToolsCard(params)
    }
    item(key = "tool_rules") {
        ToolRulesSection(
            state = params.state,
            onToolRulesJsonChange = params.callbacks.onToolRulesJsonChange,
        )
    }
}

@Composable
private fun EditAgentAttachedToolsCard(params: EditAgentContentListParams) {
    val state = params.state
    CardGroup(
        title = {
            Text(stringResource(R.string.common_tools) + " (${state.attachedTools.size})")
        },
    ) {
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
                    onClick = { params.dialogState.selectedTool.value = tool },
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
            onClick = { params.dialogState.showToolPicker.value = true },
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_attach_tools)) },
            leadingContent = {
                Icon(
                    LettaIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(LettaIconSizing.Toolbar),
                )
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.editAgentRuntimeAndDangerSection(params: EditAgentContentListParams) {
    stickyHeader(key = SectionAnchors.RUNTIME) {
        EditAgentSectionHeader(stringResource(R.string.screen_agent_edit_section_runtime))
    }
    stickyHeader(key = SectionAnchors.DANGER) {
        EditAgentSectionHeader(
            title = stringResource(R.string.screen_create_project_danger_zone_title),
            isDanger = true,
        )
    }
    item(key = "danger_zone") {
        DangerZoneSection(
            onResetMessages = params.callbacks.onResetMessages,
            onDeleteAgent = params.callbacks.onDeleteAgent,
        )
    }
}
