package com.letta.mobile.ui.screens.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.components.ShimmerConversationList
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.preview.LettaPreviewFrame
import com.letta.mobile.ui.screens.agentlist.LocalLettaCodeCreateReadiness

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onNavigateToChat: (agentId: String, conversationId: String, agentName: String?) -> Unit,
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
    onCreateFirstAgent: () -> Unit = onNavigateToAgentList,
    activeBackendLabel: String? = null,
    onNavigateToBackendSwitcher: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showNewChat by rememberSaveable { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val navigation = remember(
        onNavigateToTemplates,
        onNavigateToArchives,
        onNavigateToFolders,
        onNavigateToGroups,
        onNavigateToProviders,
        onNavigateToBlocks,
        onNavigateToIdentities,
        onNavigateToSchedules,
        onNavigateToRuns,
        onNavigateToJobs,
        onNavigateToMessageBatches,
        onNavigateToMcp,
        onNavigateToProjects,
        onNavigateToAbout,
    ) {
        ConversationsNavigation(
            onNavigateToTemplates = onNavigateToTemplates,
            onNavigateToArchives = onNavigateToArchives,
            onNavigateToFolders = onNavigateToFolders,
            onNavigateToGroups = onNavigateToGroups,
            onNavigateToProviders = onNavigateToProviders,
            onNavigateToBlocks = onNavigateToBlocks,
            onNavigateToIdentities = onNavigateToIdentities,
            onNavigateToSchedules = onNavigateToSchedules,
            onNavigateToRuns = onNavigateToRuns,
            onNavigateToJobs = onNavigateToJobs,
            onNavigateToMessageBatches = onNavigateToMessageBatches,
            onNavigateToMcp = onNavigateToMcp,
            onNavigateToProjects = onNavigateToProjects,
            onNavigateToAbout = onNavigateToAbout,
        )
    }

    if (showNewChat) {
        NewChatAgentScreen(
            agents = uiState.agents,
            onBack = { showNewChat = false },
            onAgentSelected = { agent ->
                viewModel.createConversation(agent.id) { conversationId ->
                    showNewChat = false
                    onNavigateToChat(
                        agent.id.value,
                        conversationId.value,
                        agent.name.takeIf(String::isNotBlank),
                    )
                }
            },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(uiState.createConversationError) {
        val message = uiState.createConversationError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearCreateConversationError()
    }

    val filteredConversations = remember(uiState.conversations, uiState.searchQuery) {
        viewModel.getFilteredConversations()
    }
    val listActions = remember(onNavigateToChat, viewModel) {
        ConversationListActions(
            onConversationClick = { display ->
                onNavigateToChat(
                    display.conversation.agentId.value,
                    display.conversation.id.value,
                    display.routeAgentName(),
                )
            },
            onOpenAdmin = viewModel::openConversationAdmin,
            onDeleteConversation = { viewModel.deleteConversation(it.conversation.id) },
            onRenameConversation = { display, newName ->
                viewModel.renameConversation(
                    display.conversation.id,
                    display.conversation.agentId,
                    newName,
                )
            },
            onTogglePinned = viewModel::toggleConversationPinned,
            onForkConversation = { display ->
                viewModel.forkConversation(display.conversation.id, display.conversation.agentId) { newConvId ->
                    onNavigateToChat(
                        display.conversation.agentId.value,
                        newConvId.value,
                        display.routeAgentName(),
                    )
                }
            },
            onRefresh = viewModel::refresh,
        )
    }

    ConversationsScreenContent(
        state = ConversationsScreenState(
            isLoading = uiState.isLoading,
            error = uiState.error,
            hasConversations = uiState.conversations.isNotEmpty(),
            conversations = filteredConversations,
            isRefreshing = uiState.isRefreshing,
            searchQuery = uiState.searchQuery,
            isSearchExpanded = isSearchExpanded,
            localReadiness = uiState.localLettaCodeReadiness,
            showFirstRunOnboarding = uiState.shouldShowFirstRunOnboarding(),
            activeBackendLabel = activeBackendLabel,
            showOverflowMenu = showOverflowMenu,
            onCreateFirstAgent = onCreateFirstAgent,
            onOpenLocalSettings = onNavigateToSettings,
            onNewChatClick = { showNewChat = true },
        ),
        callbacks = ConversationsScreenCallbacks(
            onSearchQueryChange = viewModel::updateSearchQuery,
            onSearchExpandedChange = { isSearchExpanded = it },
            onShowOverflowMenuChange = { showOverflowMenu = it },
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToBackendSwitcher = onNavigateToBackendSwitcher,
            onConversationClick = listActions.onConversationClick,
            onOpenAdmin = listActions.onOpenAdmin,
            onDeleteConversation = listActions.onDeleteConversation,
            onRenameConversation = listActions.onRenameConversation,
            onTogglePinned = listActions.onTogglePinned,
            onForkConversation = listActions.onForkConversation,
            onRefresh = listActions.onRefresh,
            onRetryLoad = viewModel::loadConversations,
            navigation = navigation,
            scrollBehavior = scrollBehavior,
        ),
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )

    uiState.selectedConversation?.let { display ->
        ConversationAdminDialog(
            state = ConversationAdminDialogState(
                display = display,
                recompilePreview = uiState.recompilePreview,
                inspectorMessages = uiState.inspectorMessages,
                isInspectorLoading = uiState.isInspectorLoading,
                inspectorError = uiState.inspectorError,
            ),
            callbacks = ConversationAdminDialogCallbacks(
                onDismiss = { viewModel.closeConversationAdmin() },
                onRename = { newName ->
                    viewModel.renameConversation(display.conversation.id, display.conversation.agentId, newName)
                },
                onToggleArchived = { archived -> viewModel.setConversationArchived(display, archived) },
                onFork = { viewModel.forkConversation(display.conversation.id, display.conversation.agentId) { } },
                onCancelRuns = { viewModel.cancelConversationRuns(display) },
                onRecompile = { viewModel.recompileConversation(display) },
                onDelete = { viewModel.deleteConversation(display.conversation.id) },
            ),
        )
    }
}

/**
 * Everything [ConversationsScreenContent]/[ConversationsScreenBody] need to
 * render the screen body, sourced from [ConversationsUiState] plus the
 * screen-level local state ([ConversationsScreen] owns the latter).
 *
 * [conversations] is already filtered (mirrors the `filteredConversations`
 * value the production screen used to compute inline). [hasConversations]
 * tracks the *unfiltered* list's emptiness separately, because the
 * loading/error full-screen branches must gate on whether there is any
 * cached data at all, not on whether the current search filter happens to
 * produce zero rows.
 */
internal data class ConversationsScreenState(
    val isLoading: Boolean,
    val error: String?,
    val hasConversations: Boolean,
    val conversations: List<ConversationDisplay>,
    val isRefreshing: Boolean,
    val searchQuery: String,
    val isSearchExpanded: Boolean,
    val localReadiness: LocalLettaCodeCreateReadiness,
    val showFirstRunOnboarding: Boolean,
    val activeBackendLabel: String?,
    val showOverflowMenu: Boolean,
    val onCreateFirstAgent: () -> Unit,
    val onOpenLocalSettings: () -> Unit,
    val onNewChatClick: () -> Unit,
)

internal data class ConversationsScreenCallbacks(
    val onSearchQueryChange: (String) -> Unit,
    val onSearchExpandedChange: (Boolean) -> Unit,
    val onShowOverflowMenuChange: (Boolean) -> Unit,
    val onNavigateToSettings: () -> Unit,
    val onNavigateToBackendSwitcher: (() -> Unit)?,
    val onConversationClick: (ConversationDisplay) -> Unit,
    val onOpenAdmin: (ConversationDisplay) -> Unit,
    val onDeleteConversation: (ConversationDisplay) -> Unit,
    val onRenameConversation: (ConversationDisplay, String) -> Unit,
    val onTogglePinned: (ConversationDisplay) -> Unit,
    val onForkConversation: (ConversationDisplay) -> Unit,
    val onRefresh: () -> Unit,
    val onRetryLoad: () -> Unit,
    val navigation: ConversationsNavigation,
    val scrollBehavior: TopAppBarScrollBehavior,
)

private fun ConversationsScreenCallbacks.toListActions() = ConversationListActions(
    onConversationClick = onConversationClick,
    onOpenAdmin = onOpenAdmin,
    onDeleteConversation = onDeleteConversation,
    onRenameConversation = onRenameConversation,
    onTogglePinned = onTogglePinned,
    onForkConversation = onForkConversation,
    onRefresh = onRefresh,
)

/**
 * Real production body: the [Scaffold] with the actual [ConversationsTopBar]
 * and [FloatingActionButton], plus the loading/error/list `when`. Everything
 * outside of this (view-model wiring, the `showNewChat` early return, the
 * snackbar [LaunchedEffect], and the trailing admin dialog) stays in
 * [ConversationsScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationsScreenContent(
    state: ConversationsScreenState,
    callbacks: ConversationsScreenCallbacks,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.nestedScroll(callbacks.scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ConversationsTopBar(
                state = ConversationsTopBarState(
                    searchQuery = state.searchQuery,
                    isSearchExpanded = state.isSearchExpanded,
                    activeBackendLabel = state.activeBackendLabel,
                    showOverflowMenu = state.showOverflowMenu,
                    scrollBehavior = callbacks.scrollBehavior,
                ),
                callbacks = ConversationsTopBarCallbacks(
                    onSearchQueryChange = callbacks.onSearchQueryChange,
                    onSearchExpandedChange = callbacks.onSearchExpandedChange,
                    onNavigateToBackendSwitcher = callbacks.onNavigateToBackendSwitcher,
                    onNavigateToSettings = callbacks.onNavigateToSettings,
                    onShowOverflowMenuChange = callbacks.onShowOverflowMenuChange,
                ),
                navigation = callbacks.navigation,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = state.onNewChatClick) {
                Icon(LettaIcons.Add, stringResource(R.string.screen_conversations_new_action))
            }
        },
    ) { paddingValues ->
        val error = state.error
        when {
            state.isLoading && !state.hasConversations -> {
                ShimmerConversationList(modifier = Modifier.padding(paddingValues))
            }
            error != null && !state.hasConversations -> {
                ConversationsErrorContent(
                    message = error,
                    onRetry = callbacks.onRetryLoad,
                    modifier = Modifier.padding(paddingValues),
                )
            }
            else -> {
                ConversationListContent(
                    state = ConversationListContentState(
                        conversations = state.conversations,
                        isRefreshing = state.isRefreshing,
                        isSearchActive = state.searchQuery.isNotBlank(),
                        showFirstRunOnboarding = state.showFirstRunOnboarding,
                        localReadiness = state.localReadiness,
                        onCreateFirstAgent = state.onCreateFirstAgent,
                        onOpenLocalSettings = state.onOpenLocalSettings,
                    ),
                    actions = callbacks.toListActions(),
                    modifier = Modifier.padding(paddingValues),
                )
            }
        }
    }
}

/**
 * Preview-safe body: same loading/error/list `when` logic as
 * [ConversationsScreenContent], but with a plain title row instead of a real
 * [Scaffold]/`TopAppBar` (Material3 `TopAppBar` throws `NoSuchMethodError` in
 * the layoutlib preview renderer, same as [ConversationsTopBarTitlePreview]).
 *
 * The empty/first-run branch still routes through the real
 * [ConversationListContent] entry point, since its empty-state path never
 * reaches `PullToRefreshBox`. The populated-list branch renders
 * [ConversationCard] rows directly in a plain scrollable [Column] instead —
 * going through [ConversationListContent] there would hit
 * `ConversationListRefreshableContent`'s `PullToRefreshBox`, which throws the
 * same `NoSuchMethodError` that [ConversationListContentPreview] works around
 * by calling the (file-private, so unavailable here) section renderer
 * directly.
 */
@Composable
internal fun ConversationsScreenBody(
    state: ConversationsScreenState,
    callbacks: ConversationsScreenCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.common_conversations),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        val error = state.error
        when {
            state.isLoading && !state.hasConversations -> {
                ShimmerConversationList(modifier = Modifier.weight(1f))
            }
            error != null && !state.hasConversations -> {
                ConversationsErrorContent(
                    message = error,
                    onRetry = callbacks.onRetryLoad,
                    modifier = Modifier.weight(1f),
                )
            }
            state.conversations.isEmpty() -> {
                ConversationListContent(
                    state = ConversationListContentState(
                        conversations = state.conversations,
                        isRefreshing = state.isRefreshing,
                        isSearchActive = state.searchQuery.isNotBlank(),
                        showFirstRunOnboarding = state.showFirstRunOnboarding,
                        localReadiness = state.localReadiness,
                        onCreateFirstAgent = state.onCreateFirstAgent,
                        onOpenLocalSettings = state.onOpenLocalSettings,
                    ),
                    actions = callbacks.toListActions(),
                    modifier = Modifier.weight(1f),
                )
            }
            else -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.conversations.forEach { display ->
                        ConversationCard(
                            display = display,
                            callbacks = ConversationCardCallbacks(
                                onClick = { callbacks.onConversationClick(display) },
                                onOpenAdmin = { callbacks.onOpenAdmin(display) },
                                onDelete = { callbacks.onDeleteConversation(display) },
                                onRename = { newName -> callbacks.onRenameConversation(display, newName) },
                                onTogglePinned = { callbacks.onTogglePinned(display) },
                                onFork = { callbacks.onForkConversation(display) },
                            ),
                        )
                    }
                }
            }
        }
    }
}

// region Previews

private fun previewConversationDisplay(id: String, summary: String, pinned: Boolean = false): ConversationDisplay =
    ConversationDisplay(
        conversation = Conversation(
            id = ConversationId(id),
            agentId = AgentId("agent-1"),
            summary = summary,
            createdAt = "2026-08-01T09:00:00Z",
            lastMessageAt = "2026-08-07T18:30:00Z",
        ),
        agentName = "General Assistant",
        isPinned = pinned,
    )

private fun previewConversations(): List<ConversationDisplay> = listOf(
    previewConversationDisplay("conv-1", "Weekly planning check-in", pinned = true),
    previewConversationDisplay("conv-2", "Release triage"),
    previewConversationDisplay("conv-3", "Research digest"),
    previewConversationDisplay("conv-4", "Onboarding notes"),
)

private fun previewConversationsScreenState(
    conversations: List<ConversationDisplay> = emptyList(),
    isLoading: Boolean = false,
    error: String? = null,
    showFirstRunOnboarding: Boolean = false,
    isSearchExpanded: Boolean = false,
    searchQuery: String = "",
) = ConversationsScreenState(
    isLoading = isLoading,
    error = error,
    hasConversations = conversations.isNotEmpty(),
    conversations = conversations,
    isRefreshing = false,
    searchQuery = searchQuery,
    isSearchExpanded = isSearchExpanded,
    localReadiness = LocalLettaCodeCreateReadiness(),
    showFirstRunOnboarding = showFirstRunOnboarding,
    activeBackendLabel = null,
    showOverflowMenu = false,
    onCreateFirstAgent = {},
    onOpenLocalSettings = {},
    onNewChatClick = {},
)

private fun previewConversationsNavigation() = ConversationsNavigation(
    onNavigateToTemplates = {},
    onNavigateToArchives = {},
    onNavigateToFolders = {},
    onNavigateToGroups = {},
    onNavigateToProviders = {},
    onNavigateToBlocks = {},
    onNavigateToIdentities = {},
    onNavigateToSchedules = {},
    onNavigateToRuns = {},
    onNavigateToJobs = {},
    onNavigateToMessageBatches = {},
    onNavigateToMcp = {},
    onNavigateToProjects = {},
    onNavigateToAbout = {},
)

// pinnedScrollBehavior() is @ExperimentalMaterial3Api and must be created in
// a composable context; centralizing it here keeps every preview below free
// of its own @OptIn.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun previewConversationsScreenCallbacks() = ConversationsScreenCallbacks(
    onSearchQueryChange = {},
    onSearchExpandedChange = {},
    onShowOverflowMenuChange = {},
    onNavigateToSettings = {},
    onNavigateToBackendSwitcher = null,
    onConversationClick = {},
    onOpenAdmin = {},
    onDeleteConversation = {},
    onRenameConversation = { _, _ -> },
    onTogglePinned = {},
    onForkConversation = {},
    onRefresh = {},
    onRetryLoad = {},
    navigation = previewConversationsNavigation(),
    scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
)

@PreviewLightDark
@Composable
private fun ConversationsScreenPopulatedPreview() {
    LettaPreviewFrame {
        ConversationsScreenBody(
            state = previewConversationsScreenState(conversations = previewConversations()),
            callbacks = previewConversationsScreenCallbacks(),
        )
    }
}

@PreviewLightDark
@Composable
private fun ConversationsScreenLoadingPreview() {
    LettaPreviewFrame {
        ConversationsScreenBody(
            state = previewConversationsScreenState(isLoading = true),
            callbacks = previewConversationsScreenCallbacks(),
        )
    }
}

@PreviewLightDark
@Composable
private fun ConversationsScreenEmptyPreview() {
    LettaPreviewFrame {
        ConversationsScreenBody(
            state = previewConversationsScreenState(),
            callbacks = previewConversationsScreenCallbacks(),
        )
    }
}

@PreviewLightDark
@Composable
private fun ConversationsScreenErrorPreview() {
    LettaPreviewFrame {
        ConversationsScreenBody(
            state = previewConversationsScreenState(error = "Failed to load conversations"),
            callbacks = previewConversationsScreenCallbacks(),
        )
    }
}

@PreviewLightDark
@Composable
private fun ConversationsScreenFirstRunPreview() {
    LettaPreviewFrame {
        ConversationsScreenBody(
            state = previewConversationsScreenState(showFirstRunOnboarding = true),
            callbacks = previewConversationsScreenCallbacks(),
        )
    }
}

@PreviewLightDark
@Composable
private fun ConversationsScreenSearchActivePreview() {
    LettaPreviewFrame {
        ConversationsScreenBody(
            state = previewConversationsScreenState(
                conversations = previewConversations().filter { "release" in (it.conversation.summary ?: "") },
                isSearchExpanded = true,
                searchQuery = "release",
            ),
            callbacks = previewConversationsScreenCallbacks(),
        )
    }
}

// A Tier-2 preview of ConversationsScreenContent (the real Scaffold) is
// deliberately omitted: it composes both ConversationsTopBar's TopAppBar and
// ConversationListContent's PullToRefreshBox, and this file's own precedent
// (ConversationsTopBarTitlePreview in ConversationsTopBar.kt,
// ConversationListContentPreview in ConversationListContent.kt) already
// documents that both throw NoSuchMethodError in the layoutlib preview
// renderer individually — combining them would fail the same way.

// endregion
