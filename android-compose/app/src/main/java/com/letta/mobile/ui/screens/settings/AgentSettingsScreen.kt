package com.letta.mobile.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.LoadingIndicator
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
            onSystemPromptChange = { viewModel.updateSystemPrompt(it) },
            onSave = { viewModel.saveSettings(); snackbar.dispatch("Settings saved") },
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
    onSystemPromptChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

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

        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_save_settings))
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

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error
                )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}
