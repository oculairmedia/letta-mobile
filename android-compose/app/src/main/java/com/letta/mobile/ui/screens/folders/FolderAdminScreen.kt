package com.letta.mobile.ui.screens.folders

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Folder
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderAdminScreen(
    onNavigateBack: () -> Unit,
    viewModel: FolderAdminViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Folder?>(null) }
    var deleteTarget by remember { mutableStateOf<Folder?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_folders_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, stringResource(R.string.screen_folders_add_title))
            }
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = viewModel::loadFolders,
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> {
                val filtered = remember(state.data.folders, state.data.searchQuery) { viewModel.getFilteredFolders() }
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
                        placeholder = { Text(stringResource(R.string.screen_folders_search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                    )

                    state.data.folderMetadata?.let { metadata ->
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AssistChip(onClick = {}, label = { Text(stringResource(R.string.screen_folders_total_sources_chip, metadata.totalSources)) })
                            AssistChip(onClick = {}, label = { Text(stringResource(R.string.screen_folders_total_files_chip, metadata.totalFiles)) })
                        }
                    }

                    if (filtered.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.Search,
                            message = if (state.data.searchQuery.isBlank()) {
                                stringResource(R.string.screen_folders_empty)
                            } else {
                                stringResource(R.string.screen_folders_empty_search, state.data.searchQuery)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(filtered, key = { it.id }) { folder ->
                                FolderCard(
                                    folder = folder,
                                    onInspect = { viewModel.inspectFolder(folder.id) },
                                    onEdit = { editTarget = folder },
                                    onDelete = { deleteTarget = folder },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val selectedFolder = (uiState as? UiState.Success)?.data?.selectedFolder
    selectedFolder?.let { folder ->
        FolderDetailDialog(
            folder = folder,
            attachedAgents = (uiState as? UiState.Success)?.data?.selectedFolderAgents.orEmpty(),
            files = (uiState as? UiState.Success)?.data?.selectedFolderFiles.orEmpty(),
            passages = (uiState as? UiState.Success)?.data?.selectedFolderPassages.orEmpty(),
            onDismiss = viewModel::clearSelectedFolder,
            onEdit = {
                viewModel.clearSelectedFolder()
                editTarget = folder
            },
        )
    }

    if (showCreateDialog) {
        FolderEditorDialog(
            title = stringResource(R.string.screen_folders_add_title),
            confirmLabel = stringResource(R.string.action_create),
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, description, instructions ->
                viewModel.createFolder(name, description, instructions) { showCreateDialog = false }
            },
        )
    }

    editTarget?.let { folder ->
        FolderEditorDialog(
            title = stringResource(R.string.screen_folders_edit_title),
            confirmLabel = stringResource(R.string.action_save),
            initialName = folder.name,
            initialDescription = folder.description.orEmpty(),
            initialInstructions = folder.instructions.orEmpty(),
            onDismiss = { editTarget = null },
            onConfirm = { name, description, instructions ->
                viewModel.updateFolder(folder.id, name, description, instructions) { editTarget = null }
            },
        )
    }

    deleteTarget?.let { folder ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.screen_folders_delete_title),
            message = stringResource(R.string.screen_folders_delete_confirm, folder.name),
            confirmText = stringResource(R.string.action_delete),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                viewModel.deleteFolder(folder.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            destructive = true,
        )
    }

    val operationError = (uiState as? UiState.Success)?.data?.operationError
    if (operationError != null) {
        AlertDialog(
            onDismissRequest = viewModel::clearOperationError,
            title = { Text(stringResource(R.string.common_error)) },
            text = { Text(operationError) },
            confirmButton = {
                TextButton(onClick = viewModel::clearOperationError) {
                    Text(stringResource(R.string.action_dismiss))
                }
            },
        )
    }
}

@Composable
private fun FolderCard(
    folder: Folder,
    onInspect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onInspect, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(folder.name, style = MaterialTheme.typography.titleMedium)
                    folder.description?.takeIf { it.isNotBlank() }?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.screen_folders_edit_title))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                folder.vectorDbProvider?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                folder.createdAt?.let { AssistChip(onClick = {}, label = { Text(it.take(10)) }) }
            }
        }
    }
}

@Composable
private fun FolderDetailDialog(
    folder: Folder,
    attachedAgents: List<String>,
    files: List<com.letta.mobile.data.model.FileMetadata>,
    passages: List<com.letta.mobile.data.model.Passage>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(folder.name, fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.screen_folders_id_label, folder.id), style = MaterialTheme.typography.bodySmall)
                folder.description?.let { Text(stringResource(R.string.screen_folders_description_label, it), style = MaterialTheme.typography.bodySmall) }
                folder.instructions?.let { Text(stringResource(R.string.screen_folders_instructions_label, it), style = MaterialTheme.typography.bodySmall) }
                folder.organizationId?.let { Text(stringResource(R.string.screen_folders_organization_label, it), style = MaterialTheme.typography.bodySmall) }
                folder.vectorDbProvider?.let { Text(stringResource(R.string.screen_folders_vector_provider_label, it), style = MaterialTheme.typography.bodySmall) }
                folder.createdAt?.let { Text(stringResource(R.string.screen_folders_created_label, it), style = MaterialTheme.typography.bodySmall) }
                folder.updatedAt?.let { Text(stringResource(R.string.screen_folders_updated_label, it), style = MaterialTheme.typography.bodySmall) }
                if (attachedAgents.isNotEmpty()) {
                    Text(stringResource(R.string.screen_folders_agents_title), style = MaterialTheme.typography.labelLarge)
                    attachedAgents.forEach { agentId -> Text(agentId, style = MaterialTheme.typography.bodySmall) }
                }
                if (files.isNotEmpty()) {
                    Text(stringResource(R.string.screen_folders_files_title), style = MaterialTheme.typography.labelLarge)
                    files.take(5).forEach { file ->
                        Text(file.fileName ?: file.id, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (passages.isNotEmpty()) {
                    Text(stringResource(R.string.screen_folders_passages_title), style = MaterialTheme.typography.labelLarge)
                    passages.take(5).forEach { passage ->
                        Text(passage.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (folder.metadata.isNotEmpty()) {
                    Text(stringResource(R.string.screen_archival_metadata_title), style = MaterialTheme.typography.labelLarge)
                    folder.metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
                        Text("$key: ${value.toString().trim('"')}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.screen_folders_edit_title))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun FolderEditorDialog(
    title: String,
    confirmLabel: String,
    initialName: String = "",
    initialDescription: String = "",
    initialInstructions: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var instructions by remember(initialInstructions) { mutableStateOf(initialInstructions) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.common_name)) }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(stringResource(R.string.common_description)) }, minLines = 2)
                OutlinedTextField(value = instructions, onValueChange = { instructions = it }, label = { Text(stringResource(R.string.screen_folders_instructions_input)) }, minLines = 3)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), description.trim(), instructions.trim()) }, enabled = name.isNotBlank()) {
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
