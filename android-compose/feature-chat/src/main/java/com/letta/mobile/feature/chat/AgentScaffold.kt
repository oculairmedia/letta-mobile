package com.letta.mobile.feature.chat

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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
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
import com.letta.mobile.feature.chat.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.repository.api.IConversationRepository
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
import com.letta.mobile.ui.haptics.HapticEffects

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

internal object AgentScaffoldTestTags {
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
    const val DRAWER_EDIT_AGENT = "agent_scaffold_drawer_edit_agent"
    fun drawerChatMode(mode: String) = "agent_scaffold_drawer_chat_mode_$mode"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AgentScaffold(
    initialProjectStartAction: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: (String) -> Unit,
    onNavigateToArchival: ((String) -> Unit)? = null,
    onNavigateToTools: (() -> Unit)? = null,
    onSwitchConversation: ((String, String?, String?) -> Unit)? = null,
    viewModelKey: String? = null,
) {
    AgentScaffoldContent(
        initialProjectStartAction = initialProjectStartAction,
        onNavigateBack = onNavigateBack,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToArchival = onNavigateToArchival,
        onNavigateToTools = onNavigateToTools,
        onSwitchConversation = onSwitchConversation,
        conversationRepository = null,
        viewModel = hiltViewModel(key = viewModelKey),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun AgentScaffoldContent(
    initialProjectStartAction: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: (String) -> Unit,
    onNavigateToArchival: ((String) -> Unit)? = null,
    onNavigateToTools: (() -> Unit)? = null,
    onSwitchConversation: ((String, String?, String?) -> Unit)? = null,
    conversationRepository: IConversationRepository? = null,
    viewModel: AdminChatViewModel,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showBugReportSheet by rememberSaveable { mutableStateOf(initialProjectStartAction == ProjectChatStartAction.BugReport) }
    var showAgentSwitcher by remember { mutableStateOf(false) }
    var isChatSearchExpanded by rememberSaveable { mutableStateOf(false) }
    val chatSearchFocusRequester = remember { FocusRequester() }
    val chatBackground by viewModel.chatBackground.collectAsStateWithLifecycle()
    val availableAgents by viewModel.availableAgents.collectAsStateWithLifecycle()
    val favoriteAgentId by viewModel.favoriteAgentId.collectAsStateWithLifecycle()
    val activeBackendLabel by viewModel.activeBackendLabel.collectAsStateWithLifecycle()
    val projectBindings = viewModel.projectBindings
    val pinnedAgentIds by viewModel.pinnedAgentIds.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    var chatMode by rememberSaveable { mutableStateOf("interactive") }
    val drawerConversationRepo = conversationRepository
        ?: hiltViewModel<ConversationPickerViewModel>().conversationRepository
    val drawerConversations by drawerConversationRepo.getConversations(viewModel.agentId)
        .collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(viewModel.agentId) {
        runCatching { drawerConversationRepo.refreshConversationsIfStale(viewModel.agentId, maxAgeMs = 30_000L) }
    }

    val agentName = uiState.agentName
    val agentId = viewModel.agentId
    val agentIdValue = agentId.value
    val conversationId = viewModel.conversationId?.value
    val projectContext = viewModel.projectContext
    var isProjectInfoExpanded by rememberSaveable(projectContext?.identifier) { mutableStateOf(false) }
    val screenTitle = projectContext?.name ?: agentName.ifBlank { stringResource(R.string.screen_chat_title) }
    val currentAgentIsFavorite = agentIdValue == favoriteAgentId
    val currentAgentIsPinned = agentIdValue in pinnedAgentIds
    val switchableAgents = remember(availableAgents, agentId, agentName, favoriteAgentId, pinnedAgentIds) {
        val agents = if (availableAgents.any { it.id == agentId }) {
            availableAgents
        } else {
            listOf(Agent(id = agentId, name = agentName.ifBlank { "Agent" })) + availableAgents
        }
        sortAgentsForPicker(
            agents = agents,
            favoriteAgentId = favoriteAgentId,
            pinnedAgentIds = pinnedAgentIds,
        )
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
                    agentId = agentIdValue,
                    activeBackendLabel = activeBackendLabel,
                    contextWindow = uiState.contextWindow,
                    chatMode = chatMode,
                    onChatModeSelected = { chatMode = it },
                    conversations = drawerConversations,
                    currentConversationId = conversationId,
                    onNewConversation = {
                        scope.launch { drawerState.close() }
                        onSwitchConversation?.invoke(agentIdValue, null, agentName.takeIf { it.isNotBlank() })
                    },
                    onConversationSelected = { selectedConversationId ->
                        scope.launch { drawerState.close() }
                        onSwitchConversation?.invoke(agentIdValue, selectedConversationId, agentName.takeIf { it.isNotBlank() })
                    },
                    onEditAgent = {
                        scope.launch { drawerState.close() }
                        onNavigateToSettings(agentIdValue)
                    },
                    onResetMessages = {
                        scope.launch { drawerState.close() }
                        viewModel.resetMessages()
                    },
                    onRefreshContextWindow = projectBindings::refreshContextWindow,
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
                                    .combinedClickable(
                        onClick = {
                            HapticEffects.contextClick(haptic, view)
                            viewModel.refreshAvailableAgents()
                            showAgentSwitcher = true
                        },
                        onLongClick = {
                            HapticEffects.longPress(haptic)
                            viewModel.toggleCurrentAgentPinned()
                        },
                                    )
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
                                if (currentAgentIsFavorite) {
                                    Icon(
                                        LettaIcons.Star,
                                        contentDescription = "Favorite agent",
                                        modifier = Modifier.size(LettaIconSizing.Inline),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (currentAgentIsPinned) {
                                    Icon(
                                        LettaIcons.Pin,
                                        contentDescription = "Pinned agent",
                                        modifier = Modifier.size(LettaIconSizing.Inline),
                                        tint = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                Icon(
                                    LettaIcons.ArrowDropDown,
                                    contentDescription = "Switch agent",
                                    modifier = Modifier.size(LettaIconSizing.Inline),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ChatTransportChip(
                                    transport = uiState.transport,
                                    a2uiFrameCount = uiState.a2uiFrameCount,
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
                            HapticEffects.contextClick(haptic, view)
                            projectBindings.refreshContextWindow()
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
                        onClick = {
                            HapticEffects.contextClick(haptic, view)
                            showBugReportSheet = true
                        },
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
                    ProjectInfoTray(
                        project = project,
                        agentsState = uiState.projectAgents,
                        brief = uiState.projectBrief,
                        bugReports = uiState.bugReports,
                        expanded = isProjectInfoExpanded,
                        onExpandedChange = { isProjectInfoExpanded = it },
                        onRetryAgents = projectBindings::loadProjectAgents,
                        onRetryBrief = projectBindings::loadProjectBrief,
                        onSaveBriefSection = projectBindings::saveProjectBriefSection,
                        onCreateBugReport = { showBugReportSheet = true },
                        modifier = Modifier.testTag(AgentScaffoldTestTags.PROJECT_CONTEXT_CARD),
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
                                onSwitchConversation?.invoke(agentIdValue, targetConversationId, agentName.takeIf { it.isNotBlank() })
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
            currentAgentId = agentIdValue,
            favoriteAgentId = favoriteAgentId,
            pinnedAgentIds = pinnedAgentIds,
            onDismiss = { showAgentSwitcher = false },
            onTogglePinned = { selectedAgent -> viewModel.toggleAgentPinned(selectedAgent.id.value) },
            onAgentSelected = { selectedAgent ->
                showAgentSwitcher = false
                if (selectedAgent.id.value != agentIdValue) {
                    onSwitchConversation?.invoke(selectedAgent.id.value, null, selectedAgent.name.takeIf { it.isNotBlank() })
                }
            },
        )
    }

    if (showBugReportSheet && projectContext != null) {
        ProjectBugReportSheet(
            state = uiState.bugReports,
            onDismiss = { showBugReportSheet = false },
            onSubmit = {
                projectBindings.submitStructuredBugReport(it)
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
    val conversationsById = remember(conversations) { conversations.associateBy { it.id.value } }

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
