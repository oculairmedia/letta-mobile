package com.letta.mobile.ui.screens.editagent

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
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.ModelDropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAgentScreen(
    onNavigateBack: () -> Unit,
    viewModel: EditAgentViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val llmModels by viewModel.llmModels.collectAsStateWithLifecycle()
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
            is UiState.Loading -> LoadingIndicator()
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadAgent() },
                modifier = Modifier.padding(paddingValues)
            )
            is UiState.Success -> EditAgentContent(
                state = state.data,
                llmModels = llmModels,
                onNameChange = { viewModel.updateName(it) },
                onDescriptionChange = { viewModel.updateDescription(it) },
                onModelChange = { viewModel.updateModel(it) },
                onLoadModels = { viewModel.loadModels() },
                onPersonaChange = { viewModel.updatePersonaBlock(it) },
                onHumanChange = { viewModel.updateHumanBlock(it) },
                onSystemPromptChange = { viewModel.updateSystemPrompt(it) },
                onSave = { viewModel.saveAgent { snackbar.dispatch("Agent saved"); onNavigateBack() } },
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

@Composable
private fun EditAgentContent(
    state: EditAgentUiState,
    llmModels: List<LlmModel> = emptyList(),
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onLoadModels: () -> Unit = {},
    onPersonaChange: (String) -> Unit,
    onHumanChange: (String) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.screen_agent_edit_basic_section),
            style = MaterialTheme.typography.titleMedium
        )

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
            state.tags.forEach { tag ->
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
