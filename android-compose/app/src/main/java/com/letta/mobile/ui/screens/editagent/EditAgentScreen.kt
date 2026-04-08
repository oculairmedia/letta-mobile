package com.letta.mobile.ui.screens.editagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.Accordions
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.components.ModelDropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAgentScreen(
    onNavigateBack: () -> Unit,
    viewModel: EditAgentViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val llmModels by viewModel.llmModels.collectAsStateWithLifecycle()
    val embeddingModels by viewModel.embeddingModels.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_agent_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadAgent() },
                modifier = Modifier.padding(paddingValues)
            )
            is UiState.Success -> EditAgentContent(
                state = state.data,
                llmModels = llmModels,
                embeddingModels = embeddingModels,
                onNameChange = { viewModel.updateName(it) },
                onDescriptionChange = { viewModel.updateDescription(it) },
                onModelChange = { viewModel.updateModel(it) },
                onEmbeddingChange = { viewModel.updateEmbedding(it) },
                onLoadModels = { viewModel.loadModels() },
                onBlockValueChange = { label, value -> viewModel.updateBlockValue(label, value) },
                onAddBlock = { label, value -> viewModel.addBlock(label, value) },
                onDeleteBlock = { viewModel.deleteBlock(it) },
                onAddTag = { viewModel.addTag(it) },
                onRemoveTag = { viewModel.removeTag(it) },
                onSystemPromptChange = { viewModel.updateSystemPrompt(it) },
                onTemperatureChange = { viewModel.updateTemperature(it) },
                onMaxOutputTokensChange = { viewModel.updateMaxOutputTokens(it) },
                onParallelToolCallsChange = { viewModel.updateParallelToolCalls(it) },
                onEnableSleeptimeChange = { viewModel.updateEnableSleeptime(it) },
                onSave = { viewModel.saveAgent { snackbar.dispatch("Agent saved"); onNavigateBack() } },
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

@Composable
private fun EditAgentContent(
    state: EditAgentUiState,
    llmModels: List<LlmModel>,
    embeddingModels: List<EmbeddingModel>,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onEmbeddingChange: (String) -> Unit,
    onLoadModels: () -> Unit,
    onBlockValueChange: (String, String) -> Unit,
    onAddBlock: (String, String) -> Unit,
    onDeleteBlock: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    onMaxOutputTokensChange: (Int) -> Unit,
    onParallelToolCallsChange: (Boolean) -> Unit,
    onEnableSleeptimeChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarDispatcher.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(state.name.ifBlank { "Agent" }, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = state.agentId,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Agent ID", state.agentId))
                snackbar.dispatch("Agent ID copied")
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Agent ID")
            }
        }

        if (state.agentType.isNotBlank()) {
            Text(
                text = "Type: ${state.agentType}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.common_name)) },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChange,
            label = { Text(stringResource(R.string.common_description)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        ModelDropdown(
            selectedModel = state.model,
            models = llmModels,
            onModelSelected = onModelChange,
            onLoadModels = onLoadModels,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.common_model),
        )

        ModelDropdown(
            selectedModel = state.embedding,
            models = embeddingModels.map { LlmModel(id = it.id, name = it.displayName, providerType = it.providerType, contextWindow = it.embeddingDim) },
            onModelSelected = onEmbeddingChange,
            onLoadModels = onLoadModels,
            modifier = Modifier.fillMaxWidth(),
            label = "Embedding Model",
        )

        HorizontalDivider()

        var llmConfigExpanded by remember { mutableStateOf(false) }
        Accordions(
            title = "LLM Configuration",
            subtitle = "Temperature: ${String.format("%.1f", state.temperature)}",
            expanded = llmConfigExpanded,
            onExpandedChange = { llmConfigExpanded = it },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Temperature: ${String.format("%.2f", state.temperature)}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = state.temperature,
                    onValueChange = onTemperatureChange,
                    valueRange = 0f..2f,
                    steps = 39,
                )

                if (state.contextWindow > 0) {
                    Text("Context window: ${state.contextWindow}", style = MaterialTheme.typography.bodyMedium)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Parallel tool calls", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = state.parallelToolCalls, onCheckedChange = onParallelToolCallsChange)
                }

                OutlinedTextField(
                    value = state.maxOutputTokens.toString(),
                    onValueChange = { it.toIntOrNull()?.let(onMaxOutputTokensChange) },
                    label = { Text("Max output tokens") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Enable Sleeptime", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = state.enableSleeptime, onCheckedChange = onEnableSleeptimeChange)
        }

        HorizontalDivider()

        var memoryExpanded by remember { mutableStateOf(true) }
        var showAddBlockDialog by remember { mutableStateOf(false) }
        Accordions(
            title = stringResource(R.string.screen_agent_memory_blocks_section),
            subtitle = "${state.blocks.size} blocks",
            expanded = memoryExpanded,
            onExpandedChange = { memoryExpanded = it },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.blocks.forEach { block ->
                    Row(verticalAlignment = Alignment.Top) {
                        OutlinedTextField(
                            value = block.value,
                            onValueChange = { onBlockValueChange(block.label, it) },
                            label = { Text(block.label) },
                            modifier = Modifier.weight(1f),
                            minLines = 2,
                            supportingText = block.limit?.let { limit ->
                                { Text("${block.value.length}/$limit chars") }
                            },
                        )
                        IconButton(onClick = { onDeleteBlock(block.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Delete block", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                OutlinedButton(
                    onClick = { showAddBlockDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Memory Block")
                }
            }
        }
        if (showAddBlockDialog) {
            var newLabel by remember { mutableStateOf("") }
            var newValue by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddBlockDialog = false },
                title = { Text("Add Memory Block") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newLabel,
                            onValueChange = { newLabel = it },
                            label = { Text("Label") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = newValue,
                            onValueChange = { newValue = it },
                            label = { Text("Value") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { onAddBlock(newLabel, newValue); showAddBlockDialog = false },
                        enabled = newLabel.isNotBlank(),
                    ) { Text(stringResource(R.string.action_create)) }
                },
                dismissButton = {
                    TextButton(onClick = { showAddBlockDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }

        HorizontalDivider()

        var systemExpanded by remember { mutableStateOf(false) }
        Accordions(
            title = stringResource(R.string.common_system_prompt),
            subtitle = "${state.systemPrompt.length} chars",
            expanded = systemExpanded,
            onExpandedChange = { systemExpanded = it },
        ) {
            OutlinedTextField(
                value = state.systemPrompt,
                onValueChange = onSystemPromptChange,
                label = { Text(stringResource(R.string.common_system_prompt)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                minLines = 5
            )
        }

        HorizontalDivider()

        Text(stringResource(R.string.common_tags), style = MaterialTheme.typography.titleMedium)

        var newTag by remember { mutableStateOf("") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.tags.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = { onRemoveTag(tag) },
                    label = { Text(tag) },
                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newTag,
                onValueChange = { newTag = it },
                label = { Text("New tag") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = { onAddTag(newTag); newTag = "" },
                enabled = newTag.isNotBlank(),
            ) { Text(stringResource(R.string.action_add)) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.action_save_changes))
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
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
