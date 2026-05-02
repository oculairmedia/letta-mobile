package com.letta.mobile.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.components.TagDrillInDialog
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.tags.TagDrillInEntityType
import com.letta.mobile.ui.tags.TagDrillInSource
import com.letta.mobile.ui.tags.TagDrillInViewModel
import com.letta.mobile.ui.theme.listItemMetadata
import com.letta.mobile.ui.theme.listItemSupporting
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tagDrillInViewModel: TagDrillInViewModel = hiltViewModel()
    val tagDrillInState by tagDrillInViewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current
    val context = LocalContext.current

    when (val state = uiState) {
        is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
        is UiState.Error -> ErrorContent(
            message = state.message,
            onRetry = { viewModel.loadSettings() }
        )
        is UiState.Success -> SettingsContent(
            state = state.data,
            onTemperatureChange = { viewModel.updateTemperature(it) },
            onMaxTokensChange = { viewModel.updateMaxTokens(it) },
            onParallelToolCallsChange = { viewModel.updateParallelToolCalls(it) },
            onPersonaChange = { viewModel.updatePersonaBlock(it) },
            onHumanChange = { viewModel.updateHumanBlock(it) },
            onSleeptimeChange = { viewModel.updateSleeptime(it) },
            onSystemPromptChange = { viewModel.updateSystemPrompt(it) },
            onClientModeEnabledChange = { viewModel.updateClientModeEnabled(it) },
            onClientModeBaseUrlChange = { viewModel.updateClientModeBaseUrl(it) },
            onClientModeApiKeyChange = { viewModel.updateClientModeApiKey(it) },
            onTestClientModeConnection = { viewModel.testClientModeConnection() },
            onSave = { viewModel.saveSettings { snackbar.dispatch(context.getString(R.string.screen_settings_saved)) } },
            onExport = {
                viewModel.exportAgent { exportData ->
                    val exported = shareAgentExport(context, exportData)
                    snackbar.dispatch(
                        context.getString(
                            if (exported) R.string.screen_settings_export_ready else R.string.screen_settings_export_unavailable
                        )
                    )
                }
            },
            onClone = { cloneName, overrideExistingTools, stripMessages ->
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
            onResetMessages = {
                viewModel.resetMessages {
                    snackbar.dispatch(context.getString(R.string.screen_settings_messages_reset))
                }
            },
            onDelete = { viewModel.deleteAgent(onNavigateBack) },
            onTagClick = { tag, source -> tagDrillInViewModel.showTag(tag, source) },
            modifier = modifier
        )
    }

    TagDrillInDialog(
        state = tagDrillInState,
        onDismiss = tagDrillInViewModel::dismiss,
    )
}

@Composable
private fun SettingsContent(
    state: AgentSettingsUiState,
    onTemperatureChange: (Float) -> Unit,
    onMaxTokensChange: (Int) -> Unit,
    onParallelToolCallsChange: (Boolean) -> Unit,
    onPersonaChange: (String) -> Unit,
    onHumanChange: (String) -> Unit,
    onSleeptimeChange: (Boolean) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onClientModeEnabledChange: (Boolean) -> Unit,
    onClientModeBaseUrlChange: (String) -> Unit,
    onClientModeApiKeyChange: (String) -> Unit,
    onTestClientModeConnection: () -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onClone: (cloneName: String?, overrideExistingTools: Boolean, stripMessages: Boolean) -> Unit,
    onResetMessages: () -> Unit,
    onDelete: () -> Unit,
    onTagClick: (String, TagDrillInSource) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showCloneDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.agentType.isNotBlank()) {
            CardGroup(title = { Text(stringResource(R.string.screen_settings_operational_section)) }) {
                item(
                    headlineContent = { Text(stringResource(R.string.common_type)) },
                    trailingContent = {
                        Text(
                            text = state.agentType,
                            style = MaterialTheme.typography.listItemMetadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        CardGroup(title = { Text(stringResource(R.string.screen_settings_model_section)) }) {
            item(
                headlineContent = { Text(stringResource(R.string.common_model)) },
                supportingContent = {
                    Text(state.agent?.model ?: stringResource(R.string.screen_settings_no_model))
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.common_context_window)) },
                trailingContent = {
                        Text(
                            text = state.contextWindow.toString(),
                            style = MaterialTheme.typography.listItemMetadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                },
            )
        }

        ClientModeSettingsSection(
            state = state,
            onClientModeEnabledChange = onClientModeEnabledChange,
            onClientModeBaseUrlChange = onClientModeBaseUrlChange,
            onClientModeApiKeyChange = onClientModeApiKeyChange,
            onTestClientModeConnection = onTestClientModeConnection,
        )

        CardGroup(title = { Text(stringResource(R.string.screen_settings_temperature_value, state.temperature)) }) {
            item(
                headlineContent = {
                    Slider(
                        value = state.temperature,
                        onValueChange = onTemperatureChange,
                        valueRange = 0f..2f,
                        steps = 19,
                    )
                },
            )
            item(
                headlineContent = {
                    OutlinedTextField(
                        value = state.maxTokens.toString(),
                        onValueChange = { text ->
                            text.toIntOrNull()?.let { value -> onMaxTokensChange(value) }
                        },
                        label = { Text(stringResource(R.string.common_max_output_tokens)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.common_parallel_tool_calls)) },
                trailingContent = {
                    Switch(
                        checked = state.parallelToolCalls,
                        onCheckedChange = onParallelToolCallsChange,
                    )
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.common_enable_sleeptime)) },
                trailingContent = {
                    Switch(
                        checked = state.enableSleeptime,
                        onCheckedChange = onSleeptimeChange,
                    )
                },
            )
        }

        CardGroup(title = { Text(stringResource(R.string.screen_agent_memory_blocks_section)) }) {
            item(
                headlineContent = {
                    OutlinedTextField(
                        value = state.personaBlock,
                        onValueChange = onPersonaChange,
                        label = { Text(stringResource(R.string.common_persona)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                },
            )
            item(
                headlineContent = {
                    OutlinedTextField(
                        value = state.humanBlock,
                        onValueChange = onHumanChange,
                        label = { Text(stringResource(R.string.common_human)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                },
            )
        }

        CardGroup(title = { Text(stringResource(R.string.common_system_prompt)) }) {
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

        if (state.tools.isNotEmpty()) {
            var selectedTool by remember { mutableStateOf<com.letta.mobile.data.model.Tool?>(null) }

            CardGroup(title = { Text(stringResource(R.string.common_tools) + " (${state.tools.size})") }) {
                state.tools.forEach { tool ->
                    item(
                        onClick = { selectedTool = tool },
                        headlineContent = { Text(tool.name) },
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
                            Icon(LettaIcons.Tool, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                    )
                }
            }

            selectedTool?.let { tool ->
                ToolDetailDialog(
                    tool = tool,
                    onDismiss = { selectedTool = null },
                    onTagClick = { tag ->
                        onTagClick(tag, TagDrillInSource(TagDrillInEntityType.TOOL, tool.id))
                    },
                )
            }
        }

        val agent = state.agent
        if (!agent?.tags.isNullOrEmpty()) {
            CardGroup(title = { Text(stringResource(R.string.common_tags)) }) {
                item(
                    headlineContent = {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            agent.tags.forEach { tag ->
                                AssistChip(
                                    onClick = {
                                        onTagClick(
                                            tag,
                                            TagDrillInSource(TagDrillInEntityType.AGENT, agent.id),
                                        )
                                    },
                                    label = { Text(tag) },
                                )
                            }
                        }
                    },
                )
            }
        }

        CardGroup(title = { Text(stringResource(R.string.screen_settings_admin_actions_section)) }) {
            item(
                onClick = onSave,
                headlineContent = { Text(stringResource(R.string.action_save_settings)) },
                leadingContent = { Icon(LettaIcons.Check, contentDescription = null) },
            )
            item(
                onClick = onExport,
                headlineContent = { Text(stringResource(R.string.action_export_agent)) },
                leadingContent = { Icon(LettaIcons.Share, contentDescription = null) },
            )
            item(
                onClick = { showCloneDialog = true },
                headlineContent = { Text(stringResource(R.string.action_clone_agent)) },
                supportingContent = {
                    Text(stringResource(R.string.screen_settings_clone_helper))
                },
                leadingContent = { Icon(LettaIcons.Copy, contentDescription = null) },
            )
            item(
                onClick = { showResetDialog = true },
                headlineContent = {
                    Text(
                        text = stringResource(R.string.action_reset_messages),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingContent = {
                    Icon(
                        LettaIcons.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
            item(
                onClick = { showDeleteDialog = true },
                headlineContent = {
                    Text(
                        text = stringResource(R.string.screen_agents_dialog_delete_title),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingContent = {
                    Icon(
                        LettaIcons.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }

    ConfirmDialog(
        show = showResetDialog,
        title = stringResource(R.string.screen_settings_reset_messages_title),
        message = stringResource(R.string.screen_settings_reset_messages_confirm),
        confirmText = stringResource(R.string.action_reset_messages),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showResetDialog = false; onResetMessages() },
        onDismiss = { showResetDialog = false },
        destructive = true,
    )

    ConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.screen_agents_dialog_delete_title),
        message = stringResource(R.string.screen_agents_dialog_delete_confirm_permanent),
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showDeleteDialog = false; onDelete() },
        onDismiss = { showDeleteDialog = false },
        destructive = true,
    )

    if (showCloneDialog) {
        CloneAgentDialog(
            initialName = state.agent?.name.orEmpty(),
            isCloning = state.isCloning,
            onDismiss = { showCloneDialog = false },
            onClone = { cloneName, overrideExistingTools, stripMessages ->
                showCloneDialog = false
                onClone(cloneName, overrideExistingTools, stripMessages)
            },
        )
    }
}

@Composable
internal fun ClientModeSettingsSection(
    state: AgentSettingsUiState,
    onClientModeEnabledChange: (Boolean) -> Unit,
    onClientModeBaseUrlChange: (String) -> Unit,
    onClientModeApiKeyChange: (String) -> Unit,
    onTestClientModeConnection: () -> Unit,
) {
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

        if (state.clientModeEnabled) {
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
                        style = MaterialTheme.typography.listItemSupporting,
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
                                    CircularProgressIndicator(
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

@Composable
private fun ToolDetailDialog(
    tool: com.letta.mobile.data.model.Tool,
    onDismiss: () -> Unit,
    onTagClick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(LettaIcons.Tool, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(tool.name)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tool.description?.let { desc ->
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.listItemSupporting,
                    )
                }
                tool.toolType?.let { type ->
                    Row {
                        Text(
                            text = stringResource(R.string.common_type) + ": ",
                            style = MaterialTheme.typography.listItemMetadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = type,
                            style = MaterialTheme.typography.listItemSupporting,
                        )
                    }
                }
                if (tool.tags.isNotEmpty()) {
                    Column {
                        Text(
                            text = stringResource(R.string.common_tags),
                            style = MaterialTheme.typography.listItemMetadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

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
                    style = MaterialTheme.typography.listItemSupporting,
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
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.screen_agents_import_override_tools_title), style = MaterialTheme.typography.listItemSupporting)
                        Text(
                            stringResource(R.string.screen_agents_import_override_tools_helper),
                            style = MaterialTheme.typography.listItemSupporting,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = overrideExistingTools, onCheckedChange = { overrideExistingTools = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.screen_agents_import_strip_messages_title), style = MaterialTheme.typography.listItemSupporting)
                        Text(
                            stringResource(R.string.screen_agents_import_strip_messages_helper),
                            style = MaterialTheme.typography.listItemSupporting,
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
