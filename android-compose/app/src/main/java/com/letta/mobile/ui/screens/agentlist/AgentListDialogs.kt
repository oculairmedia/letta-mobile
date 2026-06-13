package com.letta.mobile.ui.screens.agentlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelSettings
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.ModelDropdown
import com.letta.mobile.ui.components.MultiFieldInputDialog
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.screens.tools.ToolPickerDialog

@Composable
internal fun CreateAgentDialog(
    onDismiss: () -> Unit,
    availableTools: List<Tool> = emptyList(),
    llmModels: List<LlmModel> = emptyList(),
    embeddingModels: List<EmbeddingModel> = emptyList(),
    onLoadModels: () -> Unit = {},
    localReadiness: LocalLettaCodeCreateReadiness = LocalLettaCodeCreateReadiness(),
    onOpenLocalSettings: () -> Unit = {},
    onCreate: (AgentCreateParams, AgentCreateRuntimeOption) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var embedding by remember { mutableStateOf("") }
    var providerType by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf("1.0") }
    var maxOutputTokens by remember { mutableStateOf("4096") }
    var parallelToolCalls by remember { mutableStateOf(true) }
    var enableSleeptime by remember { mutableStateOf(false) }
    var includeBaseTools by remember { mutableStateOf(true) }
    var selectedToolIds by remember { mutableStateOf<List<String>>(emptyList()) }
    // letta-mobile-vc680: under a local config the dialog used to default to
    // REMOTE, presenting a remote model picker that can never list on-device
    // models — users concluded their downloaded model was missing.
    var runtimeOption by remember {
        mutableStateOf(
            if (localReadiness.activeConfigIsLocal) {
                AgentCreateRuntimeOption.LOCAL_LETTACODE
            } else {
                AgentCreateRuntimeOption.REMOTE
            }
        )
    }
    var showToolPicker by remember { mutableStateOf(false) }
    val validation = remember(name, runtimeOption, localReadiness) {
        validateCreateAgentForm(
            name = name,
            runtimeOption = runtimeOption,
            localReadiness = localReadiness,
        )
    }
    val embeddingDropdownModels = remember(embeddingModels) {
        embeddingModels.map {
            LlmModel(
                id = it.id,
                name = it.name,
                handle = it.handle,
                providerType = it.providerType,
            )
        }
    }

    MultiFieldInputDialog(
        show = true,
        title = stringResource(R.string.screen_agents_dialog_create_title),
        confirmText = stringResource(R.string.action_create),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = validation.enabled,
        onConfirm = {
            onCreate(AgentCreateParams(
                name = name,
                description = description.ifBlank { null },
                model = model.ifBlank { null },
                embedding = embedding.ifBlank { null },
                modelSettings = ModelSettings(
                    providerType = providerType.ifBlank { null },
                    temperature = temperature.toDoubleOrNull(),
                    maxOutputTokens = maxOutputTokens.toIntOrNull(),
                    parallelToolCalls = parallelToolCalls,
                ),
                toolIds = if (runtimeOption == AgentCreateRuntimeOption.LOCAL_LETTACODE) null else selectedToolIds.map { ToolId(it) }.ifEmpty { null },
                system = systemPrompt.ifBlank { null },
                enableSleeptime = if (runtimeOption == AgentCreateRuntimeOption.LOCAL_LETTACODE) false else enableSleeptime,
                includeBaseTools = if (runtimeOption == AgentCreateRuntimeOption.LOCAL_LETTACODE) false else includeBaseTools,
                parallelToolCalls = if (runtimeOption == AgentCreateRuntimeOption.LOCAL_LETTACODE) false else null,
            ), runtimeOption)
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.common_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.common_description)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Runtime",
                style = MaterialTheme.typography.titleSmall,
            )
            FormItem(
                label = {
                    Column {
                        Text("Remote Letta")
                        Text(
                            text = "Use the current server/shim connection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                tail = {
                    Switch(
                        checked = runtimeOption == AgentCreateRuntimeOption.REMOTE,
                        onCheckedChange = { if (it) runtimeOption = AgentCreateRuntimeOption.REMOTE },
                    )
                },
            )
            FormItem(
                label = {
                    Column {
                        Text("Local LettaCode")
                        Text(
                            text = "Text-only on-device chat. Tools and approvals are disabled for now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                tail = {
                    Switch(
                        checked = runtimeOption == AgentCreateRuntimeOption.LOCAL_LETTACODE,
                        onCheckedChange = { if (it) runtimeOption = AgentCreateRuntimeOption.LOCAL_LETTACODE },
                    )
                },
            )
            if (runtimeOption == AgentCreateRuntimeOption.LOCAL_LETTACODE) {
                LocalLettaCodeReadinessCard(
                    readiness = localReadiness,
                    onOpenLocalSettings = onOpenLocalSettings,
                )
                if (localReadiness.ready) {
                    // Local-mode model options: the custom endpoint's models
                    // and downloaded on-device models, via repository routing
                    // (letta-mobile-3icw7). Blank = config/seed default.
                    ModelDropdown(
                        selectedModel = model,
                        models = llmModels,
                        onModelSelected = { model = it },
                        onLoadModels = onLoadModels,
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.common_model),
                    )
                }
            } else {
                ModelDropdown(
                    selectedModel = model,
                    models = llmModels,
                    onModelSelected = { model = it },
                    onLoadModels = onLoadModels,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.common_model),
                )
                ModelDropdown(
                    selectedModel = embedding,
                    models = embeddingDropdownModels,
                    onModelSelected = { embedding = it },
                    onLoadModels = onLoadModels,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.screen_agent_edit_embedding_model),
                )
                Text(
                    text = remoteCreateAgentModelHelp(model = model, embedding = embedding),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (runtimeOption != AgentCreateRuntimeOption.LOCAL_LETTACODE) {
                Text(
                    text = stringResource(R.string.screen_agents_create_advanced_model_section),
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedTextField(
                    value = providerType,
                    onValueChange = { providerType = it },
                    label = { Text(stringResource(R.string.common_provider)) },
                    placeholder = { Text(stringResource(R.string.screen_agents_create_provider_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = temperature,
                    onValueChange = { value ->
                        if (value.isBlank() || value.toDoubleOrNull() != null) {
                            temperature = value
                        }
                    },
                    label = { Text(stringResource(R.string.screen_agent_edit_temperature_value, temperature.toFloatOrNull() ?: 0f)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = maxOutputTokens,
                    onValueChange = { value ->
                        if (value.isBlank() || value.toIntOrNull() != null) {
                            maxOutputTokens = value
                        }
                    },
                    label = { Text(stringResource(R.string.common_max_output_tokens)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                FormItem(
                    label = { Text(stringResource(R.string.common_parallel_tool_calls)) },
                    tail = {
                        Switch(checked = parallelToolCalls, onCheckedChange = { parallelToolCalls = it })
                    },
                )
            }
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text(stringResource(R.string.common_system_prompt)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5,
            )
            if (runtimeOption == AgentCreateRuntimeOption.LOCAL_LETTACODE) {
                Text(
                    text = "Text only · tools off · approvals off",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FormItem(
                    label = { Text(stringResource(R.string.common_enable_sleeptime)) },
                    tail = {
                        Switch(checked = enableSleeptime, onCheckedChange = { enableSleeptime = it })
                    },
                )
                FormItem(
                    label = { Text(stringResource(R.string.screen_agents_create_include_base_tools)) },
                    tail = {
                        Switch(checked = includeBaseTools, onCheckedChange = { includeBaseTools = it })
                    },
                )
                Text(
                    text = stringResource(R.string.common_tools),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (selectedToolIds.isEmpty()) {
                    Text(
                        text = stringResource(R.string.screen_tools_empty_attached),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.screen_agents_create_selected_tools_count, selectedToolIds.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedButton(
                    onClick = { showToolPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(LettaIcons.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.screen_agents_create_select_tools))
                }
            }
            validation.disabledReason?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showToolPicker) {
        ToolPickerDialog(
            tools = availableTools,
            selectedToolIds = selectedToolIds,
            title = stringResource(R.string.screen_agents_create_select_tools),
            onDismiss = { showToolPicker = false },
            onConfirm = { selectedIds ->
                selectedToolIds = selectedIds
                showToolPicker = false
            },
        )
    }
}

data class CreateAgentValidation(
    val enabled: Boolean,
    val disabledReason: String?,
)

fun validateCreateAgentForm(
    name: String,
    runtimeOption: AgentCreateRuntimeOption,
    localReadiness: LocalLettaCodeCreateReadiness,
): CreateAgentValidation {
    if (name.isBlank()) {
        return CreateAgentValidation(
            enabled = false,
            disabledReason = "Enter an agent name to enable Create.",
        )
    }
    if (runtimeOption == AgentCreateRuntimeOption.LOCAL_LETTACODE && !localReadiness.ready) {
        return CreateAgentValidation(
            enabled = false,
            disabledReason = localReadiness.setupMessage ?: "Finish Local LettaCode setup before creating a local agent.",
        )
    }
    return CreateAgentValidation(enabled = true, disabledReason = null)
}

fun remoteCreateAgentModelHelp(model: String, embedding: String): String = when {
    model.isBlank() && embedding.isBlank() -> "Model and embedding are optional; the server default will be used if left blank."
    model.isBlank() -> "Model is optional; the server default model will be used if left blank."
    embedding.isBlank() -> "Embedding is optional; the server default embedding will be used if left blank."
    else -> "Selected model and embedding will be sent with the create request."
}

@Composable
private fun LocalLettaCodeReadinessCard(
    readiness: LocalLettaCodeCreateReadiness,
    onOpenLocalSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (readiness.ready) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (readiness.ready) "Local LettaCode is ready" else "Finish Local LettaCode setup",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = readiness.setupMessage
                    ?: "Your selected on-device model will be used for this agent. Tools and approvals are off for now.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!readiness.ready) {
                OutlinedButton(onClick = onOpenLocalSettings) {
                    Text(readiness.setupActionLabel)
                }
            }
        }
    }
}

@Composable
internal fun ImportAgentDialog(
    isImporting: Boolean,
    onDismiss: () -> Unit,
    onImport: (overrideName: String?, overrideExistingTools: Boolean, stripMessages: Boolean) -> Unit,
) {
    var overrideName by remember { mutableStateOf("") }
    var overrideExistingTools by remember { mutableStateOf(true) }
    var stripMessages by remember { mutableStateOf(false) }

    MultiFieldInputDialog(
        show = true,
        title = stringResource(R.string.screen_agents_import_title),
        confirmText = stringResource(R.string.action_choose_file),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = !isImporting,
        onConfirm = {
            onImport(overrideName.ifBlank { null }, overrideExistingTools, stripMessages)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.screen_agents_import_helper),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = overrideName,
                onValueChange = { overrideName = it },
                label = { Text(stringResource(R.string.screen_agents_import_override_name)) },
                placeholder = { Text(stringResource(R.string.screen_agents_import_override_name_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            FormItem(
                label = { Text(stringResource(R.string.screen_agents_import_override_tools_title)) },
                description = {
                    Text(stringResource(R.string.screen_agents_import_override_tools_helper))
                },
                tail = {
                    Switch(checked = overrideExistingTools, onCheckedChange = { overrideExistingTools = it })
                },
            )
            FormItem(
                label = { Text(stringResource(R.string.screen_agents_import_strip_messages_title)) },
                description = {
                    Text(stringResource(R.string.screen_agents_import_strip_messages_helper))
                },
                tail = {
                    Switch(checked = stripMessages, onCheckedChange = { stripMessages = it })
                },
            )
        }
    }
}
