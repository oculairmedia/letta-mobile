package com.letta.mobile.feature.chat.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.components.LettaSearchBar
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun AgentScaffoldBody(
    navigation: AgentScaffoldNavigationCallbacks,
    viewModel: AdminChatViewModel,
    chatMode: String,
    onChatModeChange: (String) -> Unit,
    showBugReportSheet: Boolean,
    onShowBugReportSheetChange: (Boolean) -> Unit,
    showAgentSwitcher: Boolean,
    onShowAgentSwitcherChange: (Boolean) -> Unit,
    isChatSearchExpanded: Boolean,
    onChatSearchExpandedChange: (Boolean) -> Unit,
    isProjectInfoExpanded: Boolean,
    onProjectInfoExpandedChange: (Boolean) -> Unit,
    showModelPicker: Boolean,
    onShowModelPickerChange: (Boolean) -> Unit,
    chatSearchFocusRequester: FocusRequester,
    conversationRepository: IConversationRepository?,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chatBackground by viewModel.chatBackground.collectAsStateWithLifecycle()
    val availableAgents by viewModel.availableAgents.collectAsStateWithLifecycle()
    val favoriteAgentId by viewModel.favoriteAgentId.collectAsStateWithLifecycle()
    val activeBackendLabel by viewModel.activeBackendLabel.collectAsStateWithLifecycle()
    val availableModels by viewModel.llmModels.collectAsStateWithLifecycle()
    val activeAgent by viewModel.activeAgent.collectAsStateWithLifecycle()
    val activeAgentModel = androidx.compose.runtime.remember(activeAgent) { activeAgent?.model }
    val projectBindings = viewModel.projectBindings
    val pinnedAgentIds by viewModel.pinnedAgentIds.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
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
    val screenTitle = projectContext?.name ?: agentName.ifBlank { stringResource(R.string.screen_chat_title) }
    val currentAgentIsFavorite = agentIdValue == favoriteAgentId
    val currentAgentIsPinned = agentIdValue in pinnedAgentIds
    val switchableAgents = androidx.compose.runtime.remember(availableAgents, agentId, agentName, favoriteAgentId, pinnedAgentIds) {
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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = !drawerState.isOpen && (isChatSearchExpanded || uiState.isSearchActive)) {
        onChatSearchExpandedChange(false)
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
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> viewModel.onScreenPaused()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> viewModel.onScreenResumed()
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
                    currentModel = activeAgentModel,
                    contextWindow = uiState.contextWindow,
                    chatMode = chatMode,
                    onChatModeSelected = onChatModeChange,
                    onModelTap = { onShowModelPickerChange(true) },
                    conversations = drawerConversations,
                    currentConversationId = conversationId,
                    onNewConversation = {
                        scope.launch { drawerState.close() }
                        navigation.onSwitchConversation?.invoke(agentIdValue, null, agentName.takeIf { it.isNotBlank() })
                    },
                    onConversationSelected = { selectedConversationId ->
                        scope.launch { drawerState.close() }
                        navigation.onSwitchConversation?.invoke(agentIdValue, selectedConversationId, agentName.takeIf { it.isNotBlank() })
                    },
                    onEditAgent = {
                        scope.launch { drawerState.close() }
                        navigation.onNavigateToSettings(agentIdValue)
                    },
                    onResetMessages = {
                        scope.launch { drawerState.close() }
                        viewModel.resetMessages()
                    },
                    onRefreshContextWindow = projectBindings::refreshContextWindow,
                    onNavigateToAdmin = {
                        scope.launch { drawerState.close() }
                        navigation.onNavigateToAdmin?.invoke()
                    },
                    onNavigateToConversations = {
                        scope.launch { drawerState.close() }
                        navigation.onNavigateToConversationList?.invoke()
                    },
                    onNavigateToMemory = {
                        scope.launch { drawerState.close() }
                        navigation.onNavigateToMemory?.invoke(agentIdValue)
                    },
                    onNavigateToSchedules = {
                        scope.launch { drawerState.close() }
                        navigation.onNavigateToSchedules?.invoke(agentIdValue)
                    },
                    onClose = { scope.launch { drawerState.close() } },
                    modifier = Modifier.testTag(AgentScaffoldTestTags.DRAWER_CONTENT),
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                AgentScaffoldTopBar(
                    isChatSearchExpanded = isChatSearchExpanded,
                    isSearchActive = uiState.isSearchActive,
                    searchQuery = uiState.searchQuery,
                    onSearchQueryChange = viewModel::updateChatSearchQuery,
                    onClearSearch = viewModel::clearChatSearch,
                    chatSearchFocusRequester = chatSearchFocusRequester,
                    agentName = agentName,
                    screenTitle = screenTitle,
                    currentAgentIsFavorite = currentAgentIsFavorite,
                    currentAgentIsPinned = currentAgentIsPinned,
                    onAgentTitleClick = {
                        HapticEffects.contextClick(haptic, view)
                        viewModel.refreshAvailableAgents()
                        onShowAgentSwitcherChange(true)
                    },
                    onAgentTitleLongClick = {
                        HapticEffects.longPress(haptic)
                        viewModel.toggleCurrentAgentPinned()
                    },
                    scrollBehavior = scrollBehavior,
                    onSearchClick = {
                        HapticEffects.contextClick(haptic, view)
                        onChatSearchExpandedChange(true)
                    },
                    onMenuClick = {
                        HapticEffects.contextClick(haptic, view)
                        projectBindings.refreshContextWindow()
                        scope.launch {
                            drawerState.open()
                            runCatching { drawerConversationRepo.refreshConversations(agentId) }
                        }
                    },
                )
            },
            floatingActionButton = {
                if (projectContext != null) {
                    FloatingActionButton(
                        onClick = {
                            HapticEffects.contextClick(haptic, view)
                            onShowBugReportSheetChange(true)
                        },
                        modifier = Modifier.testTag(AgentScaffoldTestTags.PROJECT_BUG_FAB),
                    ) {
                        Icon(LettaIcons.Error, contentDescription = stringResource(R.string.screen_project_bug_report_open))
                    }
                }
            },
        ) { paddingValues ->
            AgentScaffoldMainContent(
                uiState = uiState,
                projectContext = projectContext,
                isProjectInfoExpanded = isProjectInfoExpanded,
                onProjectInfoExpandedChange = onProjectInfoExpandedChange,
                projectBindings = projectBindings,
                onShowBugReportSheetChange = onShowBugReportSheetChange,
                isChatSearchExpanded = isChatSearchExpanded,
                onChatSearchExpandedChange = onChatSearchExpandedChange,
                navigation = navigation,
                viewModel = viewModel,
                chatBackground = chatBackground,
                chatMode = chatMode,
                agentIdValue = agentIdValue,
                agentName = agentName,
                conversationId = conversationId,
                drawerConversations = drawerConversations,
                paddingValues = paddingValues,
            )
        }
    }

    AgentScaffoldSheets(
        showAgentSwitcher = showAgentSwitcher,
        onShowAgentSwitcherChange = onShowAgentSwitcherChange,
        switchableAgents = switchableAgents,
        agentIdValue = agentIdValue,
        favoriteAgentId = favoriteAgentId,
        pinnedAgentIds = pinnedAgentIds,
        viewModel = viewModel,
        navigation = navigation,
        showBugReportSheet = showBugReportSheet,
        onShowBugReportSheetChange = onShowBugReportSheetChange,
        projectContext = projectContext,
        uiState = uiState,
        projectBindings = projectBindings,
        showModelPicker = showModelPicker,
        onShowModelPickerChange = onShowModelPickerChange,
        availableModels = availableModels,
        activeAgentModel = activeAgentModel,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AgentScaffoldTopBar(
    isChatSearchExpanded: Boolean,
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    chatSearchFocusRequester: FocusRequester,
    agentName: String,
    screenTitle: String,
    currentAgentIsFavorite: Boolean,
    currentAgentIsPinned: Boolean,
    onAgentTitleClick: () -> Unit,
    onAgentTitleLongClick: () -> Unit,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onSearchClick: () -> Unit,
    onMenuClick: () -> Unit,
) {
    TopAppBar(
        title = {
            if (isChatSearchExpanded || isSearchActive) {
                LettaSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onClear = onClearSearch,
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
                            onClick = onAgentTitleClick,
                            onLongClick = onAgentTitleLongClick,
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
                }
            }
        },
        modifier = Modifier.padding(top = with(LocalDensity.current) { WindowInsets.safeDrawing.getTop(this).toDp() }),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior,
        actions = {
            if (!isChatSearchExpanded && !isSearchActive) {
                IconButton(onClick = onSearchClick) {
                    Icon(LettaIcons.Search, contentDescription = "Search")
                }
            }
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.testTag(AgentScaffoldTestTags.MENU_BUTTON),
            ) {
                Icon(LettaIcons.Menu, "Menu")
            }
        }
    )
}

@Composable
private fun AgentScaffoldMainContent(
    uiState: com.letta.mobile.ui.chat.render.ChatUiState,
    projectContext: com.letta.mobile.ui.chat.render.ProjectChatContext?,
    isProjectInfoExpanded: Boolean,
    onProjectInfoExpandedChange: (Boolean) -> Unit,
    projectBindings: com.letta.mobile.feature.chat.coordination.ChatProjectBindings,
    onShowBugReportSheetChange: (Boolean) -> Unit,
    isChatSearchExpanded: Boolean,
    onChatSearchExpandedChange: (Boolean) -> Unit,
    navigation: AgentScaffoldNavigationCallbacks,
    viewModel: AdminChatViewModel,
    chatBackground: com.letta.mobile.ui.theme.ChatBackground,
    chatMode: String,
    agentIdValue: String,
    agentName: String,
    conversationId: String?,
    drawerConversations: List<com.letta.mobile.data.model.Conversation>,
    paddingValues: PaddingValues,
) {
    val topPadding = paddingValues.calculateTopPadding()
    Column(modifier = Modifier.fillMaxSize()) {
        projectContext?.let { project ->
            ProjectInfoTray(
                project = project,
                agentsState = uiState.projectAgents,
                brief = uiState.projectBrief,
                bugReports = uiState.bugReports,
                expanded = isProjectInfoExpanded,
                onExpandedChange = onProjectInfoExpandedChange,
                onRetryAgents = projectBindings::loadProjectAgents,
                onRetryBrief = projectBindings::loadProjectBrief,
                onSaveBriefSection = projectBindings::saveProjectBriefSection,
                onCreateBugReport = { onShowBugReportSheetChange(true) },
                modifier = Modifier
                    .padding(top = topPadding)
                    .testTag(AgentScaffoldTestTags.PROJECT_CONTEXT_CARD),
            )
        }
        val chatModifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .testTag(AgentScaffoldTestTags.CHAT_SCREEN_CONTENT)

        if (uiState.isSearchActive) {
            ChatSearchResultsContent(
                searchQuery = uiState.searchQuery,
                results = uiState.searchResults,
                isSearching = uiState.isSearching,
                conversations = drawerConversations,
                currentConversationId = conversationId,
                onResultClick = { result ->
                    onChatSearchExpandedChange(false)
                    viewModel.clearChatSearch()
                    result.conversationId?.let { targetConversationId ->
                        navigation.onSwitchConversation?.invoke(agentIdValue, targetConversationId, agentName.takeIf { it.isNotBlank() })
                    }
                },
                modifier = chatModifier
                    .padding(top = if (projectContext == null) topPadding else 0.dp, bottom = paddingValues.calculateBottomPadding()),
            )
        } else {
            ChatScreen(
                modifier = chatModifier,
                contentPadding = PaddingValues(
                    top = if (projectContext == null) topPadding else 0.dp,
                    bottom = 0.dp
                ),
                chatBackground = chatBackground,
                chatMode = chatMode,
                onBugCommand = { onShowBugReportSheetChange(true) },
                onViewSubagentConversation = navigation.onViewSubagentConversation
                    ?: navigation.onSwitchConversation?.let { switch ->
                        { subagentAgentId, subagentConversationId ->
                            switch(subagentAgentId, subagentConversationId, null)
                        }
                    },
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun AgentScaffoldSheets(
    showAgentSwitcher: Boolean,
    onShowAgentSwitcherChange: (Boolean) -> Unit,
    switchableAgents: List<Agent>,
    agentIdValue: String,
    favoriteAgentId: String?,
    pinnedAgentIds: Set<String>,
    viewModel: AdminChatViewModel,
    navigation: AgentScaffoldNavigationCallbacks,
    showBugReportSheet: Boolean,
    onShowBugReportSheetChange: (Boolean) -> Unit,
    projectContext: com.letta.mobile.ui.chat.render.ProjectChatContext?,
    uiState: com.letta.mobile.ui.chat.render.ChatUiState,
    projectBindings: com.letta.mobile.feature.chat.coordination.ChatProjectBindings,
    showModelPicker: Boolean,
    onShowModelPickerChange: (Boolean) -> Unit,
    availableModels: List<com.letta.mobile.data.model.LlmModel>,
    activeAgentModel: String?,
) {
    if (showAgentSwitcher) {
        AgentPickerSheet(
            agents = switchableAgents,
            currentAgentId = agentIdValue,
            favoriteAgentId = favoriteAgentId,
            pinnedAgentIds = pinnedAgentIds,
            onDismiss = { onShowAgentSwitcherChange(false) },
            onTogglePinned = { selectedAgent -> viewModel.toggleAgentPinned(selectedAgent.id.value) },
            onAgentSelected = { selectedAgent ->
                onShowAgentSwitcherChange(false)
                if (selectedAgent.id.value != agentIdValue) {
                    navigation.onSwitchConversation?.invoke(selectedAgent.id.value, null, selectedAgent.name.takeIf { it.isNotBlank() })
                }
            },
        )
    }

    if (showBugReportSheet && projectContext != null) {
        ProjectBugReportSheet(
            state = uiState.bugReports,
            onDismiss = { onShowBugReportSheetChange(false) },
            onSubmit = {
                projectBindings.submitStructuredBugReport(it)
                onShowBugReportSheetChange(false)
            },
        )
    }

    if (showModelPicker) {
        ModelPickerSheet(
            models = availableModels,
            currentModel = activeAgentModel,
            onDismiss = { onShowModelPickerChange(false) },
            onModelSelected = { handle ->
                viewModel.updateActiveAgentModel(handle)
                onShowModelPickerChange(false)
            },
            onRefresh = viewModel::refreshModels,
        )
    }
}
