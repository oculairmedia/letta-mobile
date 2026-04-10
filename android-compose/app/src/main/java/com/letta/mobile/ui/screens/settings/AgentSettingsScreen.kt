package com.letta.mobile.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
            onResetMessages = {
                viewModel.resetMessages {
                    snackbar.dispatch(context.getString(R.string.screen_settings_messages_reset))
                }
            },
            onDelete = { viewModel.deleteAgent(onNavigateBack) },
            modifier = modifier
        )
    }
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
    onSave: () -> Unit,
    onExport: () -> Unit,
    onResetMessages: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

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
                            style = MaterialTheme.typography.bodyMedium,
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }

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
            CardGroup(title = { Text(stringResource(R.string.common_tools)) }) {
                item(
                    headlineContent = {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            state.tools.forEach { tool ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(tool.name) },
                                )
                            }
                        }
                    },
                )
            }
        }

        if (!state.agent?.tags.isNullOrEmpty()) {
            CardGroup(title = { Text(stringResource(R.string.common_tags)) }) {
                item(
                    headlineContent = {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            state.agent?.tags?.forEach { tag ->
                                AssistChip(
                                    onClick = {},
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
                leadingContent = { Icon(Icons.Default.Check, contentDescription = null) },
            )
            item(
                onClick = onExport,
                headlineContent = { Text(stringResource(R.string.action_export_agent)) },
                leadingContent = { Icon(Icons.Default.Share, contentDescription = null) },
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
                        Icons.Default.Refresh,
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
                        Icons.Default.Delete,
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
