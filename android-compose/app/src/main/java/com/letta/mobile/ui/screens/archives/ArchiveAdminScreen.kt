package com.letta.mobile.ui.screens.archives

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Archive
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.icons.LettaIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveAdminScreen(
    onNavigateBack: () -> Unit,
    viewModel: ArchiveAdminViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Archive?>(null) }
    var deleteTarget by remember { mutableStateOf<Archive?>(null) }
    var attachTarget by remember { mutableStateOf<Archive?>(null) }
    var detachTarget by remember { mutableStateOf<Pair<Archive, Agent>?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_archives_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(LettaIcons.Add, stringResource(R.string.screen_archives_add_title))
            }
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = viewModel::loadArchives,
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> {
                val filtered = remember(state.data.archives, state.data.searchQuery) { viewModel.getFilteredArchives() }
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
                        placeholder = { Text(stringResource(R.string.screen_archives_search_hint)) },
                        leadingIcon = { Icon(LettaIcons.Search, contentDescription = null) },
                        singleLine = true,
                    )

                    if (filtered.isEmpty()) {
                        EmptyState(
                            icon = LettaIcons.Search,
                            message = if (state.data.searchQuery.isBlank()) {
                                stringResource(R.string.screen_archives_empty)
                            } else {
                                stringResource(R.string.screen_archives_empty_search, state.data.searchQuery)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(filtered, key = { it.id }) { archive ->
                                ArchiveCard(
                                    archive = archive,
                                    onInspect = { viewModel.inspectArchive(archive.id) },
                                    onEdit = { editTarget = archive },
                                    onDelete = { deleteTarget = archive },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val selectedArchive = (uiState as? UiState.Success)?.data?.selectedArchive
    selectedArchive?.let { archive ->
        ArchiveDetailDialog(
            archive = archive,
            attachedAgents = (uiState as? UiState.Success)?.data?.selectedArchiveAgents.orEmpty(),
            onDismiss = viewModel::clearSelectedArchive,
            onEdit = {
                viewModel.clearSelectedArchive()
                editTarget = archive
            },
            onAttachAgent = {
                viewModel.clearOperationError()
                attachTarget = archive
            },
            onDetachAgent = { agent ->
                viewModel.clearOperationError()
                detachTarget = archive to agent
            },
        )
    }

    if (showCreateDialog) {
        ArchiveEditorDialog(
            title = stringResource(R.string.screen_archives_add_title),
            confirmLabel = stringResource(R.string.action_create),
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, description, embeddingModel ->
                viewModel.createArchive(name, description, embeddingModel) { showCreateDialog = false }
            },
        )
    }

    editTarget?.let { archive ->
        ArchiveEditorDialog(
            title = stringResource(R.string.screen_archives_edit_title),
            confirmLabel = stringResource(R.string.action_save),
            initialName = archive.name,
            initialDescription = archive.description.orEmpty(),
            initialEmbeddingModel = archive.embeddingConfig?.embeddingModel.orEmpty(),
            onDismiss = { editTarget = null },
            onConfirm = { name, description, _ ->
                viewModel.updateArchive(archive.id, name, description) { editTarget = null }
            },
        )
    }

    deleteTarget?.let { archive ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.screen_archives_delete_title),
            message = stringResource(R.string.screen_archives_delete_confirm, archive.name),
            confirmText = stringResource(R.string.action_delete),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                viewModel.deleteArchive(archive.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            destructive = true,
        )
    }

    attachTarget?.let { archive ->
        AgentPickerDialog(
            title = stringResource(R.string.screen_archives_attach_title),
            emptyMessage = stringResource(R.string.screen_archives_no_available_agents),
            agents = (uiState as? UiState.Success)?.data?.let { viewModel.getAvailableAgentsForArchive() }.orEmpty(),
            onDismiss = { attachTarget = null },
            onSelect = { agent ->
                viewModel.attachArchiveToAgent(archive.id, agent.id) {
                    attachTarget = null
                }
            },
        )
    }

    detachTarget?.let { (archive, agent) ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.screen_archives_detach_title),
            message = stringResource(R.string.screen_archives_detach_confirm, archive.name, agent.name),
            confirmText = stringResource(R.string.action_remove),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                viewModel.detachArchiveFromAgent(archive.id, agent.id)
                detachTarget = null
            },
            onDismiss = { detachTarget = null },
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
private fun ArchiveCard(
    archive: Archive,
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
                    Text(archive.name, style = MaterialTheme.typography.titleMedium)
                    archive.description?.takeIf { it.isNotBlank() }?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(LettaIcons.Edit, contentDescription = stringResource(R.string.screen_archives_edit_title))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(LettaIcons.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                archive.vectorDbProvider?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                archive.embeddingConfig?.embeddingModel?.let { AssistChip(onClick = {}, label = { Text(it) }) }
            }
        }
    }
}

@Composable
private fun ArchiveDetailDialog(
    archive: Archive,
    attachedAgents: List<Agent>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onAttachAgent: () -> Unit,
    onDetachAgent: (Agent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(archive.name, fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.screen_archives_id_label, archive.id), style = MaterialTheme.typography.bodySmall)
                archive.description?.let { Text(stringResource(R.string.screen_archives_description_label, it), style = MaterialTheme.typography.bodySmall) }
                archive.organizationId?.let { Text(stringResource(R.string.screen_archives_organization_label, it), style = MaterialTheme.typography.bodySmall) }
                archive.vectorDbProvider?.let { Text(stringResource(R.string.screen_archives_vector_provider_label, it), style = MaterialTheme.typography.bodySmall) }
                archive.embeddingConfig?.embeddingModel?.let { Text(stringResource(R.string.screen_archives_embedding_model_label, it), style = MaterialTheme.typography.bodySmall) }
                archive.createdAt?.let { Text(stringResource(R.string.screen_archives_created_label, it), style = MaterialTheme.typography.bodySmall) }
                if (attachedAgents.isNotEmpty()) {
                    Text(stringResource(R.string.screen_archives_agents_title), style = MaterialTheme.typography.labelLarge)
                    attachedAgents.forEach { agent ->
                        AssistChip(
                            onClick = { onDetachAgent(agent) },
                            label = { Text(agent.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = {
                                Icon(
                                    LettaIcons.People,
                                    contentDescription = null,
                                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    LettaIcons.LinkOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                                )
                            },
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.screen_archives_no_agents),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onAttachAgent, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                    Text(stringResource(R.string.screen_archives_attach_action))
                }
                if (archive.metadata.isNotEmpty()) {
                    Text(stringResource(R.string.screen_archival_metadata_title), style = MaterialTheme.typography.labelLarge)
                    archive.metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
                        Text("$key: ${value.toString().trim('"')}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.screen_archives_edit_title))
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
private fun AgentPickerDialog(
    title: String,
    emptyMessage: String,
    agents: List<Agent>,
    onDismiss: () -> Unit,
    onSelect: (Agent) -> Unit,
) {
    var selected by remember { mutableStateOf<Agent?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (agents.isEmpty()) {
                Text(text = emptyMessage, style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(agents, key = { it.id }) { agent ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected?.id == agent.id,
                                onClick = { selected = agent },
                            )
                            Text(
                                text = agent.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onSelect) },
                enabled = selected != null,
            ) {
                Text(stringResource(R.string.action_attach))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ArchiveEditorDialog(
    title: String,
    confirmLabel: String,
    initialName: String = "",
    initialDescription: String = "",
    initialEmbeddingModel: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var embeddingModel by remember(initialEmbeddingModel) { mutableStateOf(initialEmbeddingModel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.common_name)) }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(stringResource(R.string.common_description)) }, minLines = 2)
                OutlinedTextField(value = embeddingModel, onValueChange = { embeddingModel = it }, label = { Text(stringResource(R.string.screen_archives_embedding_model_input)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), description.trim(), embeddingModel.trim()) }, enabled = name.isNotBlank()) {
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
