package com.letta.mobile.ui.screens.folders

import com.letta.mobile.ui.theme.LettaCodeFont

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.letta.mobile.ui.components.ExpandableSearchField
import com.letta.mobile.ui.components.ExpandableTitleSearch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Folder
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.MultiFieldInputDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.icons.LettaIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderAdminScreen(
    onNavigateBack: () -> Unit,
    viewModel: FolderAdminViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Folder?>(null) }
    var deleteTarget by remember { mutableStateOf<Folder?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
            LargeFlexibleTopAppBar(
                title = {
                    ExpandableTitleSearch(
                        query = (uiState as? UiState.Success)?.data?.searchQuery.orEmpty(),
                        onQueryChange = viewModel::updateSearchQuery,
                        onClear = { viewModel.updateSearchQuery("") },
                        expanded = isSearchExpanded,
                        onExpandedChange = { isSearchExpanded = it },
                        placeholder = stringResource(R.string.screen_folders_search_hint),
                        openSearchContentDescription = stringResource(R.string.action_search),
                        closeSearchContentDescription = stringResource(R.string.action_close),
                        titleContent = { Text(stringResource(R.string.screen_folders_title)) },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
            ExpandableSearchField(
                query = (uiState as? UiState.Success)?.data?.searchQuery.orEmpty(),
                onQueryChange = viewModel::updateSearchQuery,
                onClear = { viewModel.updateSearchQuery("") },
                expanded = isSearchExpanded,
                placeholder = stringResource(R.string.screen_folders_search_hint),
            )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(LettaIcons.Add, stringResource(R.string.screen_folders_add_title))
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
                            icon = LettaIcons.Search,
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
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.common_error),
            message = operationError,
            confirmText = stringResource(R.string.action_dismiss),
            dismissText = stringResource(R.string.action_dismiss),
            onConfirm = viewModel::clearOperationError,
            onDismiss = viewModel::clearOperationError,
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
    var showContextMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onInspect,
        modifier = Modifier.fillMaxWidth(),
        colors = LettaCardDefaults.listCardColors(),
    ) {
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
                IconButton(onClick = { showContextMenu = true }) {
                    Icon(LettaIcons.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
            }
            folder.vectorDbProvider?.let { provider ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text(provider) })
                }
            }
        }
    }

    ActionSheet(
        show = showContextMenu,
        onDismiss = { showContextMenu = false },
        title = folder.name,
    ) {
        ActionSheetItem(
            text = stringResource(R.string.screen_folders_edit_title),
            icon = LettaIcons.Edit,
            onClick = {
                showContextMenu = false
                onEdit()
            },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_delete),
            icon = LettaIcons.Delete,
            onClick = {
                showContextMenu = false
                onDelete()
            },
            destructive = true,
        )
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
    ConfirmDialog(
        show = true,
        title = folder.name,
        confirmText = stringResource(R.string.action_close),
        dismissText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.screen_folders_id_label, "")) },
                    supportingContent = { Text(folder.id.value, style = MaterialTheme.typography.bodySmall, fontFamily = LettaCodeFont) },
                )
                folder.description?.let { description ->
                    item(
                        headlineContent = { Text(stringResource(R.string.common_description)) },
                        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
                    )
                }
                folder.instructions?.let { instructions ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_folders_instructions_label, "")) },
                        supportingContent = { Text(instructions, style = MaterialTheme.typography.bodySmall) },
                    )
                }
                folder.organizationId?.let { orgId ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_folders_organization_label, "")) },
                        supportingContent = { Text(orgId, style = MaterialTheme.typography.bodySmall) },
                    )
                }
                folder.vectorDbProvider?.let { provider ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_folders_vector_provider_label, "")) },
                        supportingContent = { Text(provider, style = MaterialTheme.typography.bodySmall) },
                    )
                }
                folder.createdAt?.let { created ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_folders_created_label, "")) },
                        supportingContent = { Text(created, style = MaterialTheme.typography.bodySmall) },
                    )
                }
                folder.updatedAt?.let { updated ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_folders_updated_label, "")) },
                        supportingContent = { Text(updated, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }

            if (attachedAgents.isNotEmpty()) {
                CardGroup(title = { Text(stringResource(R.string.screen_folders_agents_title)) }) {
                    attachedAgents.forEach { agentId ->
                        item(
                            headlineContent = { Text(agentId, style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                }
            }

            if (files.isNotEmpty()) {
                CardGroup(title = { Text(stringResource(R.string.screen_folders_files_title)) }) {
                    files.take(5).forEach { file ->
                        item(
                            headlineContent = { Text(file.fileName ?: file.id, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }

            if (passages.isNotEmpty()) {
                CardGroup(title = { Text(stringResource(R.string.screen_folders_passages_title)) }) {
                    passages.take(5).forEach { passage ->
                        item(
                            headlineContent = { Text(passage.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }

            if (folder.metadata.isNotEmpty()) {
                CardGroup(title = { Text(stringResource(R.string.screen_archival_metadata_title)) }) {
                    folder.metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
                        item(
                            headlineContent = { Text(key, style = MaterialTheme.typography.bodySmall) },
                            supportingContent = { Text(value.toString().trim('"'), style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                }
            }

            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.screen_folders_edit_title))
            }
        }
    }
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

    MultiFieldInputDialog(
        show = true,
        title = title,
        confirmText = confirmLabel,
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name.trim(), description.trim(), instructions.trim()) },
    ) {
        CardGroup {
            item(
                headlineContent = {
                    FormItem(label = { Text(stringResource(R.string.common_name)) }) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
            item(
                headlineContent = {
                    FormItem(label = { Text(stringResource(R.string.common_description)) }) {
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
            item(
                headlineContent = {
                    FormItem(label = { Text(stringResource(R.string.screen_folders_instructions_input)) }) {
                        OutlinedTextField(
                            value = instructions,
                            onValueChange = { instructions = it },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
        }
    }
}
