package com.letta.mobile.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.saveable.rememberSaveable
import com.letta.mobile.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.util.formatRelativeTime
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.ConnectionState
import com.letta.mobile.ui.components.LettaSearchBar
import com.letta.mobile.ui.components.ConnectionStatusBanner
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.components.Accordions
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.highlightSearchMatches
import com.letta.mobile.ui.components.rememberSearchHighlightColors
import com.letta.mobile.ui.components.searchResultSnippet

import com.letta.mobile.util.ConnectivityMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.ui.theme.listItemHeadline
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale

object AgentScaffoldTestTags {
    const val MENU_BUTTON = "agent_scaffold_menu_button"
    const val DRAWER_CONTENT = "agent_scaffold_drawer_content"
    const val CONVERSATION_PICKER_TRIGGER = "agent_scaffold_conversation_picker_trigger"
    const val CONVERSATION_PICKER_SHEET = "agent_scaffold_conversation_picker_sheet"
    const val PROJECT_BUG_FAB = "agent_scaffold_project_bug_fab"
    const val PROJECT_BUG_REPORT_SHEET = "agent_scaffold_project_bug_report_sheet"
    const val CHAT_SCREEN_CONTENT = "agent_scaffold_chat_screen_content"
    const val PROJECT_CONTEXT_CARD = "agent_scaffold_project_context_card"
    const val PROJECT_AGENTS_CARD = "agent_scaffold_project_agents_card"
    const val PROJECT_BRIEF_CARD = "agent_scaffold_project_brief_card"
    const val PROJECT_BUG_SUMMARY_CARD = "agent_scaffold_project_bug_summary_card"
    const val CHAT_SEARCH_FIELD = "agent_scaffold_chat_search_field"
    const val AGENT_PICKER_SEARCH_FIELD = "agent_scaffold_agent_picker_search_field"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScaffold(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: (String) -> Unit,
    onNavigateToArchival: ((String) -> Unit)? = null,
    onNavigateToTools: (() -> Unit)? = null,
    onSwitchConversation: ((String, String?, String?) -> Unit)? = null,
    conversationRepository: ConversationRepository? = null,
    viewModel: AdminChatViewModel = hiltViewModel()
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showBugReportSheet by remember { mutableStateOf(false) }
    var showAgentSwitcher by remember { mutableStateOf(false) }
    var isChatSearchExpanded by rememberSaveable { mutableStateOf(false) }
    val chatSearchFocusRequester = remember { FocusRequester() }
    val chatBackground by viewModel.chatBackground.collectAsStateWithLifecycle()
    val availableAgents by viewModel.availableAgents.collectAsStateWithLifecycle()
    var chatMode by rememberSaveable { mutableStateOf("interactive") }
    val drawerConversationRepo = conversationRepository
        ?: hiltViewModel<ConversationPickerViewModel>().conversationRepository
    val drawerConversations by drawerConversationRepo.getConversations(viewModel.agentId)
        .collectAsStateWithLifecycle(emptyList())

    val agentName = uiState.agentName
    val agentId = viewModel.agentId
    val conversationId = viewModel.conversationId
    val projectContext = viewModel.projectContext
    val screenTitle = projectContext?.name ?: agentName.ifBlank { stringResource(R.string.screen_chat_title) }
    val switchableAgents = remember(availableAgents, agentId, agentName) {
        if (availableAgents.any { it.id == agentId }) {
            availableAgents
        } else {
            listOf(Agent(id = agentId, name = agentName.ifBlank { "Agent" })) + availableAgents
        }
    }
    // Compact top bar — was LargeFlexibleTopAppBar (~152dp expanded), now a
    // standard TopAppBar at ~64dp. The chat surface is content-dense and
    // doesn't benefit from a big collapsing hero header; the agent name fits
    // fine in a single compact title row.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = !drawerState.isOpen && (isChatSearchExpanded || uiState.isSearchActive)) {
        isChatSearchExpanded = false
        viewModel.clearChatSearch()
    }

    LaunchedEffect(isChatSearchExpanded) {
        if (isChatSearchExpanded) {
            chatSearchFocusRequester.requestFocus()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    viewModel.onScreenPaused()
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    viewModel.onScreenResumed()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    agentName = agentName,
                    agentId = agentId,
                    messageCount = uiState.messages.size,
                    contextWindow = uiState.contextWindow,
                    chatMode = chatMode,
                    onChatModeSelected = { chatMode = it },
                    isClientModeEnabled = uiState.isClientModeEnabled,
                    clientModeLocation = uiState.clientModeLocation,
                    onOpenLocationPicker = {
                        scope.launch { drawerState.close() }
                        viewModel.openClientModeLocationPicker()
                    },
                    conversations = drawerConversations,
                    currentConversationId = conversationId,
                    onNewConversation = {
                        scope.launch { drawerState.close() }
                        onSwitchConversation?.invoke(agentId, null, agentName.takeIf { it.isNotBlank() })
                    },
                    onConversationSelected = { selectedConversationId ->
                        scope.launch { drawerState.close() }
                        onSwitchConversation?.invoke(agentId, selectedConversationId, agentName.takeIf { it.isNotBlank() })
                    },
                    onEditAgent = {
                        scope.launch { drawerState.close() }
                        onNavigateToSettings(agentId)
                    },
                    onResetMessages = {
                        scope.launch { drawerState.close() }
                        viewModel.resetMessages()
                    },
                    onRefreshContextWindow = viewModel::refreshContextWindow,
                    onClose = { scope.launch { drawerState.close() } },
                    modifier = Modifier.testTag(AgentScaffoldTestTags.DRAWER_CONTENT),
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
            topBar = {
                TopAppBar(
                    title = {
                        if (isChatSearchExpanded || uiState.isSearchActive) {
                            LettaSearchBar(
                                query = uiState.searchQuery,
                                onQueryChange = viewModel::updateChatSearchQuery,
                                onClear = viewModel::clearChatSearch,
                                placeholder = stringResource(R.string.screen_conversations_search_hint),
                                compact = true,
                                searchIconContentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(chatSearchFocusRequester)
                                    .testTag(AgentScaffoldTestTags.CHAT_SEARCH_FIELD),
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(AgentScaffoldTestTags.CONVERSATION_PICKER_TRIGGER)
                                    .clickable {
                                        viewModel.refreshAvailableAgents()
                                        showAgentSwitcher = true
                                    }
                                    .padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = agentName.ifBlank { screenTitle },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Icon(
                                    LettaIcons.ArrowDropDown,
                                    contentDescription = "Switch agent",
                                    modifier = Modifier.size(LettaIconSizing.Inline),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    colors = LettaTopBarDefaults.topAppBarColors(),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (isChatSearchExpanded || uiState.isSearchActive) {
                                isChatSearchExpanded = false
                                viewModel.clearChatSearch()
                            } else {
                                isChatSearchExpanded = true
                            }
                        }) {
                            Icon(
                                if (isChatSearchExpanded || uiState.isSearchActive) LettaIcons.Clear else LettaIcons.Search,
                                contentDescription = stringResource(
                                    if (isChatSearchExpanded || uiState.isSearchActive) R.string.action_close else R.string.action_search
                                ),
                            )
                        }
                        IconButton(onClick = {
                            viewModel.refreshContextWindow()
                            scope.launch {
                                drawerState.open()
                                runCatching { drawerConversationRepo.refreshConversations(agentId) }
                            }
                        }, modifier = Modifier.testTag(AgentScaffoldTestTags.MENU_BUTTON)) {
                            Icon(LettaIcons.Menu, "Menu")
                        }
                    }
                )
            },
            floatingActionButton = {
                if (projectContext != null) {
                    FloatingActionButton(
                        onClick = { showBugReportSheet = true },
                        modifier = Modifier.testTag(AgentScaffoldTestTags.PROJECT_BUG_FAB),
                    ) {
                        Icon(LettaIcons.Error, contentDescription = stringResource(R.string.screen_project_bug_report_open))
                    }
                }
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
            ) {
                projectContext?.let { project ->
                    ProjectContextCard(project = project, modifier = Modifier.testTag(AgentScaffoldTestTags.PROJECT_CONTEXT_CARD))
                    ProjectAgentsCard(
                        state = uiState.projectAgents,
                        onRetry = viewModel::loadProjectAgents,
                        modifier = Modifier.testTag(AgentScaffoldTestTags.PROJECT_AGENTS_CARD),
                    )
                    ProjectBriefCard(
                        brief = uiState.projectBrief,
                        onRetry = viewModel::loadProjectBrief,
                        onSaveSection = viewModel::saveProjectBriefSection,
                        modifier = Modifier.testTag(AgentScaffoldTestTags.PROJECT_BRIEF_CARD),
                    )
                    ProjectBugReportSummaryCard(
                        state = uiState.bugReports,
                        onCreateReport = { showBugReportSheet = true },
                        modifier = Modifier.testTag(AgentScaffoldTestTags.PROJECT_BUG_SUMMARY_CARD),
                    )
                }
                if (uiState.isSearchActive) {
                    ChatSearchResultsContent(
                        searchQuery = uiState.searchQuery,
                        results = uiState.searchResults,
                        isSearching = uiState.isSearching,
                        conversations = drawerConversations,
                        currentConversationId = conversationId,
                        onResultClick = { result ->
                            isChatSearchExpanded = false
                            viewModel.clearChatSearch()
                            result.conversationId?.let { targetConversationId ->
                                onSwitchConversation?.invoke(agentId, targetConversationId, agentName.takeIf { it.isNotBlank() })
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag(AgentScaffoldTestTags.CHAT_SCREEN_CONTENT),
                    )
                } else {
                    ChatScreen(
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag(AgentScaffoldTestTags.CHAT_SCREEN_CONTENT),
                        chatBackground = chatBackground,
                        chatMode = chatMode,
                        onBugCommand = { showBugReportSheet = true },
                        viewModel = viewModel,
                    )
                }
            }
        }
    }

    if (showAgentSwitcher) {
        AgentPickerSheet(
            agents = switchableAgents,
            currentAgentId = agentId,
            onDismiss = { showAgentSwitcher = false },
            onAgentSelected = { selectedAgent ->
                showAgentSwitcher = false
                if (selectedAgent.id != agentId) {
                    onSwitchConversation?.invoke(selectedAgent.id, null, selectedAgent.name.takeIf { it.isNotBlank() })
                }
            },
        )
    }

    if (showBugReportSheet && projectContext != null) {
        ProjectBugReportSheet(
            state = uiState.bugReports,
            onDismiss = { showBugReportSheet = false },
            onSubmit = {
                viewModel.submitStructuredBugReport(it)
                showBugReportSheet = false
            },
        )
    }
}

@Composable
private fun ChatSearchResultsContent(
    searchQuery: String,
    results: List<ParsedSearchMessage>,
    isSearching: Boolean,
    conversations: List<Conversation>,
    currentConversationId: String?,
    onResultClick: (ParsedSearchMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightColors = rememberSearchHighlightColors()
    val conversationsById = remember(conversations) { conversations.associateBy { it.id } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "chat-search-header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.screen_home_search_messages_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }

        if (!isSearching && results.isEmpty()) {
            item(key = "chat-search-empty") {
                Text(
                    text = stringResource(R.string.screen_home_search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                )
            }
        }

        itemsIndexed(
            items = results,
            key = { index, result -> chatSearchResultKey(result, index) },
        ) { _, result ->
            val conversation = result.conversationId?.let(conversationsById::get)
            val isCurrentConversation = result.conversationId != null && result.conversationId == currentConversationId
            val conversationScope = when {
                isCurrentConversation -> "Current conversation"
                result.conversationId != null -> "Previous conversation"
                else -> "Conversation unknown"
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onResultClick(result) },
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            LettaIcons.ChatOutline,
                            contentDescription = null,
                            tint = if (isCurrentConversation) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = conversationScope,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCurrentConversation) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            },
                        )
                        result.date?.let(::formatRelativeTime)?.takeIf { it.isNotBlank() }?.let { timeText ->
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    conversation?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        text = highlightSearchMatches(
                            searchResultSnippet(result.content.orEmpty(), searchQuery),
                            searchQuery,
                            highlightColors,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@androidx.annotation.VisibleForTesting
internal fun chatSearchResultKey(result: ParsedSearchMessage, index: Int): String {
    val identity = result.messageId
        ?: result.conversationId?.let { conversationId ->
            "$conversationId-${result.content.hashCode()}"
        }
        ?: result.content.hashCode().toString()
    return "chat-search-$identity-$index"
}

@androidx.annotation.VisibleForTesting
@Composable
internal fun ProjectAgentsCard(
    state: ProjectAgentsUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = LettaCardDefaults.listCardColors(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FormItem(
                label = {
                    Text(
                        text = stringResource(R.string.screen_project_agents_title),
                        style = MaterialTheme.typography.listItemHeadline,
                    )
                },
                description = {
                    Text(stringResource(R.string.screen_project_agents_subtitle))
                },
                tail = {
                    OutlinedButton(onClick = onRetry, enabled = !state.isLoading) {
                        Text(stringResource(R.string.action_refresh))
                    }
                },
            )

            if (state.isLoading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.screen_project_agents_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.error?.let { error ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        OutlinedButton(onClick = onRetry) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }

            if (!state.isLoading && state.agents.isEmpty()) {
                Text(
                    text = stringResource(R.string.screen_project_agents_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.agents.forEach { agent ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(agent.name, style = MaterialTheme.typography.titleSmall)
                                agent.model?.let {
                                    Text(
                                        text = stringResource(R.string.screen_project_agents_model, it),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            AssistChip(
                                onClick = {},
                                label = { Text(agent.statusLabel) },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier.size(10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Card(
                                            modifier = Modifier.size(10.dp),
                                            colors = CardDefaults.cardColors(containerColor = toneColor(agent.statusTone)),
                                        ) {}
                                    }
                                },
                            )
                        }

                        agent.detail?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        agent.lastActivity?.let {
                            Text(
                                text = stringResource(
                                    R.string.screen_project_agents_last_activity,
                                    formatRelativeTime(it),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun toneColor(tone: ProjectAgentStatusTone) = when (tone) {
    ProjectAgentStatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    ProjectAgentStatusTone.Good -> MaterialTheme.colorScheme.tertiary
    ProjectAgentStatusTone.Busy -> MaterialTheme.colorScheme.primary
    ProjectAgentStatusTone.Error -> MaterialTheme.colorScheme.error
}

@androidx.annotation.VisibleForTesting
@Composable
internal fun ProjectBugReportSummaryCard(
    state: ProjectBugReportUiState,
    onCreateReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FormItem(
                label = { Text(stringResource(R.string.screen_project_bug_report_title), style = MaterialTheme.typography.listItemHeadline) },
                description = {
                    Text(stringResource(R.string.screen_project_bug_report_subtitle))
                },
                tail = {
                    OutlinedButton(onClick = onCreateReport) {
                        Text(stringResource(R.string.screen_project_bug_report_open))
                    }
                },
            )

            state.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.recentReports.take(3).forEach { report ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(report.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = stringResource(
                                R.string.screen_project_bug_report_recent_meta,
                                report.severity,
                                formatRelativeTime(report.createdAt),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectBugReportSheet(
    state: ProjectBugReportUiState,
    onDismiss: () -> Unit,
    onSubmit: (ProjectBugReportDraft) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var severity by rememberSaveable { mutableStateOf(BugSeverity.Medium) }
    var severityExpanded by remember { mutableStateOf(false) }
    var selectedTags by remember { mutableStateOf(setOf("ui", "backend", "sync")) }
    var attachments by remember { mutableStateOf(listOf<String>()) }
    var showAttachmentSheet by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AgentScaffoldTestTags.PROJECT_BUG_REPORT_SHEET)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.screen_project_bug_report_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.screen_project_bug_report_sheet_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.screen_project_bug_report_field_title)) },
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.screen_project_bug_report_field_description)) },
                minLines = 5,
            )

            ExposedDropdownMenuBox(
                expanded = severityExpanded,
                onExpandedChange = { severityExpanded = it },
            ) {
                OutlinedTextField(
                    value = severity.wireValue.replaceFirstChar { it.uppercase() },
                    onValueChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryEditable),
                    readOnly = true,
                    label = { Text(stringResource(R.string.screen_project_bug_report_field_severity)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = severityExpanded) },
                )
                DropdownMenu(
                    expanded = severityExpanded,
                    onDismissRequest = { severityExpanded = false },
                ) {
                    BugSeverity.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.wireValue.replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                severity = option
                                severityExpanded = false
                            },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.screen_project_bug_report_field_tags),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ui", "backend", "sync", "crash").forEach { tag ->
                        FilterChip(
                            selected = tag in selectedTags,
                            onClick = {
                                selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag
                            },
                            label = { Text(tag) },
                        )
                    }
                }
            }

            FormItem(
                label = { Text(stringResource(R.string.screen_project_bug_report_field_attachments)) },
                description = {
                    Text(
                        if (attachments.isEmpty()) {
                            stringResource(R.string.screen_project_bug_report_attachments_empty)
                        } else {
                            attachments.joinToString("\n")
                        }
                    )
                },
                tail = {
                    OutlinedButton(onClick = { showAttachmentSheet = true }) {
                        Text(stringResource(R.string.screen_project_bug_report_add_attachment))
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = {
                        onSubmit(
                            ProjectBugReportDraft(
                                title = title,
                                description = description,
                                severity = severity,
                                tags = selectedTags.toList().sorted().toImmutableList(),
                                attachmentReferences = attachments.toImmutableList(),
                            )
                        )
                    },
                    enabled = title.isNotBlank() && description.isNotBlank() && !state.isSubmitting,
                ) {
                    Text(stringResource(R.string.screen_project_bug_report_submit))
                }
            }
        }
    }

    ActionSheet(
        show = showAttachmentSheet,
        onDismiss = { showAttachmentSheet = false },
        title = stringResource(R.string.screen_project_bug_report_attachment_title),
    ) {
        ActionSheetItem(
            text = stringResource(R.string.screen_project_bug_report_attachment_camera),
            icon = LettaIcons.Error,
            onClick = {
                attachments = attachments + "camera://capture-${System.currentTimeMillis()}"
                showAttachmentSheet = false
            },
        )
        ActionSheetItem(
            text = stringResource(R.string.screen_project_bug_report_attachment_gallery),
            icon = LettaIcons.FileOpen,
            onClick = {
                attachments = attachments + "gallery://selection-${System.currentTimeMillis()}"
                showAttachmentSheet = false
            },
        )
        ActionSheetItem(
            text = stringResource(R.string.screen_project_bug_report_attachment_recording),
            icon = LettaIcons.Play,
            onClick = {
                attachments = attachments + "recording://screen-${System.currentTimeMillis()}"
                showAttachmentSheet = false
            },
        )
    }
}

@androidx.annotation.VisibleForTesting
@Composable
internal fun ProjectBriefCard(
    brief: ProjectBriefUiState,
    onRetry: () -> Unit,
    onSaveSection: (ProjectBriefSectionKey, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editableState = remember { mutableStateMapOf<ProjectBriefSectionKey, String>() }
    val editingState = remember { mutableStateMapOf<ProjectBriefSectionKey, Boolean>() }
    var expanded by rememberSaveable { mutableStateOf(true) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = LettaCardDefaults.listCardColors(),
    ) {
        Accordions(
            title = stringResource(R.string.screen_project_brief_title),
            subtitle = stringResource(R.string.screen_project_brief_subtitle),
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (brief.isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.screen_project_brief_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                brief.error?.let { error ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            OutlinedButton(onClick = onRetry) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                }

                if (!brief.isLoading && brief.sections.isEmpty()) {
                    Text(
                        text = stringResource(R.string.screen_project_brief_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ProjectBriefSectionKey.entries.forEach { key ->
                    val section = brief.sections[key] ?: return@forEach
                    val isEditing = editingState[key] == true
                    val draft = editableState[key] ?: section.content

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceBright,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            FormItem(
                                label = {
                                    Text(
                                        text = sectionTitleFor(key),
                                        style = MaterialTheme.typography.listItemHeadline,
                                    )
                                },
                                description = {
                                    section.updatedAt?.let {
                                        Text(
                                            text = stringResource(
                                                R.string.screen_project_brief_last_updated,
                                                formatRelativeTime(it),
                                            ),
                                        )
                                    } ?: Text(stringResource(R.string.screen_project_brief_memory_backed))
                                },
                                tail = {
                                    OutlinedButton(
                                        onClick = {
                                            if (isEditing) {
                                                onSaveSection(key, draft)
                                                editingState[key] = false
                                            } else {
                                                editableState[key] = section.content
                                                editingState[key] = true
                                            }
                                        },
                                        enabled = !brief.isSaving,
                                    ) {
                                        Text(
                                            stringResource(
                                                if (isEditing) R.string.action_save else R.string.action_edit
                                            )
                                        )
                                    }
                                },
                            )

                            if (isEditing) {
                                OutlinedTextField(
                                    value = draft,
                                    onValueChange = { editableState[key] = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = minLinesFor(key),
                                    enabled = !brief.isSaving,
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    MarkdownText(
                                        text = section.content,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun sectionTitleFor(key: ProjectBriefSectionKey): String = when (key) {
    ProjectBriefSectionKey.Description -> stringResource(R.string.screen_project_brief_description_title)
    ProjectBriefSectionKey.KeyDecisions -> stringResource(R.string.screen_project_brief_decisions_title)
    ProjectBriefSectionKey.TechStack -> stringResource(R.string.screen_project_brief_tech_stack_title)
    ProjectBriefSectionKey.ActiveGoals -> stringResource(R.string.screen_project_brief_goals_title)
    ProjectBriefSectionKey.RecentChanges -> stringResource(R.string.screen_project_brief_recent_changes_title)
}

private fun minLinesFor(key: ProjectBriefSectionKey): Int = when (key) {
    ProjectBriefSectionKey.Description -> 5
    ProjectBriefSectionKey.KeyDecisions -> 4
    ProjectBriefSectionKey.TechStack -> 3
    ProjectBriefSectionKey.ActiveGoals -> 4
    ProjectBriefSectionKey.RecentChanges -> 4
}

@androidx.annotation.VisibleForTesting
@Composable
internal fun ProjectContextCard(
    project: ProjectChatContext,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(project.identifier) { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = project.identifier,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                AssistChip(
                    onClick = { expanded = !expanded },
                    label = {
                        Text(
                            text = if (expanded) stringResource(R.string.common_hide) else stringResource(R.string.common_details),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (expanded) LettaIcons.ExpandLess else LettaIcons.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(LettaIconSizing.Inline),
                        )
                    },
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = ChatMotion.expandEnter(),
                exit = ChatMotion.expandExit(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectInfoLine(
                        label = stringResource(R.string.screen_project_chat_path_label),
                        value = project.filesystemPath,
                    )
                    ProjectInfoLine(
                        label = stringResource(R.string.screen_project_chat_git_url_label),
                        value = project.gitUrl,
                    )
                    ProjectInfoLine(
                        label = stringResource(R.string.screen_project_chat_active_agents_label),
                        value = project.activeCodingAgents,
                    )
                    ProjectInfoLine(
                        label = stringResource(R.string.screen_project_chat_last_sync_label),
                        value = project.lastSyncAt?.let(::formatRelativeTime),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectInfoLine(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
        )
        Text(
            text = value ?: stringResource(R.string.common_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
    }
}

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
                    text = "No conversations yet",
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
                                        contentDescription = "Selected",
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
        message = "Delete ${selectedIds.size} conversation${if (selectedIds.size > 1) "s" else ""}? This cannot be undone.",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentPickerSheet(
    agents: List<Agent>,
    currentAgentId: String,
    onDismiss: () -> Unit,
    onAgentSelected: (Agent) -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isDismissingForAction by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val filteredAgents = remember(agents, searchQuery) {
        if (searchQuery.isBlank()) {
            agents
        } else {
            val query = searchQuery.trim()
            agents.filter { agent ->
                agent.name.contains(query, ignoreCase = true) ||
                    agent.id.contains(query, ignoreCase = true) ||
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
                        "No agents matching \"$searchQuery\""
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
                    items(filteredAgents, key = { it.id }) { agent ->
                        val isActive = agent.id == currentAgentId
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isDismissingForAction) {
                                    dismissThen { onAgentSelected(agent) }
                                },
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = agent.name.ifBlank { "Agent" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val detail = agent.model
                                        ?: agent.description?.takeIf { it.isNotBlank() }
                                        ?: agent.id.take(12)
                                    Text(
                                        text = detail,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (isActive) {
                                    Icon(
                                        LettaIcons.CheckCircle,
                                        contentDescription = "Current agent",
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationMenuItem(
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.summary?.takeIf { it.isNotBlank() } ?: "Conversation",
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

private fun conversationActivityText(conversation: Conversation): String? {
    val timestamp = conversation.lastMessageAt ?: conversation.createdAt ?: return null
    val relative = formatRelativeTime(timestamp).takeIf { it.isNotBlank() } ?: return null
    return if (conversation.lastMessageAt != null) "Last activity $relative" else "Created $relative"
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
        val deletedActive = activeConversationId != null && activeConversationId in ids
        _selectedIds.value = emptySet()
        viewModelScope.launch {
            for (id in ids) {
                try {
                    conversationRepository.deleteConversation(id, agentId)
                } catch (_: Exception) { /* individual failures are handled by the repository's rollback */ }
            }
            if (deletedActive) onActiveDeleted()
        }
    }
}

@Composable
private fun ContextWindowCard(
    state: ContextWindowUiState,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    LettaIcons.Database,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.screen_chat_context_window_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                        Icon(
                            LettaIcons.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (state.maxTokens > 0) {
                val progress = (state.currentTokens.toFloat() / state.maxTokens.toFloat()).coerceIn(0f, 1f)
                Text(
                    text = stringResource(
                        R.string.screen_chat_context_window_usage,
                        formatDrawerNumber(state.currentTokens),
                        formatDrawerNumber(state.maxTokens),
                        state.usagePercent,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.tertiaryContainer,
                )
                Spacer(modifier = Modifier.height(10.dp))
                ContextMetricRow(
                    label = stringResource(R.string.screen_chat_context_window_messages),
                    value = stringResource(
                        R.string.screen_chat_context_window_messages_value,
                        formatDrawerNumber(state.messageTokens),
                        state.messageCount,
                    ),
                )
                ContextMetricRow(
                    label = stringResource(R.string.screen_chat_context_window_memory),
                    value = formatDrawerNumber(
                        state.coreMemoryTokens + state.externalMemoryTokens + state.summaryMemoryTokens,
                    ),
                )
                ContextMetricRow(
                    label = stringResource(R.string.screen_chat_context_window_tools),
                    value = formatDrawerNumber(state.toolTokens),
                )
                ContextMetricRow(
                    label = stringResource(R.string.screen_chat_context_window_system),
                    value = formatDrawerNumber(state.systemTokens),
                )
                Text(
                    text = stringResource(
                        R.string.screen_chat_context_window_memory_counts,
                        state.recallMemoryCount,
                        state.archivalMemoryCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else if (state.error != null) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
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
private fun ContextMetricRow(label: String, value: String) {
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

private fun formatDrawerNumber(value: Int): String = String.format(Locale.US, "%,d", value)

private fun ClientModeLocationUiState.displayLabel(): String? {
    val path = currentPath ?: lastRequestedPath ?: defaultPath ?: return null
    return path.trimEnd('/').substringAfterLast('/').ifBlank { path }
}

@Composable
private fun contrastDrawerItemColors() =
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
    messageCount: Int,
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
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                LettaIcons.Agent,
                contentDescription = "Agent",
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = agentName.ifBlank { "Agent" },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$messageCount messages",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))
        val drawerItemColors = contrastDrawerItemColors()
        NavigationDrawerItem(
            icon = { Icon(LettaIcons.Edit, contentDescription = "Edit") },
            label = { Text(stringResource(R.string.screen_drawer_edit_agent)) },
            selected = false,
            onClick = onEditAgent,
            colors = drawerItemColors,
        )

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

        Text(
            text = "Chat mode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        listOf("simple", "interactive", "debug").forEach { mode ->
            NavigationDrawerItem(
                icon = {
                    if (chatMode == mode) {
                        Icon(LettaIcons.Check, contentDescription = null)
                    } else {
                        Spacer(modifier = Modifier.size(LettaIconSizing.Toolbar))
                    }
                },
                label = { Text(mode.replaceFirstChar { it.uppercase() }) },
                selected = chatMode == mode,
                onClick = { onChatModeSelected(mode) },
                colors = drawerItemColors,
            )
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
                text = "No conversations yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
            conversations.forEach { conversation ->
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
            icon = { Icon(LettaIcons.Delete, contentDescription = "Reset") },
            label = { Text("Reset Messages") },
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
