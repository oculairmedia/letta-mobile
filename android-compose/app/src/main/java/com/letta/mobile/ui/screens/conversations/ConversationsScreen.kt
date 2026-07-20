package com.letta.mobile.ui.screens.conversations

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.ui.components.ShimmerConversationList
import com.letta.mobile.ui.icons.LettaIcons

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

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ConversationsTopBar(
                searchQuery = uiState.searchQuery,
                onSearchQueryChange = viewModel::updateSearchQuery,
                isSearchExpanded = isSearchExpanded,
                onSearchExpandedChange = { isSearchExpanded = it },
                activeBackendLabel = activeBackendLabel,
                onNavigateToBackendSwitcher = onNavigateToBackendSwitcher,
                onNavigateToSettings = onNavigateToSettings,
                navigation = navigation,
                showOverflowMenu = showOverflowMenu,
                onShowOverflowMenuChange = { showOverflowMenu = it },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewChat = true }) {
                Icon(LettaIcons.Add, stringResource(R.string.screen_conversations_new_action))
            }
        },
    ) { paddingValues ->
        val convError = uiState.error
        when {
            uiState.isLoading && uiState.conversations.isEmpty() -> {
                ShimmerConversationList(modifier = Modifier.padding(paddingValues))
            }
            convError != null && uiState.conversations.isEmpty() -> {
                ConversationsErrorContent(
                    message = convError,
                    onRetry = { viewModel.loadConversations() },
                    modifier = Modifier.padding(paddingValues),
                )
            }
            else -> {
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
                ConversationListContent(
                    conversations = filteredConversations,
                    isRefreshing = uiState.isRefreshing,
                    isSearchActive = uiState.searchQuery.isNotBlank(),
                    showFirstRunOnboarding = uiState.shouldShowFirstRunOnboarding(),
                    localReadiness = uiState.localLettaCodeReadiness,
                    onCreateFirstAgent = onCreateFirstAgent,
                    onOpenLocalSettings = onNavigateToSettings,
                    actions = listActions,
                    modifier = Modifier.padding(paddingValues),
                )
            }
        }
    }

    uiState.selectedConversation?.let { display ->
        ConversationAdminDialog(
            display = display,
            recompilePreview = uiState.recompilePreview,
            onDismiss = { viewModel.closeConversationAdmin() },
            onRename = { newName ->
                viewModel.renameConversation(display.conversation.id, display.conversation.agentId, newName)
            },
            onToggleArchived = { archived -> viewModel.setConversationArchived(display, archived) },
            onFork = { viewModel.forkConversation(display.conversation.id, display.conversation.agentId) { } },
            onCancelRuns = { viewModel.cancelConversationRuns(display) },
            inspectorMessages = uiState.inspectorMessages,
            isInspectorLoading = uiState.isInspectorLoading,
            inspectorError = uiState.inspectorError,
            onRecompile = { viewModel.recompileConversation(display) },
            onDelete = { viewModel.deleteConversation(display.conversation.id) },
        )
    }
}
