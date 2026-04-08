package com.letta.mobile.ui.screens.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.ShimmerCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigListScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConfigListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_config_list_title)) },
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
                onRetry = { viewModel.loadConfigs() },
                modifier = Modifier.padding(paddingValues)
            )
            is UiState.Success -> ConfigListContent(
                state = state.data,
                onSetActive = { viewModel.setActiveConfig(it.id) },
                onDelete = { viewModel.deleteConfig(it.id) },
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

@Composable
private fun ConfigListContent(
    state: ConfigListUiState,
    onSetActive: (ServerConfig) -> Unit,
    onDelete: (ServerConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.configs.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Settings,
            message = stringResource(R.string.screen_config_list_empty),
            modifier = modifier.fillMaxSize()
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = state.configs,
                key = { it.id }
            ) { config ->
                ConfigCard(
                    config = config,
                    onSetActive = { onSetActive(config) },
                    onDelete = { onDelete(config) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigCard(
    config: ServerConfig,
    onSetActive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = {},
                        label = { 
                            Text(
                                text = config.mode.name,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                    if (config.isActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        AssistChip(
                            onClick = {},
                            label = { 
                                Text(
                                    text = stringResource(R.string.common_active),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = if (config.mode == ServerMode.CLOUD) stringResource(R.string.common_letta_cloud) 
                           else config.url,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row {
                if (!config.isActive) {
                    FilledTonalButton(onClick = onSetActive) {
                        Text(stringResource(R.string.action_set_active))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(Icons.Default.Delete, stringResource(R.string.action_delete))
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.screen_config_dialog_delete_title)) },
            text = { Text(stringResource(R.string.screen_config_dialog_delete_confirm)) },
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
