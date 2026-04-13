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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
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
import com.letta.mobile.ui.components.DateSeparator
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ExpandableTitleSearch
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.components.TextInputDialog
import com.letta.mobile.ui.theme.dialogSectionHeading
import com.letta.mobile.ui.theme.listItemHeadline
import com.letta.mobile.ui.theme.listItemMetadata
import com.letta.mobile.ui.theme.listItemMetadataMonospace
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.theme.sectionTitle
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.util.formatRelativeTime
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.navigation.agentAvatarSharedElementKey
import com.letta.mobile.ui.navigation.optionalSharedElement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onNavigateToChat: (agentId: String, conversationId: String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAgentList: () -> Unit,
    onNavigateToTemplates: () -> Unit = {},
    onNavigateToArchives: () -> Unit = {},
    onNavigateToFolders: () -> Unit = {},
    onNavigateToGroups: () -> Unit = {},
    onNavigateToProviders: () -> Unit = {},
    onNavigateToBlocks: () -> Unit = {},
    onNavigateToIdentities: () -> Unit = {},
    onNavigateToSchedules: () -> Unit = {},
    onNavigateToRuns: () -> Unit = {},
    onNavigateToJobs: () -> Unit = {},
    onNavigateToMessageBatches: () -> Unit = {},
    onNavigateToMcp: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToBotSettings: () -> Unit = {},
    onNavigateToProjects: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ConversationsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAgentPickerDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    ExpandableTitleSearch(
                        query = uiState.searchQuery,
                        onQueryChange = viewModel::updateSearchQuery,
                        onClear = { viewModel.updateSearchQuery("") },
                        expanded = isSearchExpanded,
                        onExpandedChange = { isSearchExpanded = it },
                        placeholder = stringResource(R.string.screen_conversations_search_hint),
                        titleContent = {
                            Text(stringResource(R.string.common_conversations))
                        },
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
                actions = {
                    IconButton(onClick = onNavigateToAgentList) {
                        Icon(LettaIcons.AccountCircle, stringResource(R.string.common_agents))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(LettaIcons.Settings, stringResource(R.string.common_settings))
                    }
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(LettaIcons.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_templates)) },
                            onClick = { showOverflowMenu = false; onNavigateToTemplates() },
                            leadingIcon = { Icon(LettaIcons.Dashboard, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_archives)) },
                            onClick = { showOverflowMenu = false; onNavigateToArchives() },
                            leadingIcon = { Icon(LettaIcons.Storage, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_folders)) },
                            onClick = { showOverflowMenu = false; onNavigateToFolders() },
                            leadingIcon = { Icon(LettaIcons.ManageSearch, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_groups)) },
                            onClick = { showOverflowMenu = false; onNavigateToGroups() },
                            leadingIcon = { Icon(LettaIcons.ForkRight, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_providers)) },
                            onClick = { showOverflowMenu = false; onNavigateToProviders() },
                            leadingIcon = { Icon(LettaIcons.Cloud, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_blocks)) },
                            onClick = { showOverflowMenu = false; onNavigateToBlocks() },
                            leadingIcon = { Icon(LettaIcons.Storage, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_identities)) },
                            onClick = { showOverflowMenu = false; onNavigateToIdentities() },
                            leadingIcon = { Icon(LettaIcons.AccountCircle, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_schedules)) },
                            onClick = { showOverflowMenu = false; onNavigateToSchedules() },
                            leadingIcon = { Icon(LettaIcons.AccessTime, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_runs)) },
                            onClick = { showOverflowMenu = false; onNavigateToRuns() },
                            leadingIcon = { Icon(LettaIcons.ChatOutline, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_jobs)) },
                            onClick = { showOverflowMenu = false; onNavigateToJobs() },
                            leadingIcon = { Icon(LettaIcons.AccessTime, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_message_batches)) },
                            onClick = { showOverflowMenu = false; onNavigateToMessageBatches() },
                            leadingIcon = { Icon(LettaIcons.ChatOutline, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_nav_mcp_servers)) },
                            onClick = { showOverflowMenu = false; onNavigateToMcp() },
                            leadingIcon = { Icon(LettaIcons.Cloud, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Bot Settings") },
                            onClick = { showOverflowMenu = false; onNavigateToBotSettings() },
                            leadingIcon = { Icon(LettaIcons.Agent, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_projects_title)) },
                            onClick = { showOverflowMenu = false; onNavigateToProjects() },
                            leadingIcon = { Icon(LettaIcons.Apps, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_about_title)) },
                            onClick = { showOverflowMenu = false; onNavigateToAbout() },
                            leadingIcon = { Icon(LettaIcons.Info, contentDescription = null) },
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAgentPickerDialog = true }) {
                Icon(LettaIcons.Add, stringResource(R.string.screen_conversations_new_action))
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
            else -> {
                val filteredConversations = remember(uiState.conversations, uiState.searchQuery) {
                    viewModel.getFilteredConversations()
                }
                ConversationsContent(
                    conversations = filteredConversations,
                    isRefreshing = uiState.isRefreshing,
                    isSearchActive = uiState.searchQuery.isNotBlank(),
                    onConversationClick = { display ->
                        onNavigateToChat(display.conversation.agentId, display.conversation.id)
                    },
                onOpenAdmin = { display -> viewModel.openConversationAdmin(display) },
                onDeleteConversation = { viewModel.deleteConversation(it.conversation.id) },
                onRenameConversation = { display, newName ->
                    viewModel.renameConversation(display.conversation.id, display.conversation.agentId, newName)
                },
                onTogglePinned = viewModel::toggleConversationPinned,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationsContent(
    conversations: List<ConversationDisplay>,
    isRefreshing: Boolean,
    isSearchActive: Boolean,
    onConversationClick: (ConversationDisplay) -> Unit,
    onOpenAdmin: (ConversationDisplay) -> Unit,
    onDeleteConversation: (ConversationDisplay) -> Unit,
    onRenameConversation: (ConversationDisplay, String) -> Unit,
    onTogglePinned: (ConversationDisplay) -> Unit,
    onForkConversation: (ConversationDisplay) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (conversations.isEmpty()) {
        EmptyState(
            icon = LettaIcons.ChatOutline,
            message = stringResource(
                if (isSearchActive) R.string.screen_conversations_search_empty
                else R.string.screen_conversations_empty
            ),
            modifier = modifier.fillMaxSize()
        )
    } else {
        @OptIn(ExperimentalMaterial3Api::class)
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier.fillMaxSize(),
        ) {
            val sections = remember(conversations) {
                buildConversationSections(conversations)
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sections.forEach { section ->
                    item(key = section.key) {
                        when {
                            section.isPinned -> ConversationPinnedHeader()
                            section.date != null -> DateSeparator(date = section.date)
                        }
                    }
                    items(
                        items = section.items,
                        key = { it.conversation.id }
                    ) { display ->
                        ConversationCard(
                            display = display,
                            onClick = { onConversationClick(display) },
                            onOpenAdmin = { onOpenAdmin(display) },
                            onDelete = { onDeleteConversation(display) },
                            onRename = { newName -> onRenameConversation(display, newName) },
                            onTogglePinned = { onTogglePinned(display) },
                            onFork = { onForkConversation(display) },
                        )
                    }
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
    onTogglePinned: () -> Unit,
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
                style = MaterialTheme.typography.listItemHeadline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (display.isPinned) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = LettaIcons.Star,
                        contentDescription = "Pinned",
                        modifier = Modifier.size(LettaIconSizing.Inline),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Pinned",
                        style = MaterialTheme.typography.listItemMetadata,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = LettaIcons.Agent,
                    contentDescription = "Agent",
                    modifier = Modifier
                        .size(LettaIconSizing.Inline)
                        .optionalSharedElement(agentAvatarSharedElementKey(conversation.agentId)),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = display.agentName,
                    style = MaterialTheme.typography.listItemSupporting,
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
                    style = MaterialTheme.typography.listItemMetadata,
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
            icon = LettaIcons.ManageSearch,
            onClick = { showContextMenu = false; onOpenAdmin() },
        )
        ActionSheetItem(
            text = if (display.isPinned) "Unpin" else "Pin",
            icon = LettaIcons.Star,
            onClick = { showContextMenu = false; onTogglePinned() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_rename),
            icon = LettaIcons.Edit,
            onClick = { showContextMenu = false; showRenameDialog = true },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_fork),
            icon = LettaIcons.ForkRight,
            onClick = { showContextMenu = false; onFork() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_delete),
            icon = LettaIcons.Delete,
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
private fun ConversationPinnedHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = LettaIcons.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(LettaIconSizing.Inline),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Pinned",
            style = MaterialTheme.typography.sectionTitle,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private data class ConversationSection(
    val key: String,
    val date: LocalDate? = null,
    val isPinned: Boolean = false,
    val items: List<ConversationDisplay>,
)

private fun buildConversationSections(conversations: List<ConversationDisplay>): List<ConversationSection> {
    if (conversations.isEmpty()) return emptyList()

    val pinned = conversations.filter { it.isPinned }
    val regular = conversations.filterNot { it.isPinned }

    val sections = mutableListOf<ConversationSection>()
    if (pinned.isNotEmpty()) {
        sections += ConversationSection(
            key = "pinned",
            isPinned = true,
            items = pinned,
        )
    }

    regular
        .groupBy { conversationLocalDate(it.conversation) }
        .forEach { (date, items) ->
            sections += ConversationSection(
                key = "date_$date",
                date = date,
                items = items,
            )
        }

    return sections
}

private fun conversationLocalDate(conversation: com.letta.mobile.data.model.Conversation): LocalDate {
    val timestamp = conversation.lastMessageAt ?: conversation.createdAt ?: Instant.EPOCH.toString()
    return runCatching {
        Instant.parse(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    }.getOrDefault(LocalDate.now())
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
                Text(
                    conversation.id,
                    style = MaterialTheme.typography.listItemMetadataMonospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    display.agentName,
                    style = MaterialTheme.typography.listItemSupporting,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (conversation.archived == true) stringResource(R.string.screen_conversations_archived_label)
                    else stringResource(R.string.screen_conversations_active_label),
                    style = MaterialTheme.typography.listItemMetadata,
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
                    style = MaterialTheme.typography.dialogSectionHeading,
                )
                if (!inspectorError.isNullOrBlank()) {
                    Text(
                        text = inspectorError,
                        style = MaterialTheme.typography.listItemSupporting,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (inspectorMessages.isEmpty()) {
                    Text(
                        text = stringResource(R.string.screen_conversations_message_inspector_empty),
                        style = MaterialTheme.typography.listItemSupporting,
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
                    Text(stringResource(R.string.screen_conversations_recompile_preview_title), style = MaterialTheme.typography.dialogSectionHeading)
                    Text(recompilePreview, style = MaterialTheme.typography.listItemSupporting)
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
                    style = MaterialTheme.typography.listItemMetadata,
                )
                Text(
                    text = message.id,
                    style = MaterialTheme.typography.listItemMetadataMonospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = message.summary,
                style = MaterialTheme.typography.listItemSupporting,
            )
            message.detailLines.forEach { (label, value) ->
                Text(
                    text = "$label: $value",
                    style = MaterialTheme.typography.listItemSupporting,
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
            imageVector = LettaIcons.Error,
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
                                    style = MaterialTheme.typography.listItemHeadline,
                                )
                                agent.model?.let { model ->
                                    Text(
                                        text = model,
                                        style = MaterialTheme.typography.listItemSupporting,
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
