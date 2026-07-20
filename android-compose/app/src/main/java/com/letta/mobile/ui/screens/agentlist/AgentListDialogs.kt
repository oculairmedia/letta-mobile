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
import androidx.compose.ui.res.pluralStringResource
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

internal data class CreateAgentFormState(
    val name: String = "",
    val description: String = "",
    val model: String = "",
    val embedding: String = "",
    val providerType: String = "",
    val systemPrompt: String = "",
    val temperature: String = "1.0",
    val maxOutputTokens: String = "4096",
    val parallelToolCalls: Boolean = true,
    val enableSleeptime: Boolean = false,
    val includeBaseTools: Boolean = true,
    val selectedToolIds: List<String> = emptyList(),
    val runtimeOption: AgentCreateRuntimeOption = AgentCreateRuntimeOption.REMOTE,
    val showToolPicker: Boolean = false,
)

internal data class CreateAgentDialogInputs(
    val availableTools: List<Tool> = emptyList(),
    val llmModels: List<LlmModel> = emptyList(),
    val embeddingModels: List<EmbeddingModel> = emptyList(),
    val onLoadModels: () -> Unit = {},
    val localReadiness: LocalLettaCodeCreateReadiness = LocalLettaCodeCreateReadiness(),
    val onOpenLocalSettings: () -> Unit = {},
)

internal data class CreateAgentDialogResources(
    val localReadiness: LocalLettaCodeCreateReadiness,
    val llmModels: List<LlmModel>,
    val embeddingDropdownModels: List<LlmModel>,
    val onOpenLocalSettings: () -> Unit,
    val onLoadModels: () -> Unit,
    val validation: CreateAgentValidation,
)


private fun initialCreateAgentFormState(
    localReadiness: LocalLettaCodeCreateReadiness,
): CreateAgentFormState {
    // letta-mobile-vc680: under a local config the dialog used to default to
    // REMOTE, presenting a remote model picker that can never list on-device
    // models — users concluded their downloaded model was missing.
    val defaultRuntime = if (localReadiness.activeConfigIsLocal) {
        AgentCreateRuntimeOption.LOCAL_LETTACODE
    } else {
        AgentCreateRuntimeOption.REMOTE
    }
    return CreateAgentFormState(runtimeOption = defaultRuntime)
}

@Composable
private fun rememberCreateAgentDialogResources(
    formState: CreateAgentFormState,
    inputs: CreateAgentDialogInputs,
): CreateAgentDialogResources {
    val validation = remember(formState.name, formState.runtimeOption, inputs.localReadiness) {
        validateCreateAgentForm(
            name = formState.name,
            runtimeOption = formState.runtimeOption,
            localReadiness = inputs.localReadiness,
        )
    }
    val embeddingDropdownModels = remember(inputs.embeddingModels) {
        inputs.embeddingModels.map {
            LlmModel(
                id = it.id,
                name = it.name,
                handle = it.handle,
                providerType = it.providerType,
            )
        }
    }
    return CreateAgentDialogResources(
        localReadiness = inputs.localReadiness,
        llmModels = inputs.llmModels,
        embeddingDropdownModels = embeddingDropdownModels,
        onOpenLocalSettings = inputs.onOpenLocalSettings,
        onLoadModels = inputs.onLoadModels,
        validation = validation,
    )
}

@Composable
internal fun CreateAgentDialog(
    onDismiss: () -> Unit,
    inputs: CreateAgentDialogInputs = CreateAgentDialogInputs(),
    onCreate: (AgentCreateParams, AgentCreateRuntimeOption) -> Unit,
) {
    var formState by remember {
        mutableStateOf(initialCreateAgentFormState(inputs.localReadiness))
    }
    val resources = rememberCreateAgentDialogResources(formState, inputs)

    MultiFieldInputDialog(
        show = true,
        title = stringResource(R.string.screen_agents_dialog_create_title),
        confirmText = stringResource(R.string.action_create),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = resources.validation.enabled,
        onConfirm = {
            onCreate(
                buildAgentCreateParams(formState),
                formState.runtimeOption,
            )
        },
    ) {
        CreateAgentDialogForm(
            formState = formState,
            onFormStateChange = { formState = it },
            resources = resources,
        )
    }

    if (formState.showToolPicker) {
        ToolPickerDialog(
            tools = inputs.availableTools,
            selectedToolIds = formState.selectedToolIds,
            title = stringResource(R.string.screen_agents_create_select_tools),
            onDismiss = { formState = formState.copy(showToolPicker = false) },
            onConfirm = { selectedIds ->
                formState = formState.copy(
                    selectedToolIds = selectedIds,
                    showToolPicker = false,
                )
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

internal fun buildAgentCreateParams(formState: CreateAgentFormState): AgentCreateParams {
    val isLocal = formState.runtimeOption == AgentCreateRuntimeOption.LOCAL_LETTACODE
    return AgentCreateParams(
        name = formState.name,
        description = formState.description.ifBlank { null },
        model = formState.model.ifBlank { null },
        embedding = formState.embedding.ifBlank { null },
        modelSettings = ModelSettings(
            providerType = formState.providerType.ifBlank { null },
            temperature = formState.temperature.toDoubleOrNull(),
            maxOutputTokens = formState.maxOutputTokens.toIntOrNull(),
            parallelToolCalls = formState.parallelToolCalls,
        ),
        toolIds = if (isLocal) {
            null
        } else {
            formState.selectedToolIds.map { ToolId(it) }.ifEmpty { null }
        },
        system = formState.systemPrompt.ifBlank { null },
        enableSleeptime = if (isLocal) false else formState.enableSleeptime,
        includeBaseTools = if (isLocal) false else formState.includeBaseTools,
        parallelToolCalls = if (isLocal) false else null,
    )
}

@Composable
private fun CreateAgentDialogForm(
    formState: CreateAgentFormState,
    onFormStateChange: (CreateAgentFormState) -> Unit,
    resources: CreateAgentDialogResources,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = formState.name,
            onValueChange = { onFormStateChange(formState.copy(name = it)) },
            label = { Text(stringResource(R.string.common_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = formState.description,
            onValueChange = { onFormStateChange(formState.copy(description = it)) },
            label = { Text(stringResource(R.string.common_description)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        CreateAgentRuntimeSection(
            formState = formState,
            onFormStateChange = onFormStateChange,
            resources = resources,
        )
        if (formState.runtimeOption != AgentCreateRuntimeOption.LOCAL_LETTACODE) {
            CreateAgentRemoteAdvancedSection(
                formState = formState,
                onFormStateChange = onFormStateChange,
            )
        }
        OutlinedTextField(
            value = formState.systemPrompt,
            onValueChange = { onFormStateChange(formState.copy(systemPrompt = it)) },
            label = { Text(stringResource(R.string.common_system_prompt)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 5,
        )
        if (formState.runtimeOption == AgentCreateRuntimeOption.LOCAL_LETTACODE) {
            Text(
                text = "Text only · tools off · approvals off",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CreateAgentRemoteToolsSection(
                formState = formState,
                onFormStateChange = onFormStateChange,
            )
        }
        resources.validation.disabledReason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CreateAgentRuntimeSection(
    formState: CreateAgentFormState,
    onFormStateChange: (CreateAgentFormState) -> Unit,
    resources: CreateAgentDialogResources,
) {
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
                checked = formState.runtimeOption == AgentCreateRuntimeOption.REMOTE,
                onCheckedChange = {
                    if (it) onFormStateChange(formState.copy(runtimeOption = AgentCreateRuntimeOption.REMOTE))
                },
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
                checked = formState.runtimeOption == AgentCreateRuntimeOption.LOCAL_LETTACODE,
                onCheckedChange = {
                    if (it) onFormStateChange(formState.copy(runtimeOption = AgentCreateRuntimeOption.LOCAL_LETTACODE))
                },
            )
        },
    )
    if (formState.runtimeOption == AgentCreateRuntimeOption.LOCAL_LETTACODE) {
        LocalLettaCodeReadinessCard(
            readiness = resources.localReadiness,
            onOpenLocalSettings = resources.onOpenLocalSettings,
        )
        if (resources.localReadiness.ready) {
            // Local-mode model options: the custom endpoint's models
            // and downloaded on-device models, via repository routing
            // (letta-mobile-3icw7). Blank = config/seed default.
            ModelDropdown(
                selectedModel = formState.model,
                models = resources.llmModels,
                onModelSelected = { onFormStateChange(formState.copy(model = it)) },
                onLoadModels = resources.onLoadModels,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.common_model),
            )
        }
    } else {
        ModelDropdown(
            selectedModel = formState.model,
            models = resources.llmModels,
            onModelSelected = { onFormStateChange(formState.copy(model = it)) },
            onLoadModels = resources.onLoadModels,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.common_model),
        )
        ModelDropdown(
            selectedModel = formState.embedding,
            models = resources.embeddingDropdownModels,
            onModelSelected = { onFormStateChange(formState.copy(embedding = it)) },
            onLoadModels = resources.onLoadModels,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.screen_agent_edit_embedding_model),
        )
        Text(
            text = remoteCreateAgentModelHelp(model = formState.model, embedding = formState.embedding),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CreateAgentRemoteAdvancedSection(
    formState: CreateAgentFormState,
    onFormStateChange: (CreateAgentFormState) -> Unit,
) {
    Text(
        text = stringResource(R.string.screen_agents_create_advanced_model_section),
        style = MaterialTheme.typography.titleSmall,
    )
    OutlinedTextField(
        value = formState.providerType,
        onValueChange = { onFormStateChange(formState.copy(providerType = it)) },
        label = { Text(stringResource(R.string.common_provider)) },
        placeholder = { Text(stringResource(R.string.screen_agents_create_provider_placeholder)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = formState.temperature,
        onValueChange = { value ->
            if (value.isBlank() || value.toDoubleOrNull() != null) {
                onFormStateChange(formState.copy(temperature = value))
            }
        },
        label = {
            Text(
                stringResource(
                    R.string.screen_agent_edit_temperature_value,
                    formState.temperature.toFloatOrNull() ?: 0f,
                ),
            )
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = formState.maxOutputTokens,
        onValueChange = { value ->
            if (value.isBlank() || value.toIntOrNull() != null) {
                onFormStateChange(formState.copy(maxOutputTokens = value))
            }
        },
        label = { Text(stringResource(R.string.common_max_output_tokens)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    FormItem(
        label = { Text(stringResource(R.string.common_parallel_tool_calls)) },
        tail = {
            Switch(
                checked = formState.parallelToolCalls,
                onCheckedChange = { onFormStateChange(formState.copy(parallelToolCalls = it)) },
            )
        },
    )
}

@Composable
private fun CreateAgentRemoteToolsSection(
    formState: CreateAgentFormState,
    onFormStateChange: (CreateAgentFormState) -> Unit,
) {
    FormItem(
        label = { Text(stringResource(R.string.common_enable_sleeptime)) },
        tail = {
            Switch(
                checked = formState.enableSleeptime,
                onCheckedChange = { onFormStateChange(formState.copy(enableSleeptime = it)) },
            )
        },
    )
    FormItem(
        label = { Text(stringResource(R.string.screen_agents_create_include_base_tools)) },
        tail = {
            Switch(
                checked = formState.includeBaseTools,
                onCheckedChange = { onFormStateChange(formState.copy(includeBaseTools = it)) },
            )
        },
    )
    Text(
        text = stringResource(R.string.common_tools),
        style = MaterialTheme.typography.titleSmall,
    )
    if (formState.selectedToolIds.isEmpty()) {
        Text(
            text = stringResource(R.string.screen_tools_empty_attached),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            text = pluralStringResource(
                R.plurals.screen_agents_create_selected_tools_count,
                formState.selectedToolIds.size,
                formState.selectedToolIds.size,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    OutlinedButton(
        onClick = { onFormStateChange(formState.copy(showToolPicker = true)) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(LettaIcons.Add, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.screen_agents_create_select_tools))
    }
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
