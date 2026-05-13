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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.letta.mobile.ui.components.ExpandableTitleSearch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Archive
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
import com.letta.mobile.ui.theme.listItemSupporting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveAdminScreen(
    onNavigateBack: () -> Unit,
    viewModel: ArchiveAdminViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Archive?>(null) }
    var deleteTarget by remember { mutableStateOf<Archive?>(null) }
    var attachTarget by remember { mutableStateOf<Archive?>(null) }
    var detachTarget by remember { mutableStateOf<Pair<Archive, Agent>?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    ExpandableTitleSearch(
                        query = (uiState as? UiState.Success)?.data?.searchQuery.orEmpty(),
                        onQueryChange = viewModel::updateSearchQuery,
                        onClear = { viewModel.updateSearchQuery("") },
                        expanded = isSearchExpanded,
                        onExpandedChange = { isSearchExpanded = it },
                        placeholder = stringResource(R.string.screen_archives_search_hint),
                        openSearchContentDescription = stringResource(R.string.action_search),
                        closeSearchContentDescription = stringResource(R.string.action_close),
                        titleContent = {
                            Text(stringResource(R.string.screen_archives_title))
                        },
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
                viewModel.attachArchiveToAgent(archive.id, agent.id.value) {
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
                viewModel.detachArchiveFromAgent(archive.id, agent.id.value)
                detachTarget = null
            },
            onDismiss = { detachTarget = null },
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
private fun ArchiveCard(
    archive: Archive,
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
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(archive.name, style = MaterialTheme.typography.titleMedium)
                    archive.description?.takeIf { it.isNotBlank() }?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { showContextMenu = true }) {
                    Icon(LettaIcons.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                archive.vectorDbProvider?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                archive.embeddingConfig?.embeddingModel?.let { AssistChip(onClick = {}, label = { Text(it) }) }
            }
        }
    }

    ActionSheet(
        show = showContextMenu,
        onDismiss = { showContextMenu = false },
        title = archive.name,
    ) {
        ActionSheetItem(
            text = stringResource(R.string.screen_archives_edit_title),
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
private fun ArchiveDetailDialog(
    archive: Archive,
    attachedAgents: List<Agent>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onAttachAgent: () -> Unit,
    onDetachAgent: (Agent) -> Unit,
) {
    ConfirmDialog(
        show = true,
        title = archive.name,
        confirmText = stringResource(R.string.action_close),
        dismissText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.screen_archives_id_label, "")) },
                    supportingContent = { Text(archive.id, style = MaterialTheme.typography.listItemSupporting) },
                )
                archive.description?.let { desc ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_archives_description_label, "")) },
                        supportingContent = { Text(desc, style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
                archive.organizationId?.let { orgId ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_archives_organization_label, "")) },
                        supportingContent = { Text(orgId, style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
                archive.vectorDbProvider?.let { provider ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_archives_vector_provider_label, "")) },
                        supportingContent = { Text(provider, style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
                archive.embeddingConfig?.embeddingModel?.let { model ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_archives_embedding_model_label, "")) },
                        supportingContent = { Text(model, style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
                archive.createdAt?.let { created ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_archives_created_label, "")) },
                        supportingContent = { Text(created, style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
            }

            CardGroup(title = { Text(stringResource(R.string.screen_archives_agents_title)) }) {
                if (attachedAgents.isEmpty()) {
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.screen_archives_no_agents),
                                style = MaterialTheme.typography.listItemSupporting,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                } else {
                    attachedAgents.forEach { agent ->
                        item(
                            headlineContent = { Text(agent.name, style = MaterialTheme.typography.listItemSupporting) },
                            trailingContent = {
                                TextButton(onClick = { onDetachAgent(agent) }) {
                                    Text(stringResource(R.string.action_remove_attachment), color = MaterialTheme.colorScheme.error)
                                }
                            },
                        )
                    }
                }
            }

            if (archive.metadata.isNotEmpty()) {
                CardGroup(title = { Text(stringResource(R.string.screen_archival_metadata_title)) }) {
                    archive.metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
                        item(
                            headlineContent = { Text(key, style = MaterialTheme.typography.listItemSupporting) },
                            supportingContent = { Text(value.toString().trim('"'), style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAttachAgent) {
                    Text(stringResource(R.string.screen_archives_attach_action))
                }
                TextButton(onClick = onEdit) {
                    Text(stringResource(R.string.screen_archives_edit_title))
                }
            }
        }
    }
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

    MultiFieldInputDialog(
        show = true,
        title = title,
        confirmText = stringResource(R.string.action_attach),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = selected != null,
        onConfirm = { selected?.let(onSelect) },
    ) {
        if (agents.isEmpty()) {
            Text(text = emptyMessage, style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(agents, key = { it.id.value }) { agent ->
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
    }
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

    MultiFieldInputDialog(
        show = true,
        title = title,
        confirmText = confirmLabel,
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name.trim(), description.trim(), embeddingModel.trim()) },
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
                    FormItem(label = { Text(stringResource(R.string.screen_archives_embedding_model_input)) }) {
                        OutlinedTextField(
                            value = embeddingModel,
                            onValueChange = { embeddingModel = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
        }
    }
}
