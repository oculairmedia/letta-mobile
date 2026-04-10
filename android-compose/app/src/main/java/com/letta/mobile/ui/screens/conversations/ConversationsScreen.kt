package com.letta.mobile.ui.screens.conversations

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.ConversationInspectorMessage
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.components.TextInputDialog
import com.letta.mobile.util.formatRelativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onNavigateToChat: (agentId: String, conversationId: String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAgentList: () -> Unit,
    onNavigateToTemplates: () -> Unit = {},
    onNavigateToBlocks: () -> Unit = {},
    onNavigateToIdentities: () -> Unit = {},
    onNavigateToSchedules: () -> Unit = {},
    onNavigateToRuns: () -> Unit = {},
    onNavigateToJobs: () -> Unit = {},
    onNavigateToMcp: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    viewModel: ConversationsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAgentPickerDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.common_conversations)) },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onNavigateToAgentList) {
                        Icon(Icons.Default.AccountCircle, stringResource(R.string.common_agents))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, stringResource(R.string.common_settings))
                    }
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_templates)) },
                            onClick = { showOverflowMenu = false; onNavigateToTemplates() },
                            leadingIcon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_blocks)) },
                            onClick = { showOverflowMenu = false; onNavigateToBlocks() },
                            leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_identities)) },
                            onClick = { showOverflowMenu = false; onNavigateToIdentities() },
                            leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_schedules)) },
                            onClick = { showOverflowMenu = false; onNavigateToSchedules() },
                            leadingIcon = { Icon(Icons.Default.AccessTime, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_runs)) },
                            onClick = { showOverflowMenu = false; onNavigateToRuns() },
                            leadingIcon = { Icon(Icons.Default.ChatBubbleOutline, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_jobs)) },
                            onClick = { showOverflowMenu = false; onNavigateToJobs() },
                            leadingIcon = { Icon(Icons.Default.AccessTime, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_mcp_servers)) },
                            onClick = { showOverflowMenu = false; onNavigateToMcp() },
                            leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_about_title)) },
                            onClick = { showOverflowMenu = false; onNavigateToAbout() },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAgentPickerDialog = true }) {
                Icon(Icons.Default.Add, stringResource(R.string.screen_conversations_new_action))
            }
        }
    ) { paddingValues ->
        when {
            uiState.isLoading && uiState.conversations.isEmpty() -> ShimmerCard(modifier = Modifier.padding(16.dp))
            uiState.error != null && uiState.conversations.isEmpty() -> ErrorContent(
                message = uiState.error!!,
                onRetry = { viewModel.loadConversations() },
                modifier = Modifier.padding(paddingValues)
            )
            else -> ConversationsContent(
                state = uiState,
                onConversationClick = { display ->
                    onNavigateToChat(display.conversation.agentId, display.conversation.id)
                },
                onOpenAdmin = { display -> viewModel.openConversationAdmin(display) },
                onDeleteConversation = { viewModel.deleteConversation(it.conversation.id) },
                onRenameConversation = { display, newName ->
                    viewModel.renameConversation(display.conversation.id, display.conversation.agentId, newName)
                },
                onForkConversation = { display ->
                    viewModel.forkConversation(display.conversation.id, display.conversation.agentId) { newConvId ->
                        onNavigateToChat(display.conversation.agentId, newConvId)
                    }
                },
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.padding(paddingValues)
            )
        }
    }

    if (showAgentPickerDialog) {
        val agents = uiState.agents
        AgentPickerDialog(
            agents = agents,
            onDismiss = { showAgentPickerDialog = false },
            onAgentSelected = { agentId ->
                viewModel.createConversation(agentId) { conversationId ->
                    showAgentPickerDialog = false
                    onNavigateToChat(agentId, conversationId)
                }
            }
        )
    }

    uiState.selectedConversation?.let { display ->
        ConversationAdminDialog(
            display = display,
            recompilePreview = uiState.recompilePreview,
            onDismiss = { viewModel.closeConversationAdmin() },
            onRename = { newName -> viewModel.renameConversation(display.conversation.id, display.conversation.agentId, newName) },
            onToggleArchived = { archived -> viewModel.setConversationArchived(display, archived) },
            onFork = { viewModel.forkConversation(display.conversation.id, display.conversation.agentId) { } },
            onCancelRuns = { viewModel.cancelConversationRuns(display) },
            inspectorMessages = uiState.inspectorMessages,
            inspectorError = uiState.inspectorError,
            onRecompile = { viewModel.recompileConversation(display) },
            onDelete = { viewModel.deleteConversation(display.conversation.id) },
        )
    }
}

@Composable
private fun ConversationsContent(
    state: ConversationsUiState,
    onConversationClick: (ConversationDisplay) -> Unit,
    onOpenAdmin: (ConversationDisplay) -> Unit,
    onDeleteConversation: (ConversationDisplay) -> Unit,
    onRenameConversation: (ConversationDisplay, String) -> Unit,
    onForkConversation: (ConversationDisplay) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.conversations.isEmpty()) {
        EmptyState(
            icon = Icons.Default.ChatBubbleOutline,
            message = stringResource(R.string.screen_conversations_empty),
            modifier = modifier.fillMaxSize()
        )
    } else {
        @OptIn(ExperimentalMaterial3Api::class)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier.fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = state.conversations,
                    key = { it.conversation.id }
                ) { display ->
                    ConversationCard(
                        display = display,
                        onClick = { onConversationClick(display) },
                    onOpenAdmin = { onOpenAdmin(display) },
                    onDelete = { onDeleteConversation(display) },
                    onRename = { newName -> onRenameConversation(display, newName) },
                    onFork = { onForkConversation(display) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationCard(
    display: ConversationDisplay,
    onClick: () -> Unit,
    onOpenAdmin: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onFork: () -> Unit,
    modifier: Modifier = Modifier
) {
    val conversation = display.conversation
    var showContextMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val title = conversation.summary
        ?: "${display.agentName} \u00B7 ${formatRelativeTime(conversation.createdAt)}"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showContextMenu = true
                }
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = "Agent",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = display.agentName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val timeText = formatRelativeTime(conversation.lastMessageAt ?: conversation.createdAt)
            if (timeText.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

    }

    ActionSheet(
        show = showContextMenu,
        onDismiss = { showContextMenu = false },
        title = title,
    ) {
        ActionSheetItem(
            text = stringResource(R.string.screen_conversations_admin_details),
            icon = Icons.AutoMirrored.Filled.ManageSearch,
            onClick = { showContextMenu = false; onOpenAdmin() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_rename),
            icon = Icons.Default.Edit,
            onClick = { showContextMenu = false; showRenameDialog = true },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_fork),
            icon = Icons.Default.ForkRight,
            onClick = { showContextMenu = false; onFork() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_delete),
            icon = Icons.Default.Delete,
            onClick = { showContextMenu = false; showDeleteDialog = true },
            destructive = true,
        )
    }

    ConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.screen_conversations_dialog_delete_title),
        message = stringResource(R.string.screen_conversations_dialog_delete_confirm),
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showDeleteDialog = false; onDelete() },
        onDismiss = { showDeleteDialog = false },
        destructive = true,
    )

    TextInputDialog(
        show = showRenameDialog,
        title = stringResource(R.string.screen_conversations_dialog_rename_title),
        label = stringResource(R.string.common_name),
        confirmText = stringResource(R.string.action_save),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showRenameDialog = false; onRename(it) },
        onDismiss = { showRenameDialog = false },
        initialValue = conversation.summary ?: "",
    )
}

@Composable
private fun ConversationAdminDialog(
    display: ConversationDisplay,
    recompilePreview: String?,
    inspectorMessages: List<ConversationInspectorMessage>,
    inspectorError: String?,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onToggleArchived: (Boolean) -> Unit,
    onFork: () -> Unit,
    onCancelRuns: () -> Unit,
    onRecompile: () -> Unit,
    onDelete: () -> Unit,
) {
    var renameText by remember(display.conversation.id) { mutableStateOf(display.conversation.summary ?: "") }
    val conversation = display.conversation

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_conversations_admin_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(conversation.id, style = MaterialTheme.typography.titleSmall)
                Text(display.agentName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = if (conversation.archived == true) stringResource(R.string.screen_conversations_archived_label)
                    else stringResource(R.string.screen_conversations_active_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                conversation.createdAt?.let {
                    Text(stringResource(R.string.screen_conversations_created_label, formatRelativeTime(it)))
                }
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.common_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { if (renameText.isNotBlank()) onRename(renameText) }) {
                        Text(stringResource(R.string.action_save))
                    }
                    TextButton(onClick = { onToggleArchived(conversation.archived != true) }) {
                        Text(
                            if (conversation.archived == true) stringResource(R.string.screen_conversations_unarchive_action)
                            else stringResource(R.string.screen_conversations_archive_action)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onFork) { Text(stringResource(R.string.action_fork)) }
                    TextButton(onClick = onCancelRuns) { Text(stringResource(R.string.screen_conversations_cancel_runs_action)) }
                    TextButton(onClick = onRecompile) { Text(stringResource(R.string.screen_conversations_recompile_action)) }
                }
                Text(
                    text = stringResource(R.string.screen_conversations_message_inspector_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!inspectorError.isNullOrBlank()) {
                    Text(
                        text = inspectorError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (inspectorMessages.isEmpty()) {
                    Text(
                        text = stringResource(R.string.screen_conversations_message_inspector_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(inspectorMessages, key = { it.id }) { message ->
                            ConversationInspectorCard(message = message)
                        }
                    }
                }
                if (!recompilePreview.isNullOrBlank()) {
                    Text(stringResource(R.string.screen_conversations_recompile_preview_title), style = MaterialTheme.typography.labelLarge)
                    Text(recompilePreview, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun ConversationInspectorCard(message: ConversationInspectorMessage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message.messageType,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = message.id,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = message.summary,
                style = MaterialTheme.typography.bodyMedium,
            )
            message.detailLines.forEach { (label, value) ->
                Text(
                    text = "$label: $value",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "Error",
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun AgentPickerDialog(
    agents: List<Agent>,
    onDismiss: () -> Unit,
    onAgentSelected: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_conversations_dialog_select_agent_title)) },
        text = {
            if (agents.isEmpty()) {
                Text(stringResource(R.string.screen_conversations_dialog_no_agents))
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.height(300.dp),
                ) {
                    items(agents, key = { it.id }) { agent ->
                        Card(
                            onClick = { onAgentSelected(agent.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = agent.name,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                agent.model?.let { model ->
                                    Text(
                                        text = model,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
