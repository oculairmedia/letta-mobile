package com.letta.mobile.ui.screens.editagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.math.roundToInt

internal data class CompactionModeOption(
    val value: String,
    val labelRes: Int,
)

internal val compactionModeOptions = listOf(
    CompactionModeOption("sliding_window", R.string.screen_agent_edit_compaction_mode_sliding_window),
    CompactionModeOption("all", R.string.screen_agent_edit_compaction_mode_all),
    CompactionModeOption("self_compact_sliding_window", R.string.screen_agent_edit_compaction_mode_self_sliding_window),
    CompactionModeOption("self_compact_all", R.string.screen_agent_edit_compaction_mode_self_all),
)

internal data class AdvancedModelOption(
    val value: String,
    val labelRes: Int,
)

internal val reasoningEffortOptions = listOf(
    AdvancedModelOption("", R.string.screen_agent_edit_model_option_default),
    AdvancedModelOption("none", R.string.screen_agent_edit_reasoning_effort_none),
    AdvancedModelOption("minimal", R.string.screen_agent_edit_reasoning_effort_minimal),
    AdvancedModelOption("low", R.string.screen_agent_edit_model_option_low),
    AdvancedModelOption("medium", R.string.screen_agent_edit_model_option_medium),
    AdvancedModelOption("high", R.string.screen_agent_edit_model_option_high),
    AdvancedModelOption("xhigh", R.string.screen_agent_edit_reasoning_effort_xhigh),
)

internal val verbosityOptions = listOf(
    AdvancedModelOption("", R.string.screen_agent_edit_model_option_default),
    AdvancedModelOption("low", R.string.screen_agent_edit_model_option_low),
    AdvancedModelOption("medium", R.string.screen_agent_edit_model_option_medium),
    AdvancedModelOption("high", R.string.screen_agent_edit_model_option_high),
)

internal val anthropicEffortOptions = listOf(
    AdvancedModelOption("", R.string.screen_agent_edit_model_option_default),
    AdvancedModelOption("low", R.string.screen_agent_edit_model_option_low),
    AdvancedModelOption("medium", R.string.screen_agent_edit_model_option_medium),
    AdvancedModelOption("high", R.string.screen_agent_edit_model_option_high),
    AdvancedModelOption("max", R.string.screen_agent_edit_anthropic_effort_max),
)

internal fun isInvalidJsonObjectIfPresent(value: String): Boolean {
    if (value.isBlank()) return false
    return !runCatching { Json.parseToJsonElement(value) is JsonObject }.getOrDefault(false)
}

internal fun isInvalidJsonArrayIfPresent(value: String): Boolean {
    if (value.isBlank()) return false
    return !runCatching { Json.parseToJsonElement(value) is JsonArray }.getOrDefault(false)
}

internal fun isInvalidWholeNumberIfPresent(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return false
    return trimmed.toIntOrNull()?.takeIf { it >= 0 } == null
}

internal fun isInvalidNumberIfPresent(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return false
    return trimmed.toDoubleOrNull() == null
}

internal fun List<EditableAgentEnvironmentVariable>.hasDuplicateKeys(): Boolean {
    val keys = map { it.key.trim() }.filter { it.isNotBlank() }
    return keys.distinct().size != keys.size
}

@Composable
internal fun ToolRulesSection(
    state: EditAgentUiState,
    onToolRulesJsonChange: (String) -> Unit,
) {
    CardGroup(title = { Text(stringResource(R.string.screen_agent_edit_tool_rules_section)) }) {
        item(
            headlineContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.screen_agent_edit_tool_rules_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.attachedTools.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.screen_agent_edit_tool_rules_attached_tools,
                                state.attachedTools.joinToString { it.name },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = state.toolRulesJson,
                        onValueChange = onToolRulesJsonChange,
                        label = { Text(stringResource(R.string.screen_agent_edit_tool_rules_json)) },
                        placeholder = { Text(stringResource(R.string.screen_agent_edit_tool_rules_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
        )
    }
}

@Composable
internal fun ToolEnvironmentSection(
    state: EditAgentUiState,
    onAddAgentSecret: () -> Unit,
    onAgentSecretKeyChange: (Int, String) -> Unit,
    onAgentSecretValueChange: (Int, String) -> Unit,
    onRemoveAgentSecret: (Int) -> Unit,
    onAddToolEnvironmentVariable: () -> Unit,
    onToolEnvironmentVariableKeyChange: (Int, String) -> Unit,
    onToolEnvironmentVariableValueChange: (Int, String) -> Unit,
    onRemoveToolEnvironmentVariable: (Int) -> Unit,
) {
    CardGroup(title = { Text(stringResource(R.string.screen_agent_edit_tool_environment)) }) {
        item(
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_secrets)) },
            supportingContent = { Text(stringResource(R.string.screen_agent_edit_secrets_helper)) },
        )
        state.agentSecrets.forEachIndexed { index, variable ->
            item(
                headlineContent = {
                    EnvironmentVariableEditorRow(
                        variable = variable,
                        valueLabel = stringResource(R.string.screen_agent_edit_secret_value),
                        maskValue = true,
                        onKeyChange = { onAgentSecretKeyChange(index, it) },
                        onValueChange = { onAgentSecretValueChange(index, it) },
                        onRemove = { onRemoveAgentSecret(index) },
                    )
                },
            )
        }
        item(
            onClick = onAddAgentSecret,
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_add_secret)) },
            leadingContent = {
                Icon(
                    LettaIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(LettaIconSizing.Toolbar),
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_tool_environment_variables)) },
            supportingContent = { Text(stringResource(R.string.screen_agent_edit_tool_environment_variables_helper)) },
        )
        state.toolEnvironmentVariables.forEachIndexed { index, variable ->
            item(
                headlineContent = {
                    EnvironmentVariableEditorRow(
                        variable = variable,
                        valueLabel = stringResource(R.string.screen_agent_edit_environment_value),
                        maskValue = false,
                        onKeyChange = { onToolEnvironmentVariableKeyChange(index, it) },
                        onValueChange = { onToolEnvironmentVariableValueChange(index, it) },
                        onRemove = { onRemoveToolEnvironmentVariable(index) },
                    )
                },
            )
        }
        item(
            onClick = onAddToolEnvironmentVariable,
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_add_tool_environment_variable)) },
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

@Composable
private fun EnvironmentVariableEditorRow(
    variable: EditableAgentEnvironmentVariable,
    valueLabel: String,
    maskValue: Boolean,
    onKeyChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val hasHiddenStoredValue = variable.hasStoredValue && variable.originalValue == null && variable.value.isBlank()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = variable.key.ifBlank { stringResource(R.string.screen_agent_edit_environment_variable) },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasHiddenStoredValue) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.screen_agent_edit_value_stored)) },
                    enabled = false,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    LettaIcons.Close,
                    contentDescription = stringResource(R.string.action_remove),
                )
            }
        }
        OutlinedTextField(
            value = variable.key,
            onValueChange = onKeyChange,
            label = { Text(stringResource(R.string.screen_agent_edit_environment_key)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = variable.value,
            onValueChange = onValueChange,
            label = { Text(valueLabel) },
            placeholder = {
                if (hasHiddenStoredValue) {
                    Text(stringResource(R.string.screen_agent_edit_environment_value_hidden_placeholder))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (maskValue) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

@Composable
internal fun PrimaryModelAdvancedSection(
    state: EditAgentUiState,
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
) {
    CardGroup(title = { Text(stringResource(R.string.screen_agent_edit_primary_model_advanced)) }) {
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelProviderName,
                    onValueChange = onModelProviderNameChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_model_provider_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelProviderCategory,
                    onValueChange = onModelProviderCategoryChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_model_provider_category)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_enable_reasoner)) },
            trailingContent = {
                Switch(
                    checked = state.modelEnableReasoner,
                    onCheckedChange = onModelEnableReasonerChange,
                )
            },
        )
        item(
            headlineContent = {
                AdvancedModelDropdown(
                    label = stringResource(R.string.screen_agent_edit_reasoning_effort),
                    selectedValue = state.modelReasoningEffort,
                    options = reasoningEffortOptions,
                    onValueChange = onModelReasoningEffortChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelMaxReasoningTokens,
                    onValueChange = onModelMaxReasoningTokensChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_max_reasoning_tokens)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelReasoningJson,
                    onValueChange = onModelReasoningJsonChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_reasoning_json)) },
                    placeholder = { Text(stringResource(R.string.screen_agent_edit_json_object_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelFrequencyPenalty,
                    onValueChange = onModelFrequencyPenaltyChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_frequency_penalty)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
        )
        item(
            headlineContent = {
                AdvancedModelDropdown(
                    label = stringResource(R.string.screen_agent_edit_verbosity),
                    selectedValue = state.modelVerbosity,
                    options = verbosityOptions,
                    onValueChange = onModelVerbosityChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_strict_tool_calling)) },
            trailingContent = {
                Switch(
                    checked = state.modelStrictToolCalling,
                    onCheckedChange = onModelStrictToolCallingChange,
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelResponseFormatJson,
                    onValueChange = onModelResponseFormatJsonChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_response_format_json)) },
                    placeholder = { Text(stringResource(R.string.screen_agent_edit_json_object_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelResponseSchemaJson,
                    onValueChange = onModelResponseSchemaJsonChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_response_schema_json)) },
                    placeholder = { Text(stringResource(R.string.screen_agent_edit_json_object_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelThinkingConfigJson,
                    onValueChange = onModelThinkingConfigJsonChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_thinking_config_json)) },
                    placeholder = { Text(stringResource(R.string.screen_agent_edit_json_object_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_put_inner_thoughts_in_kwargs)) },
            trailingContent = {
                Switch(
                    checked = state.modelPutInnerThoughtsInKwargs,
                    onCheckedChange = onModelPutInnerThoughtsInKwargsChange,
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelToolCallParser,
                    onValueChange = onModelToolCallParserChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_tool_call_parser)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
        )
        item(
            headlineContent = {
                AdvancedModelDropdown(
                    label = stringResource(R.string.screen_agent_edit_anthropic_effort),
                    selectedValue = state.modelAnthropicEffort,
                    options = anthropicEffortOptions,
                    onValueChange = onModelAnthropicEffortChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedModelDropdown(
    label: String,
    selectedValue: String,
    options: List<AdvancedModelOption>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull { it.value == selectedValue } ?: options.first()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = stringResource(selectedOption.labelRes),
            onValueChange = {},
            label = { Text(label) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        onValueChange(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun AdvancedCompactionSection(
    state: EditAgentUiState,
    onSummarizationPromptChange: (String) -> Unit,
    onCompactionClipCharsChange: (Int) -> Unit,
    onSlidingWindowPercentageChange: (Float) -> Unit,
    onPromptAcknowledgementChange: (Boolean) -> Unit,
    onCompactionModeChange: (String) -> Unit,
    onCompactionModelChange: (String) -> Unit,
    onCompactionModelSettingsJsonChange: (String) -> Unit,
    compactionModelTitle: String,
    compactionModelSupporting: String,
    onOpenCompactionModelPicker: () -> Unit,
) {
    CardGroup(title = { Text(stringResource(R.string.screen_agent_edit_advanced_configuration)) }) {
        item(
            headlineContent = {
                CompactionModeDropdown(
                    selectedMode = state.compactionMode,
                    onModeChange = onCompactionModeChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        item(
            headlineContent = {
                SearchPickerField(
                    label = stringResource(R.string.screen_agent_edit_compaction_model),
                    title = compactionModelTitle,
                    supporting = compactionModelSupporting,
                    onClick = onOpenCompactionModelPicker,
                )
            },
        )
        if (state.compactionModel.isNotBlank()) {
            item(
                onClick = { onCompactionModelChange("") },
                headlineContent = { Text(stringResource(R.string.screen_agent_edit_compaction_model_use_default)) },
                leadingContent = {
                    Icon(
                        LettaIcons.Close,
                        contentDescription = null,
                        modifier = Modifier.size(LettaIconSizing.Toolbar),
                    )
                },
            )
        }
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.compactionModelSettingsJson,
                    onValueChange = onCompactionModelSettingsJsonChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_compaction_model_settings)) },
                    placeholder = { Text(stringResource(R.string.screen_agent_edit_compaction_model_settings_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.summarizationPrompt,
                    onValueChange = onSummarizationPromptChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_summarization_prompt)) },
                    placeholder = { Text(stringResource(R.string.screen_agent_edit_summarization_prompt_default)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.compactionClipChars.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let(onCompactionClipCharsChange)
                    },
                    label = { Text(stringResource(R.string.screen_agent_edit_clip_chars)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
        )
        item(
            headlineContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(
                            R.string.screen_agent_edit_sliding_window_percentage,
                            formatCompactionPercentage(state.slidingWindowPercentage),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Slider(
                            value = state.slidingWindowPercentage.coerceIn(0f, 1f),
                            onValueChange = onSlidingWindowPercentageChange,
                            valueRange = 0f..1f,
                            steps = 19,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = formatCompactionPercentage(state.slidingWindowPercentage),
                            onValueChange = { value ->
                                value.toFloatOrNull()?.let(onSlidingWindowPercentageChange)
                            },
                            modifier = Modifier.width(92.dp),
                            singleLine = true,
                        )
                    }
                }
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_prompt_acknowledgement)) },
            trailingContent = {
                Switch(
                    checked = state.promptAcknowledgement,
                    onCheckedChange = onPromptAcknowledgementChange,
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactionModeDropdown(
    selectedMode: String,
    onModeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = compactionModeOptions.firstOrNull { it.value == selectedMode }
        ?: compactionModeOptions.first()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = stringResource(selectedOption.labelRes),
            onValueChange = {},
            label = { Text(stringResource(R.string.screen_agent_edit_compaction_mode)) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            compactionModeOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        onModeChange(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun ContextWindowLimitSlider(
    value: Int,
    maxValue: Int?,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.common_context_window),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (maxValue != null && maxValue > 0) {
            val coercedValue = value.coerceIn(0, maxValue)
            val percentage = ((coercedValue.toFloat() / maxValue.toFloat()) * 100f).roundToInt()
            Text(
                text = stringResource(
                    R.string.screen_chat_context_window_usage,
                    formatEditAgentNumber(coercedValue),
                    formatEditAgentNumber(maxValue),
                    percentage,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = coercedValue.toFloat(),
                onValueChange = { sliderValue ->
                    onValueChange(snapContextWindowValue(sliderValue, maxValue))
                },
                valueRange = 0f..maxValue.toFloat(),
            )
        } else {
            Text(
                text = if (value > 0) {
                    stringResource(R.string.screen_agent_edit_context_window, value)
                } else {
                    stringResource(R.string.screen_agent_edit_context_window_unavailable)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun formatCompactionPercentage(value: Float): String {
    return String.format(Locale.US, "%.2f", value.coerceIn(0f, 1f))
        .trimEnd('0')
        .trimEnd('.')
}

internal fun snapContextWindowValue(value: Float, maxValue: Int): Int {
    if (maxValue <= 1_000) return value.roundToInt().coerceIn(0, maxValue)
    return (value / 1_000f).roundToInt()
        .times(1_000)
        .coerceIn(0, maxValue)
}

internal fun formatEditAgentNumber(value: Int): String =
    String.format(Locale.getDefault(), "%,d", value)
