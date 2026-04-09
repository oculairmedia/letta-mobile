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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.screen_settings_model_section),
            style = MaterialTheme.typography.titleMedium
        )
        
        OutlinedTextField(
            value = state.agent?.model ?: stringResource(R.string.screen_settings_no_model),
            onValueChange = {},
            label = { Text(stringResource(R.string.common_model)) },
            readOnly = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.screen_settings_context_window_limit, state.agent?.contextWindowLimit ?: 0),
            style = MaterialTheme.typography.bodyMedium
        )

        Divider()

        Text(
            text = stringResource(R.string.screen_settings_temperature_value, state.temperature),
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = state.temperature,
            onValueChange = onTemperatureChange,
            valueRange = 0f..2f,
            steps = 19
        )

        OutlinedTextField(
            value = state.maxTokens.toString(),
            onValueChange = { 
                it.toIntOrNull()?.let { value -> onMaxTokensChange(value) }
            },
            label = { Text(stringResource(R.string.common_max_output_tokens)) },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.common_parallel_tool_calls),
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = state.parallelToolCalls,
                onCheckedChange = onParallelToolCallsChange
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.common_enable_sleeptime),
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = state.enableSleeptime,
                onCheckedChange = onSleeptimeChange
            )
        }

        Divider()

        Text(
            text = stringResource(R.string.screen_agent_memory_blocks_section),
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = state.personaBlock,
            onValueChange = onPersonaChange,
            label = { Text(stringResource(R.string.common_persona)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )

        OutlinedTextField(
            value = state.humanBlock,
            onValueChange = onHumanChange,
            label = { Text(stringResource(R.string.common_human)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )

        Divider()

        Text(
            text = stringResource(R.string.common_system_prompt),
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = state.systemPrompt,
            onValueChange = onSystemPromptChange,
            label = { Text(stringResource(R.string.common_system_prompt)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5
        )

        Divider()

        Text(
            text = stringResource(R.string.common_tags),
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.agent?.tags?.forEach { tag ->
                AssistChip(
                    onClick = {},
                    label = { Text(tag) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.screen_settings_admin_actions_section),
            style = MaterialTheme.typography.titleMedium
        )

        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_save_settings))
        }

        OutlinedButton(
            onClick = onExport,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Share, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.action_export_agent))
        }

        OutlinedButton(
            onClick = { showResetDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.Refresh, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.action_reset_messages))
        }

        OutlinedButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.Delete, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.screen_agents_dialog_delete_title))
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.screen_settings_reset_messages_title)) },
            text = { Text(stringResource(R.string.screen_settings_reset_messages_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onResetMessages()
                    }
                ) {
                    Text(stringResource(R.string.action_reset_messages), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.screen_agents_dialog_delete_title)) },
            text = { Text(stringResource(R.string.screen_agents_dialog_delete_confirm_permanent)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
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
