package com.letta.mobile.feature.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.letta.mobile.feature.chat.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.LettaSearchBar
import com.letta.mobile.ui.components.highlightSearchMatches
import com.letta.mobile.ui.components.rememberSearchHighlightColors
import com.letta.mobile.ui.components.searchResultSnippet
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.util.formatRelativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
@androidx.annotation.VisibleForTesting
internal fun ConversationPickerSheet(
    agentId: String,
    currentConversationId: String?,
    onDismiss: () -> Unit,
    onConversationSelected: (ConversationSwitchAction) -> Unit,
    onNewConversation: (ConversationSwitchAction) -> Unit,
    viewModel: ConversationPickerViewModel = hiltViewModel(),
) {
    val conversationRepo = viewModel.conversationRepository
    val conversations by conversationRepo.getConversations(agentId).collectAsStateWithLifecycle(emptyList())
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val isSelectionMode = selectedIds.isNotEmpty()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDismissingForAction by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val selectionColors = MaterialTheme.customColors

    fun dismissThen(action: () -> Unit) {
        if (isDismissingForAction) return
        isDismissingForAction = true
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) {
                action()
                viewModel.clearSelection()
                onDismiss()
            } else {
                isDismissingForAction = false
            }
        }
    }

    LaunchedEffect(agentId) {
        conversationRepo.refreshConversations(agentId)
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = {
            viewModel.clearSelection()
            onDismiss()
        },
    ) {
        Column(modifier = Modifier.padding(16.dp).testTag(AgentScaffoldTestTags.CONVERSATION_PICKER_SHEET)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelectionMode) {
                    Text(
                        text = "${selectedIds.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            LettaIcons.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.common_conversations),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    dismissThen {
                        onNewConversation(ConversationSwitchAction.NewConversation)
                    }
                },
                enabled = !isDismissingForAction,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(LettaIcons.Add, contentDescription = null, modifier = Modifier.size(LettaIconSizing.Toolbar))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.screen_conversations_new_action))
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (conversations.isEmpty()) {
                Text(
                    text = stringResource(R.string.screen_conversations_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        val isActive = conversation.id == currentConversationId
                        val isChecked = conversation.id in selectedIds
                        val containerColor = when {
                            isChecked -> selectionColors.selectionContainer
                            isActive -> MaterialTheme.colorScheme.primaryContainer
                            else -> CardDefaults.cardColors().containerColor
                        }
                        ConversationMenuItem(
                            conversation = conversation,
                            containerColor = containerColor,
                            leadingIcon = {
                                Icon(
                                    LettaIcons.ChatOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(LettaIconSizing.Toolbar),
                                )
                            },
                            trailingIcon = if (isChecked) {
                                {
                                    Icon(
                                        LettaIcons.CheckCircle,
                                        contentDescription = stringResource(R.string.common_selected),
                                        modifier = Modifier.size(LettaIconSizing.Toolbar),
                                        tint = selectionColors.selectionIndicator,
                                    )
                                }
                            } else null,
                            onClick = {
                                if (isSelectionMode) {
                                    viewModel.toggleSelection(conversation.id)
                                } else {
                                    dismissThen {
                                        onConversationSelected(
                                            ConversationSwitchAction.ExistingConversation(conversation.id)
                                        )
                                    }
                                }
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleSelection(conversation.id)
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    ConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.screen_conversations_dialog_delete_title),
        message = pluralStringResource(
            R.plurals.screen_conversations_delete_message,
            selectedIds.size,
            selectedIds.size,
        ),
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = {
            showDeleteConfirm = false
            viewModel.deleteSelected(
                agentId = agentId,
                activeConversationId = currentConversationId,
                onActiveDeleted = {
                    dismissThen {
                        onNewConversation(ConversationSwitchAction.NewConversation)
                    }
                },
            )
        },
        onDismiss = { showDeleteConfirm = false },
        destructive = true,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun AgentPickerSheet(
    agents: List<Agent>,
    currentAgentId: String,
    favoriteAgentId: String?,
    pinnedAgentIds: Set<String>,
    onDismiss: () -> Unit,
    onTogglePinned: (Agent) -> Unit,
    onAgentSelected: (Agent) -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isDismissingForAction by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val filteredAgents = remember(agents, searchQuery) {
        if (searchQuery.isBlank()) {
            agents
        } else {
            val query = searchQuery.trim()
            agents.filter { agent ->
                agent.name.contains(query, ignoreCase = true) ||
                    agent.id.value.contains(query, ignoreCase = true) ||
                    agent.description?.contains(query, ignoreCase = true) == true ||
                    agent.model?.contains(query, ignoreCase = true) == true ||
                    agent.tags.any { it.contains(query, ignoreCase = true) }
            }
        }
    }

    fun dismissThen(action: () -> Unit) {
        if (isDismissingForAction) return
        isDismissingForAction = true
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) {
                action()
                onDismiss()
            } else {
                isDismissingForAction = false
            }
        }
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.common_agents),
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            LettaSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onClear = { searchQuery = "" },
                placeholder = stringResource(R.string.screen_agents_search_hint),
                compact = true,
                searchIconContentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AgentScaffoldTestTags.AGENT_PICKER_SEARCH_FIELD),
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredAgents.isEmpty()) {
                Text(
                    text = if (searchQuery.isBlank()) {
                        stringResource(R.string.screen_agents_empty)
                    } else {
                        stringResource(R.string.screen_agents_no_matches, searchQuery)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    items(filteredAgents, key = { it.id.value }) { agent ->
                        val isActive = agent.id.value == currentAgentId
                        val isFavorite = agent.id.value == favoriteAgentId
                        val isPinned = agent.id.value in pinnedAgentIds
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    enabled = !isDismissingForAction,
                                    onClick = {
                                        dismissThen { onAgentSelected(agent) }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onTogglePinned(agent)
                                    },
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    CardDefaults.cardColors().containerColor
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val defaultAgentName = stringResource(R.string.screen_drawer_default_agent_name)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = agent.name.ifBlank { defaultAgentName },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val detail = agent.model
                                        ?: agent.description?.takeIf { it.isNotBlank() }
                                        ?: agent.id.value.take(12)
                                    Text(
                                        text = detail,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (isFavorite) {
                                    Icon(
                                        LettaIcons.Star,
                                        contentDescription = stringResource(R.string.screen_agents_favorite_indicator),
                                        modifier = Modifier
                                            .padding(start = 8.dp)
                                            .size(LettaIconSizing.Inline),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (isPinned) {
                                    Icon(
                                        LettaIcons.Pin,
                                        contentDescription = stringResource(R.string.screen_agents_pinned_indicator),
                                        modifier = Modifier
                                            .padding(start = 8.dp)
                                            .size(LettaIconSizing.Inline),
                                        tint = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                if (isActive) {
                                    Icon(
                                        LettaIcons.CheckCircle,
                                        contentDescription = stringResource(R.string.screen_agents_current_indicator),
                                        modifier = Modifier.size(LettaIconSizing.Toolbar),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

internal fun sortAgentsForPicker(
    agents: List<Agent>,
    favoriteAgentId: String?,
    pinnedAgentIds: Set<String>,
): List<Agent> = agents
    .distinctBy { it.id.value }
    .sortedWith(
        compareByDescending<Agent> { it.id.value == favoriteAgentId }
            .thenByDescending { it.id.value in pinnedAgentIds }
            .thenBy { it.name.lowercase(Locale.getDefault()) },
    )

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationMenuItem(
    conversation: Conversation,
    containerColor: androidx.compose.ui.graphics.Color,
    leadingIcon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leadingIcon()
            val fallbackTitle = stringResource(R.string.screen_conversations_unnamed)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.summary?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                conversationActivityText(conversation)?.let { timeText ->
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailingIcon?.invoke()
        }
    }
}

@Composable
internal fun conversationActivityText(conversation: Conversation): String? {
    val timestamp = conversation.lastMessageAt ?: conversation.createdAt ?: return null
    val relative = formatRelativeTime(timestamp).takeIf { it.isNotBlank() } ?: return null
    return if (conversation.lastMessageAt != null) {
        stringResource(R.string.screen_conversations_last_activity_format, relative)
    } else {
        stringResource(R.string.screen_conversations_created_format, relative)
    }
}

sealed interface ConversationSwitchAction {
    val conversationId: String?

    data object NewConversation : ConversationSwitchAction {
        override val conversationId: String? = null
    }

    data class ExistingConversation(override val conversationId: String) : ConversationSwitchAction
}

@HiltViewModel
class ConversationPickerViewModel @Inject constructor(
    val conversationRepository: ConversationRepository,
) : ViewModel() {

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    fun toggleSelection(id: String) {
        _selectedIds.value = _selectedIds.value.let { current ->
            if (id in current) current - id else current + id
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun deleteSelected(agentId: String, onActiveDeleted: () -> Unit = {}, activeConversationId: String? = null) {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        _selectedIds.value = emptySet()
        viewModelScope.launch {
            var deletedActive = false
            for (id in ids) {
                try {
                    conversationRepository.deleteConversation(id, agentId)
                    if (id == activeConversationId) deletedActive = true
                } catch (_: Exception) { /* individual failures are handled by the repository's rollback */ }
            }
            if (deletedActive) onActiveDeleted()
        }
    }
}

/**
 * letta-mobile: minimised context-window indicator in the agent drawer. The
 * full breakdown (per-component token counts, memory counts, raw numerator/
 * denominator) was visual debt for the common case where the user only
 * wants to know "how full is the window?". The condensed form keeps the
 * existing token-progress flow but reduces it to: title, percent, slim
 * progress bar, and refresh control. Error / unavailable states still
 * render their hint text inline so degraded states aren't silent.
 */
@Composable
internal fun ContextWindowCard(
    state: ContextWindowUiState,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    LettaIcons.Database,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.screen_chat_context_window_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.maxTokens > 0) {
                    Text(
                        text = stringResource(
                            R.string.screen_chat_context_window_percent,
                            state.usagePercent,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                        Icon(
                            LettaIcons.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            if (state.maxTokens > 0) {
                val progress = (state.currentTokens.toFloat() / state.maxTokens.toFloat()).coerceIn(0f, 1f)
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.tertiaryContainer,
                )
            } else if (state.error != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.screen_chat_context_window_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ContextMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

internal fun formatDrawerNumber(value: Int): String = String.format(Locale.US, "%,d", value)

internal fun ClientModeLocationUiState.displayLabel(): String? {
    val path = currentPath ?: lastRequestedPath ?: defaultPath ?: return null
    return path.trimEnd('/').substringAfterLast('/').ifBlank { path }
}

@Composable
internal fun contrastDrawerItemColors() =
    NavigationDrawerItemDefaults.colors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedContainerColor = LettaCardDefaults.listContainerColor,
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
        unselectedTextColor = MaterialTheme.colorScheme.onSurface,
        selectedBadgeColor = MaterialTheme.colorScheme.onPrimaryContainer,
        unselectedBadgeColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

@androidx.annotation.VisibleForTesting
@Composable
internal fun DrawerContent(
    agentName: String,
    agentId: String,
    activeBackendLabel: String?,
    contextWindow: ContextWindowUiState,
    chatMode: String,
    onChatModeSelected: (String) -> Unit,
    isClientModeEnabled: Boolean = false,
    clientModeLocation: ClientModeLocationUiState = ClientModeLocationUiState(),
    onOpenLocationPicker: () -> Unit = {},
    conversations: List<Conversation>,
    currentConversationId: String?,
    onNewConversation: () -> Unit,
    onConversationSelected: (String) -> Unit,
    onEditAgent: () -> Unit,
    onResetMessages: () -> Unit = {},
    onRefreshContextWindow: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            // letta-mobile: pad past the bottom system bar so the agent-id
            // tail (and any new items below it) stay visible. Previous layout
            // clipped the last element under the gesture nav.
            .navigationBarsPadding()
    ) {
        // letta-mobile-7lyb: Inline the Edit Agent action as a trailing
        // IconButton on the agent header. Removes the giant full-width
        // NavigationDrawerItem that previously occupied ~64dp for a single
        // tap target.
        val drawerDefaultAgentName = stringResource(R.string.screen_drawer_default_agent_name)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                LettaIcons.Agent,
                contentDescription = stringResource(R.string.screen_drawer_agent_icon_description),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = agentName.ifBlank { drawerDefaultAgentName },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onEditAgent,
                modifier = Modifier.testTag(AgentScaffoldTestTags.DRAWER_EDIT_AGENT),
            ) {
                Icon(
                    LettaIcons.Edit,
                    contentDescription = stringResource(R.string.screen_drawer_edit_agent),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        // letta-mobile: replace the running message-count line with a small
        // backend-identity indicator. Shows which Letta server the agent is
        // talking to (matches the active-backend pill on the top-level
        // surfaces). Falls back to a placeholder when no active config is
        // configured so the slot doesn't collapse.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                LettaIcons.Storage,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = activeBackendLabel
                    ?: stringResource(R.string.screen_drawer_backend_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val drawerItemColors = contrastDrawerItemColors()
        Spacer(modifier = Modifier.height(16.dp))
        if (isClientModeEnabled) {
            AssistChip(
                onClick = onOpenLocationPicker,
                leadingIcon = { Icon(LettaIcons.Storage, contentDescription = null) },
                label = {
                    Text(
                        text = clientModeLocation.displayLabel()
                            ?: stringResource(R.string.screen_chat_client_location_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        ContextWindowCard(
            state = contextWindow,
            onRefresh = onRefreshContextWindow,
        )
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // letta-mobile-7lyb: Compact chat-mode selector. The three modes
        // previously rendered as stacked NavigationDrawerItems (~144dp tall);
        // a SingleChoiceSegmentedButtonRow gives the same affordance in one
        // ~48dp row with Material3-native selection visuals.
        Text(
            text = stringResource(R.string.screen_drawer_chat_mode_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        // letta-mobile-w3dl: pair each mode value (transport key sent to the
        // viewmodel) with its localized label resource. Keeps the chat-mode
        // string identifier stable for analytics/storage while honoring the
        // user's locale for the visible button text.
        val chatModes = listOf(
            "simple" to R.string.screen_drawer_chat_mode_simple,
            "interactive" to R.string.screen_drawer_chat_mode_interactive,
            "debug" to R.string.screen_drawer_chat_mode_debug,
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            chatModes.forEachIndexed { index, (mode, labelRes) ->
                SegmentedButton(
                    selected = chatMode == mode,
                    onClick = { onChatModeSelected(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = chatModes.size),
                    modifier = Modifier.testTag(AgentScaffoldTestTags.drawerChatMode(mode)),
                    // letta-mobile: suppress SegmentedButton's default check
                    // affordance — the container highlight already conveys
                    // selection and the icon adds visual noise in a tight
                    // three-item row.
                    icon = {},
                    label = {
                        Text(
                            stringResource(labelRes),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(R.string.common_conversations),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        NavigationDrawerItem(
            icon = { Icon(LettaIcons.Add, contentDescription = null) },
            label = { Text(stringResource(R.string.screen_conversations_new_action)) },
            selected = currentConversationId == null,
            onClick = onNewConversation,
            colors = drawerItemColors,
        )
        if (conversations.isEmpty()) {
            Text(
                text = stringResource(R.string.screen_conversations_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
            // letta-mobile: cap to the 4 most-recent conversations in the
            // drawer for now — the full list is reachable via the dedicated
            // conversation picker. Keeps the drawer scannable on small
            // screens and stops it from running past the bottom system bar.
            conversations.take(4).forEach { conversation ->
                val isActive = conversation.id == currentConversationId
                ConversationMenuItem(
                    conversation = conversation,
                    containerColor = if (isActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        LettaCardDefaults.listContainerColor
                    },
                    leadingIcon = {
                        Icon(
                            if (isActive) LettaIcons.CheckCircle else LettaIcons.ChatOutline,
                            contentDescription = null,
                            modifier = Modifier.size(LettaIconSizing.Toolbar),
                            tint = if (isActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    onClick = { onConversationSelected(conversation.id) },
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        NavigationDrawerItem(
            icon = {
                Icon(
                    LettaIcons.Delete,
                    contentDescription = stringResource(R.string.screen_drawer_reset_icon_description),
                )
            },
            label = { Text(stringResource(R.string.action_reset_messages)) },
            selected = false,
            onClick = onResetMessages,
            colors = drawerItemColors,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = agentId.take(12) + "\u2026",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
