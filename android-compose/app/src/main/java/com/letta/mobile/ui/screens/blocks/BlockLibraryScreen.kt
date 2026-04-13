package com.letta.mobile.ui.screens.blocks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.customColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockLibraryScreen(
    onNavigateBack: () -> Unit,
    viewModel: BlockLibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val isSelectionMode = selectedIds.isNotEmpty()

    var inspectTarget by remember { mutableStateOf<Block?>(null) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Block?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var manageAgentsTarget by remember { mutableStateOf<Block?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text(stringResource(R.string.screen_blocks_selected_count, selectedIds.size))
                    } else {
                        ExpandableTitleSearch(
                            query = (uiState as? UiState.Success)?.data?.searchQuery.orEmpty(),
                            onQueryChange = viewModel::updateSearchQuery,
                            onClear = { viewModel.updateSearchQuery("") },
                            expanded = isSearchExpanded,
                            onExpandedChange = { isSearchExpanded = it },
                            placeholder = stringResource(R.string.screen_blocks_search_hint),
                            openSearchContentDescription = stringResource(R.string.action_search),
                            closeSearchContentDescription = stringResource(R.string.action_close),
                            titleContent = { Text(stringResource(R.string.screen_blocks_title)) },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSelectionMode) viewModel.clearSelection() else onNavigateBack()
                    }) {
                        Icon(
                            if (isSelectionMode) LettaIcons.Close else LettaIcons.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                LettaIcons.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(LettaIcons.Add, stringResource(R.string.screen_blocks_create_title))
                }
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
                    if (filteredBlocks.isEmpty()) {
                        EmptyState(
                            icon = LettaIcons.Search,
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
                                    agents = state.data.agentsByBlock[block.id] ?: emptyList(),
                                    isSelected = block.id in selectedIds,
                                    isSelectionMode = isSelectionMode,
                                    onTap = {
                                        if (isSelectionMode) {
                                            viewModel.toggleSelection(block.id)
                                        } else {
                                            inspectTarget = block
                                        }
                                    },
                                    onLongPress = { viewModel.toggleSelection(block.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Bulk delete confirmation
    ConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.screen_blocks_delete_title),
        message = stringResource(R.string.screen_blocks_delete_selected_confirm, selectedIds.size),
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = {
            showDeleteConfirm = false
            viewModel.deleteSelected {}
        },
        onDismiss = { showDeleteConfirm = false },
        destructive = true,
    )

    inspectTarget?.let { block ->
        val successData = (uiState as? UiState.Success)?.data
        val agents = successData?.agentsByBlock?.get(block.id) ?: emptyList()
        BlockDetailDialog(
            block = block,
            agents = agents,
            onEdit = if (block.readOnly == true) null else {
                {
                    inspectTarget = null
                    editTarget = block
                }
            },
            onManageAgents = {
                inspectTarget = null
                manageAgentsTarget = block
            },
            onDismiss = { inspectTarget = null },
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
                    inspectTarget = null
                }
            },
        )
    }

    manageAgentsTarget?.let { block ->
        val successData = (uiState as? UiState.Success)?.data
        val attachedAgentIds = successData?.agentsByBlock?.get(block.id)?.map { it.id }?.toSet() ?: emptySet()
        val allAgents = successData?.allAgents ?: emptyList()

        AgentMultiSelectDialog(
            agents = allAgents,
            selectedAgentIds = attachedAgentIds,
            onDismiss = { manageAgentsTarget = null },
            onConfirm = { newSelection ->
                viewModel.updateBlockAgents(block.id, newSelection) {
                    manageAgentsTarget = null
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun BlockLibraryCard(
    block: Block,
    agents: List<Agent>,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val selectionColors = MaterialTheme.customColors
    val containerColor = if (isSelected) {
        selectionColors.selectionContainer
    } else {
        CardDefaults.cardColors().containerColor
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            ),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
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
                if (isSelected) {
                    Icon(
                        LettaIcons.CheckCircle,
                        contentDescription = "Selected",
                        modifier = Modifier.size(LettaIconSizing.Toolbar),
                        tint = selectionColors.selectionIndicator,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
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

            if (agents.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    agents.forEach { agent ->
                        AssistChip(
                            onClick = {},
                            label = { Text(agent.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = {
                                Icon(
                                    LettaIcons.People,
                                    contentDescription = null,
                                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockDetailDialog(
    block: Block,
    agents: List<Agent>,
    onEdit: (() -> Unit)?,
    onManageAgents: () -> Unit,
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
                if (agents.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.common_agents),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    agents.forEach { agent ->
                        Text(
                            text = agent.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row {
                onEdit?.let {
                    TextButton(onClick = it) {
                        Text(stringResource(R.string.action_edit))
                    }
                }
                TextButton(onClick = onManageAgents) {
                    Text(stringResource(R.string.screen_blocks_manage_agents))
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

@Composable
private fun AgentMultiSelectDialog(
    agents: List<Agent>,
    selectedAgentIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var selection by remember(selectedAgentIds) { mutableStateOf(selectedAgentIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_blocks_manage_agents)) },
        text = {
            if (agents.isEmpty()) {
                Text(
                    text = stringResource(R.string.screen_blocks_no_available_agents),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 360.dp),
                ) {
                    items(agents, key = { it.id }) { agent ->
                        val isChecked = agent.id in selection
                        TextButton(
                            onClick = {
                                selection = if (isChecked) selection - agent.id else selection + agent.id
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = null,
                                )
                                Spacer(modifier = Modifier.size(8.dp))
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
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selection) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
