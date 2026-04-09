package com.letta.mobile.ui.screens.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Block
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockLibraryScreen(
    onNavigateBack: () -> Unit,
    viewModel: BlockLibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedBlock by remember { mutableStateOf<Block?>(null) }
    var editTarget by remember { mutableStateOf<Block?>(null) }
    var deleteTarget by remember { mutableStateOf<Block?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_blocks_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, stringResource(R.string.screen_blocks_create_title))
            }
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadBlocks() },
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> {
                state.data.operationError?.let { message ->
                    AlertDialog(
                        onDismissRequest = viewModel::clearOperationError,
                        title = { Text(stringResource(R.string.common_error)) },
                        text = { Text(message) },
                        confirmButton = {
                            TextButton(onClick = viewModel::clearOperationError) {
                                Text(stringResource(R.string.action_close))
                            }
                        },
                    )
                }

                val filteredBlocks = remember(state.data.blocks, state.data.searchQuery) {
                    viewModel.getFilteredBlocks()
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    OutlinedTextField(
                        value = state.data.searchQuery,
                        onValueChange = viewModel::updateSearchQuery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(stringResource(R.string.screen_blocks_search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                    )

                    if (filteredBlocks.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.Search,
                            message = if (state.data.searchQuery.isBlank()) {
                                stringResource(R.string.screen_blocks_empty)
                            } else {
                                stringResource(R.string.screen_blocks_empty_search, state.data.searchQuery)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(filteredBlocks, key = { it.id }) { block ->
                                BlockLibraryCard(
                                    block = block,
                                    onInspect = { selectedBlock = block },
                                    onEdit = { if (block.readOnly != true) editTarget = block },
                                    onDelete = { if (block.readOnly != true) deleteTarget = block },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selectedBlock?.let { block ->
        BlockDetailDialog(
            block = block,
            onEdit = if (block.readOnly == true) null else {
                {
                    selectedBlock = null
                    editTarget = block
                }
            },
            onDismiss = { selectedBlock = null },
        )
    }

    if (showCreateDialog) {
        BlockEditorDialog(
            title = stringResource(R.string.screen_blocks_create_title),
            confirmLabel = stringResource(R.string.action_create),
            onDismiss = { showCreateDialog = false },
            onConfirm = { label, value, description, limit ->
                viewModel.createBlock(label, value, description, limit) {
                    showCreateDialog = false
                }
            },
        )
    }

    editTarget?.let { block ->
        BlockEditorDialog(
            title = stringResource(R.string.screen_blocks_edit_title),
            confirmLabel = stringResource(R.string.action_save),
            initialLabel = block.label.orEmpty(),
            initialValue = block.value,
            initialDescription = block.description.orEmpty(),
            initialLimit = block.limit?.toString().orEmpty(),
            labelEnabled = false,
            onDismiss = { editTarget = null },
            onConfirm = { _, value, description, limit ->
                viewModel.updateGlobalBlock(block.id, value, description, limit) {
                    editTarget = null
                    selectedBlock = null
                }
            },
        )
    }

    deleteTarget?.let { block ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.screen_blocks_delete_title)) },
            text = { Text(stringResource(R.string.screen_blocks_delete_confirm, block.label ?: stringResource(R.string.common_unknown))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBlock(block.id) {
                            deleteTarget = null
                            if (selectedBlock?.id == block.id) selectedBlock = null
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun BlockLibraryCard(
    block: Block,
    onInspect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onInspect,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = block.label ?: stringResource(R.string.common_unknown),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    block.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (block.readOnly != true) {
                    Row {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, stringResource(R.string.action_edit))
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, stringResource(R.string.action_delete))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                block.limit?.let { limit ->
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.screen_blocks_limit_chip, limit)) })
                }
                block.isTemplate?.takeIf { it }?.let {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.screen_agent_edit_block_template)) })
                }
                block.readOnly?.takeIf { it }?.let {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.screen_agent_edit_block_read_only)) })
                }
            }
        }
    }
}

@Composable
private fun BlockDetailDialog(
    block: Block,
    onEdit: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(block.label ?: stringResource(R.string.common_unknown)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                block.limit?.let { limit ->
                    Text(
                        text = stringResource(R.string.screen_blocks_limit_label, limit),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                block.createdAt?.let { createdAt ->
                    Text(
                        text = stringResource(R.string.screen_blocks_created_label, createdAt),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                block.updatedAt?.let { updatedAt ->
                    Text(
                        text = stringResource(R.string.screen_blocks_updated_label, updatedAt),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
                Text(
                    text = block.value,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            Row {
                onEdit?.let {
                    TextButton(onClick = it) {
                        Text(stringResource(R.string.action_edit))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            }
        },
    )
}

@Composable
private fun BlockEditorDialog(
    title: String,
    confirmLabel: String,
    initialLabel: String = "",
    initialValue: String = "",
    initialDescription: String = "",
    initialLimit: String = "",
    labelEnabled: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (label: String, value: String, description: String, limit: Int?) -> Unit,
) {
    var label by remember(initialLabel) { mutableStateOf(initialLabel) }
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var limit by remember(initialLimit) { mutableStateOf(initialLimit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!labelEnabled) {
                    Text(
                        text = stringResource(R.string.screen_blocks_global_edit_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.common_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = labelEnabled,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.common_value)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.common_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                OutlinedTextField(
                    value = limit,
                    onValueChange = { next ->
                        if (next.isBlank() || next.toIntOrNull() != null) {
                            limit = next
                        }
                    },
                    label = { Text(stringResource(R.string.screen_blocks_limit_input_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label.trim(), value, description, limit.toIntOrNull()) },
                enabled = value.isNotBlank() && (!labelEnabled || label.isNotBlank()),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
