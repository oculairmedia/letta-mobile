package com.letta.mobile.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        is UiState.Loading -> LoadingIndicator()
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
            onSave = { viewModel.saveSettings() },
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
            text = "Model Settings",
            style = MaterialTheme.typography.titleMedium
        )
        
        OutlinedTextField(
            value = state.agent?.model ?: "No model",
            onValueChange = {},
            label = { Text("Model") },
            readOnly = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "Context Window: ${state.agent?.contextWindowLimit ?: 0}",
            style = MaterialTheme.typography.bodyMedium
        )

        Divider()

        Text(
            text = "Temperature: ${String.format("%.2f", state.temperature)}",
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
            label = { Text("Max Output Tokens") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Parallel Tool Calls",
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = state.parallelToolCalls,
                onCheckedChange = onParallelToolCallsChange
            )
        }

        Divider()

        Text(
            text = "Memory Blocks",
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = state.personaBlock,
            onValueChange = onPersonaChange,
            label = { Text("Persona") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )

        OutlinedTextField(
            value = state.humanBlock,
            onValueChange = onHumanChange,
            label = { Text("Human") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )

        Divider()

        Text(
            text = "System Prompt",
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = state.systemPrompt,
            onValueChange = onSystemPromptChange,
            label = { Text("System Prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5
        )

        Divider()

        Text(
            text = "Tags",
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
            Text("Save Settings")
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
            Text("Delete Agent")
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Agent") },
            text = { Text("Are you sure you want to delete this agent? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
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
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}
