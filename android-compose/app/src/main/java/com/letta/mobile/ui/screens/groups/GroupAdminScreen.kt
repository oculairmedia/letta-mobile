package com.letta.mobile.ui.screens.groups

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.letta.mobile.ui.components.ExpandableSearchField
import com.letta.mobile.ui.components.ExpandableTitleSearch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
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
import com.letta.mobile.ui.components.TextInputDialog
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.listItemSupporting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupAdminScreen(
    onNavigateBack: () -> Unit,
    viewModel: GroupAdminViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Group?>(null) }
    var deleteTarget by remember { mutableStateOf<Group?>(null) }
    var sendMessageTarget by remember { mutableStateOf<Group?>(null) }
    var resetMessagesTarget by remember { mutableStateOf<Group?>(null) }

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
                        placeholder = stringResource(R.string.screen_groups_search_hint),
                        openSearchContentDescription = stringResource(R.string.action_search),
                        closeSearchContentDescription = stringResource(R.string.action_close),
                        titleContent = { Text(stringResource(R.string.screen_groups_title)) },
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
                placeholder = stringResource(R.string.screen_groups_search_hint),
            )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(LettaIcons.Add, stringResource(R.string.screen_groups_add_title))
            }
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = viewModel::loadGroups,
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> {
                val filtered = remember(state.data.groups, state.data.searchQuery) { viewModel.getFilteredGroups() }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    if (filtered.isEmpty()) {
                        EmptyState(
                            icon = LettaIcons.ForkRight,
                            message = if (state.data.searchQuery.isBlank()) {
                                stringResource(R.string.screen_groups_empty)
                            } else {
                                stringResource(R.string.screen_groups_empty_search, state.data.searchQuery)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(filtered, key = { it.id }) { group ->
                                GroupCard(
                                    group = group,
                                    onInspect = { viewModel.inspectGroup(group.id) },
                                    onEdit = { editTarget = group },
                                    onDelete = { deleteTarget = group },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val state = (uiState as? UiState.Success)?.data
    state?.selectedGroup?.let { group ->
        GroupDetailDialog(
            group = group,
            messages = state.selectedMessages,
            onDismiss = viewModel::clearSelectedGroup,
            onEdit = {
                viewModel.clearSelectedGroup()
                editTarget = group
            },
            onSendMessage = { sendMessageTarget = group },
            onResetMessages = { resetMessagesTarget = group },
        )
    }

    if (showCreateDialog) {
        GroupEditorDialog(
            title = stringResource(R.string.screen_groups_add_title),
            confirmLabel = stringResource(R.string.action_create),
            onDismiss = { showCreateDialog = false },
            onConfirm = { description, agentIds, projectId, sharedBlockIds, hidden ->
                viewModel.createGroup(description, agentIds, projectId, sharedBlockIds, hidden) {
                    showCreateDialog = false
                }
            },
        )
    }

    editTarget?.let { group ->
        GroupEditorDialog(
            title = stringResource(R.string.screen_groups_edit_title),
            confirmLabel = stringResource(R.string.action_save),
            initialDescription = group.description,
            initialAgentIds = group.agentIds.joinToString(", "),
            initialProjectId = group.projectId.orEmpty(),
            initialSharedBlockIds = group.sharedBlockIds.joinToString(", "),
            initialHidden = group.hidden == true,
            onDismiss = { editTarget = null },
            onConfirm = { description, agentIds, projectId, sharedBlockIds, hidden ->
                viewModel.updateGroup(group.id, description, agentIds, projectId, sharedBlockIds, hidden) {
                    editTarget = null
                }
            },
        )
    }

    deleteTarget?.let { group ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.screen_groups_delete_title),
            message = stringResource(R.string.screen_groups_delete_confirm, group.id),
            confirmText = stringResource(R.string.action_delete),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                viewModel.deleteGroup(group.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            destructive = true,
        )
    }

    sendMessageTarget?.let { group ->
        TextInputDialog(
            show = true,
            title = stringResource(R.string.screen_groups_send_message_title),
            label = stringResource(R.string.screen_groups_send_message_label),
            confirmText = stringResource(R.string.action_send_message),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = { input ->
                viewModel.sendMessage(group.id, input) { sendMessageTarget = null }
            },
            onDismiss = { sendMessageTarget = null },
        )
    }

    resetMessagesTarget?.let { group ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.screen_groups_reset_messages_title),
            message = stringResource(R.string.screen_groups_reset_messages_confirm, group.id),
            confirmText = stringResource(R.string.action_reset_messages),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                viewModel.resetMessages(group.id)
                resetMessagesTarget = null
            },
            onDismiss = { resetMessagesTarget = null },
            destructive = true,
        )
    }

    state?.operationError?.let { operationError ->
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

    state?.operationMessage?.let { operationMessage ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.common_conversations),
            message = operationMessage,
            confirmText = stringResource(R.string.action_dismiss),
            dismissText = stringResource(R.string.action_dismiss),
            onConfirm = viewModel::clearOperationMessage,
            onDismiss = viewModel::clearOperationMessage,
        )
    }
}

@Composable
private fun GroupCard(
    group: Group,
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
                    Text(
                        text = group.description.ifBlank { group.id },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(group.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { showContextMenu = true }) {
                    Icon(LettaIcons.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(group.managerType) })
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.screen_groups_agents_chip, group.agentIds.size)) })
                if (group.hidden == true) {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.screen_groups_hidden_chip)) })
                }
            }
        }
    }

    ActionSheet(
        show = showContextMenu,
        onDismiss = { showContextMenu = false },
        title = group.description.ifBlank { group.id },
    ) {
        ActionSheetItem(
            text = stringResource(R.string.screen_groups_edit_title),
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
private fun GroupDetailDialog(
    group: Group,
    messages: List<LettaMessage>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onSendMessage: () -> Unit,
    onResetMessages: () -> Unit,
) {
    ConfirmDialog(
        show = true,
        title = group.id,
        confirmText = stringResource(R.string.action_close),
        dismissText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                CardGroup {
                    item(
                        headlineContent = { Text(stringResource(R.string.common_description)) },
                        supportingContent = { Text(group.description.ifBlank { stringResource(R.string.common_description) }, style = MaterialTheme.typography.listItemSupporting) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_groups_manager_type_label, "")) },
                        supportingContent = { Text(group.managerType, style = MaterialTheme.typography.listItemSupporting) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_groups_agents_label, "")) },
                        supportingContent = { Text(group.agentIds.joinToString(), style = MaterialTheme.typography.listItemSupporting) },
                    )
                    group.projectId?.let { projectId ->
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_groups_project_label, "")) },
                            supportingContent = { Text(projectId, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    if (group.sharedBlockIds.isNotEmpty()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_groups_shared_blocks_label, "")) },
                            supportingContent = { Text(group.sharedBlockIds.joinToString(), style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    group.managerAgentId?.let { managerId ->
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_groups_manager_agent_label, "")) },
                            supportingContent = { Text(managerId, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    group.templateId?.let { templateId ->
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_groups_template_label, "")) },
                            supportingContent = { Text(templateId, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    group.baseTemplateId?.let { baseTemplateId ->
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_groups_base_template_label, "")) },
                            supportingContent = { Text(baseTemplateId, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    group.deploymentId?.let { deploymentId ->
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_groups_deployment_label, "")) },
                            supportingContent = { Text(deploymentId, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    group.terminationToken?.let { token ->
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_groups_termination_label, "")) },
                            supportingContent = { Text(token, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    group.maxTurns?.let { maxTurns ->
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_groups_max_turns_label, "")) },
                            supportingContent = { Text(maxTurns.toString(), style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    group.turnsCounter?.let { turnsCounter ->
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_groups_turns_counter_label, "")) },
                            supportingContent = { Text(turnsCounter.toString(), style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onEdit) { Text(stringResource(R.string.screen_groups_edit_title)) }
                    TextButton(onClick = onSendMessage) { Text(stringResource(R.string.action_send_message)) }
                    TextButton(onClick = onResetMessages) { Text(stringResource(R.string.action_reset_messages), color = MaterialTheme.colorScheme.error) }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.screen_groups_messages_title),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (messages.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.screen_groups_messages_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(messages, key = { it.id }) { message ->
                    GroupMessageCard(message = message)
                }
            }
        }
    }
}

@Composable
private fun GroupMessageCard(message: LettaMessage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = LettaCardDefaults.listCardColors(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(message.messageType, style = MaterialTheme.typography.labelMedium)
            Text(
                text = message.toSummary(),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GroupEditorDialog(
    title: String,
    confirmLabel: String,
    initialDescription: String = "",
    initialAgentIds: String = "",
    initialProjectId: String = "",
    initialSharedBlockIds: String = "",
    initialHidden: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, Boolean) -> Unit,
) {
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var agentIds by remember(initialAgentIds) { mutableStateOf(initialAgentIds) }
    var projectId by remember(initialProjectId) { mutableStateOf(initialProjectId) }
    var sharedBlockIds by remember(initialSharedBlockIds) { mutableStateOf(initialSharedBlockIds) }
    var hidden by remember(initialHidden) { mutableStateOf(initialHidden) }

    MultiFieldInputDialog(
        show = true,
        title = title,
        confirmText = confirmLabel,
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = description.isNotBlank() && agentIds.split(',').any { it.trim().isNotEmpty() },
        onConfirm = {
            onConfirm(description.trim(), agentIds.trim(), projectId.trim(), sharedBlockIds.trim(), hidden)
        },
    ) {
        CardGroup {
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
                    FormItem(
                        label = { Text(stringResource(R.string.screen_groups_agent_ids_input)) },
                        description = { Text(stringResource(R.string.screen_groups_csv_helper)) },
                    ) {
                        OutlinedTextField(
                            value = agentIds,
                            onValueChange = { agentIds = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
            item(
                headlineContent = {
                    FormItem(label = { Text(stringResource(R.string.screen_groups_project_id_input)) }) {
                        OutlinedTextField(
                            value = projectId,
                            onValueChange = { projectId = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
            item(
                headlineContent = {
                    FormItem(
                        label = { Text(stringResource(R.string.screen_groups_shared_block_ids_input)) },
                        description = { Text(stringResource(R.string.screen_groups_csv_helper)) },
                    ) {
                        OutlinedTextField(
                            value = sharedBlockIds,
                            onValueChange = { sharedBlockIds = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
            item(
                headlineContent = {
                    FormItem(label = { Text(stringResource(R.string.screen_groups_hidden_input)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = hidden, onCheckedChange = { hidden = it })
                        }
                    }
                },
            )
        }
    }
}

private fun LettaMessage.toSummary(): String = when (this) {
    is UserMessage -> content
    is AssistantMessage -> content
    is SystemMessage -> content
    is ReasoningMessage -> reasoning
    is ToolCallMessage -> effectiveToolCalls.joinToString { it.name ?: it.effectiveId.ifBlank { "Tool call" } }
    is ToolReturnMessage -> toolReturn.funcResponse ?: toolReturn.status
    else -> id
}
