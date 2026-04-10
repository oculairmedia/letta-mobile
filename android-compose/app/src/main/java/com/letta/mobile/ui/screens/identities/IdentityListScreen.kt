package com.letta.mobile.ui.screens.identities

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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityUpdateParams
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityListScreen(
    onNavigateBack: () -> Unit,
    viewModel: IdentityListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Identity?>(null) }
    var editTarget by remember { mutableStateOf<Identity?>(null) }
    var attachTarget by remember { mutableStateOf<Identity?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_identities_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            androidx.compose.material3.FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, stringResource(R.string.screen_identities_add_title))
            }
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadIdentities() },
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> {
                val filteredIdentities = remember(state.data.identities, state.data.searchQuery) {
                    viewModel.getFilteredIdentities()
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
                        placeholder = { Text(stringResource(R.string.screen_identities_search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                    )

                    if (filteredIdentities.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.AccountCircle,
                            message = if (state.data.searchQuery.isBlank()) {
                                stringResource(R.string.screen_identities_empty)
                            } else {
                                stringResource(R.string.screen_identities_empty_search, state.data.searchQuery)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(filteredIdentities, key = { it.id }) { identity ->
                                IdentityCard(
                                    identity = identity,
                                    onInspect = { viewModel.inspectIdentity(identity.id) },
                                    onEdit = { editTarget = identity },
                                    onDelete = { deleteTarget = identity },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val selectedIdentity = (uiState as? UiState.Success)?.data?.selectedIdentity
    selectedIdentity?.let { identity ->
        IdentityDetailDialog(
            identity = identity,
            knownAgents = (uiState as? UiState.Success)?.data?.knownAgents.orEmpty(),
            onDismiss = { viewModel.clearSelectedIdentity() },
            onEdit = {
                viewModel.clearSelectedIdentity()
                editTarget = identity
            },
            onAttachAgent = {
                viewModel.clearSelectedIdentity()
                attachTarget = identity
            },
            onDetachAgent = { agentId -> viewModel.detachIdentity(agentId, identity.id) },
        )
    }

    if (showCreateDialog) {
        IdentityEditorDialog(
            title = stringResource(R.string.screen_identities_add_title),
            confirmLabel = stringResource(R.string.action_create),
            onDismiss = { showCreateDialog = false },
            onConfirm = { identifierKey, name, identityType, blockIds ->
                viewModel.createIdentity(
                    IdentityCreateParams(
                        identifierKey = identifierKey,
                        name = name,
                        identityType = identityType,
                        blockIds = blockIds,
                    )
                ) {
                    showCreateDialog = false
                }
            },
        )
    }

    editTarget?.let { identity ->
        IdentityEditorDialog(
            title = stringResource(R.string.screen_identities_edit_title),
            confirmLabel = stringResource(R.string.action_save),
            initialIdentifierKey = identity.identifierKey,
            initialName = identity.name,
            initialIdentityType = identity.identityType,
            initialBlockIds = identity.blockIds,
            onDismiss = { editTarget = null },
            onConfirm = { identifierKey, name, identityType, blockIds ->
                viewModel.updateIdentity(
                    identityId = identity.id,
                    params = IdentityUpdateParams(
                        identifierKey = identifierKey,
                        name = name,
                        identityType = identityType,
                        blockIds = blockIds,
                    )
                ) {
                    editTarget = null
                }
            },
        )
    }

    attachTarget?.let { identity ->
        AgentAttachDialog(
            agents = (uiState as? UiState.Success)?.data?.knownAgents.orEmpty().filter { it.id !in identity.agentIds },
            onDismiss = { attachTarget = null },
            onAttach = { agentId ->
                viewModel.attachIdentity(agentId = agentId, identityId = identity.id) {
                    attachTarget = null
                }
            },
        )
    }

    deleteTarget?.let { identity ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.screen_identities_delete_title),
            message = stringResource(R.string.screen_identities_delete_confirm, identity.name),
            confirmText = stringResource(R.string.action_delete),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                viewModel.deleteIdentity(identity.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            destructive = true,
        )
    }

    val operationError = (uiState as? UiState.Success)?.data?.operationError
    if (operationError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearOperationError() },
            title = { Text(stringResource(R.string.common_error)) },
            text = { Text(operationError) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearOperationError() }) {
                    Text(stringResource(R.string.action_dismiss))
                }
            },
        )
    }
}

@Composable
private fun IdentityCard(
    identity: Identity,
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
                    Text(identity.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = identity.identifierKey,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, stringResource(R.string.action_edit))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, stringResource(R.string.action_delete))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(identity.identityType) })
                if (identity.properties.isNotEmpty()) {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.screen_identities_properties_chip, identity.properties.size)) })
                }
            }
        }
    }
}

@Composable
private fun IdentityDetailDialog(
    identity: Identity,
    knownAgents: List<Agent>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onAttachAgent: () -> Unit,
    onDetachAgent: (String) -> Unit,
) {
    val attachedAgentsById = remember(identity.id, knownAgents) {
        knownAgents.associateBy { it.id }
    }
    val attachedAgents = remember(identity.id, identity.agentIds, knownAgents) {
        identity.agentIds.mapNotNull { attachedAgentsById[it] }
    }
    val unresolvedAgentIds = remember(identity.id, identity.agentIds, knownAgents) {
        identity.agentIds.filterNot(attachedAgentsById::containsKey)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(identity.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.screen_identities_identifier_label, identity.identifierKey), fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.screen_identities_type_label, identity.identityType))
                identity.projectId?.let { Text(stringResource(R.string.screen_identities_project_label, it)) }
                identity.organizationId?.let { Text(stringResource(R.string.screen_identities_organization_label, it)) }
                Text(stringResource(R.string.screen_identities_agent_count_label, identity.agentIds.size))
                Text(stringResource(R.string.screen_identities_block_count_label, identity.blockIds.size))
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.common_agents), style = MaterialTheme.typography.labelLarge)
                if (identity.agentIds.isEmpty()) {
                    Text(
                        text = stringResource(R.string.screen_identities_no_linked_agents),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    attachedAgents.forEach { agent ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(agent.name, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onDetachAgent(agent.id) }) {
                                Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    unresolvedAgentIds.forEach { agentId ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(agentId, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            TextButton(onClick = { onDetachAgent(agentId) }) {
                                Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.screen_identities_blocks_title), style = MaterialTheme.typography.labelLarge)
                if (identity.blockIds.isEmpty()) {
                    Text(
                        text = stringResource(R.string.screen_identities_no_linked_blocks),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    identity.blockIds.forEach { blockId ->
                        Text(
                            text = blockId,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = stringResource(R.string.screen_identities_blocks_manage_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (identity.properties.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.screen_identities_properties_title), style = MaterialTheme.typography.labelLarge)
                    identity.properties.forEach { property ->
                        Text(
                            text = "${property.key}: ${property.value}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAttachAgent) {
                    Text(stringResource(R.string.screen_identities_attach_agent_action))
                }
                TextButton(onClick = onEdit) {
                    Text(stringResource(R.string.action_edit))
                }
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
private fun IdentityEditorDialog(
    title: String,
    confirmLabel: String,
    initialIdentifierKey: String = "",
    initialName: String = "",
    initialIdentityType: String = "user",
    initialBlockIds: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (identifierKey: String, name: String, identityType: String, blockIds: List<String>) -> Unit,
) {
    var identifierKey by remember(initialIdentifierKey) { mutableStateOf(initialIdentifierKey) }
    var name by remember(initialName) { mutableStateOf(initialName) }
    var identityType by remember(initialIdentityType) { mutableStateOf(initialIdentityType) }
    var blockIdsText by remember(initialBlockIds) { mutableStateOf(initialBlockIds.joinToString(", ")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = identifierKey,
                    onValueChange = { identifierKey = it },
                    label = { Text(stringResource(R.string.screen_identities_identifier_key_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.common_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = identityType,
                    onValueChange = { identityType = it },
                    label = { Text(stringResource(R.string.screen_identities_type_input_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = blockIdsText,
                    onValueChange = { blockIdsText = it },
                    label = { Text(stringResource(R.string.screen_identities_block_ids_label)) },
                    supportingText = { Text(stringResource(R.string.screen_identities_block_ids_helper)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        identifierKey.trim(),
                        name.trim(),
                        identityType.trim(),
                        blockIdsText.parseCommaSeparatedValues(),
                    )
                },
                enabled = identifierKey.isNotBlank() && name.isNotBlank() && identityType.isNotBlank(),
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

@Composable
private fun AgentAttachDialog(
    agents: List<Agent>,
    onDismiss: () -> Unit,
    onAttach: (String) -> Unit,
) {
    var selection by remember(agents) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_identities_attach_agent_action)) },
        text = {
            if (agents.isEmpty()) {
                Text(
                    text = stringResource(R.string.screen_identities_no_available_agents),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.height(240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(agents, key = { it.id }) { agent ->
                        TextButton(
                            onClick = {
                                selection = if (selection == agent.id) null else agent.id
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(agent.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = agent.id,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                androidx.compose.material3.Checkbox(
                                    checked = selection == agent.id,
                                    onCheckedChange = null,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selection?.let(onAttach) },
                enabled = selection != null,
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

private fun String.parseCommaSeparatedValues(): List<String> {
    return split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() }
}
