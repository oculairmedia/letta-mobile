package com.letta.mobile.ui.screens.dashboard

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.ui.shell.pages.home.HomePage
import com.letta.mobile.ui.shell.pages.home.HomePageCallbacks
import com.letta.mobile.ui.shell.pages.home.HomePageOptions
import kotlinx.coroutines.launch

/** The phone presentation of the shared Home page: the app bar and drawer carry title and search. */
private val AndroidHomeOptions = HomePageOptions(showTitle = false, showSearch = false, touch = true)

/**
 * The Admin tab's home: Android's app bar (search, backend chip, settings) and admin drawer around
 * the shared Home page (letta-mobile-c3np7.3.11.1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToAgents: () -> Unit,
    onNavigateToConversations: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToBlocks: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToChat: (agentId: String, agentName: String?, initialMessage: String?) -> Unit,
    onNavigateToChatMessage: (agentId: String, conversationId: String, messageId: String) -> Unit,
    onNavigateToEditAgent: (agentId: String) -> Unit,
    onNavigateToUsage: () -> Unit,
    onNavigateToConversation: (agentId: String, conversationId: String) -> Unit = { agentId, _ ->
        onNavigateToChat(agentId, null, null)
    },
    onNavigateToTemplates: () -> Unit = {},
    onNavigateToArchives: () -> Unit = {},
    onNavigateToFolders: () -> Unit = {},
    onNavigateToGroups: () -> Unit = {},
    onNavigateToProviders: () -> Unit = {},
    onNavigateToIdentities: () -> Unit = {},
    onNavigateToSchedules: () -> Unit = {},
    onNavigateToRuns: () -> Unit = {},
    onNavigateToJobs: () -> Unit = {},
    onNavigateToMessageBatches: () -> Unit = {},
    onNavigateToMcp: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToTelemetry: () -> Unit = {},
    onNavigateToSystemAccess: () -> Unit = {},
    onNavigateToBotSettings: () -> Unit = {},
    onNavigateToProjects: () -> Unit = {},
    onNavigateToModels: () -> Unit = {},
    activeBackendLabel: String? = null,
    onNavigateToBackendSwitcher: (() -> Unit)? = null,
    title: String = "Letta",
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val navigation = remember(
        onNavigateToAgents, onNavigateToConversations, onNavigateToTools, onNavigateToBlocks,
        onNavigateToSettings, onNavigateToChat, onNavigateToUsage, onNavigateToTemplates,
        onNavigateToArchives, onNavigateToFolders, onNavigateToGroups, onNavigateToProviders,
        onNavigateToIdentities, onNavigateToSchedules, onNavigateToRuns, onNavigateToJobs,
        onNavigateToMessageBatches, onNavigateToMcp, onNavigateToAbout, onNavigateToTelemetry,
        onNavigateToSystemAccess, onNavigateToBotSettings, onNavigateToProjects, onNavigateToModels,
    ) {
        HomeNavigationCallbacks(
            onNavigateToAgents = onNavigateToAgents,
            onNavigateToConversations = onNavigateToConversations,
            onNavigateToTools = onNavigateToTools,
            onNavigateToBlocks = onNavigateToBlocks,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToChat = onNavigateToChat,
            onNavigateToUsage = onNavigateToUsage,
            onNavigateToTemplates = onNavigateToTemplates,
            onNavigateToArchives = onNavigateToArchives,
            onNavigateToFolders = onNavigateToFolders,
            onNavigateToGroups = onNavigateToGroups,
            onNavigateToProviders = onNavigateToProviders,
            onNavigateToIdentities = onNavigateToIdentities,
            onNavigateToSchedules = onNavigateToSchedules,
            onNavigateToRuns = onNavigateToRuns,
            onNavigateToJobs = onNavigateToJobs,
            onNavigateToMessageBatches = onNavigateToMessageBatches,
            onNavigateToMcp = onNavigateToMcp,
            onNavigateToAbout = onNavigateToAbout,
            onNavigateToTelemetry = onNavigateToTelemetry,
            onNavigateToSystemAccess = onNavigateToSystemAccess,
            onNavigateToBotSettings = onNavigateToBotSettings,
            onNavigateToProjects = onNavigateToProjects,
            onNavigateToModels = onNavigateToModels,
        )
    }
    val routes = HomeChatRoutes(onNavigateToChatMessage, onNavigateToConversation, onNavigateToEditAgent)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                HomeScreenDrawerContent(
                    params = HomeScreenDrawerParams(
                        state = state,
                        navigation = navigation,
                        actions = viewModel.actions,
                        drawerState = drawerState,
                        scope = scope,
                    ),
                )
            }
        },
    ) {
        Scaffold(
            modifier = Modifier
                .systemBarsPadding()
                .imePadding()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
            topBar = {
                HomeScreenTopBar(
                    params = HomeScreenTopBarParams(
                        title = title,
                        searchQuery = state.search.query,
                        isConnected = !state.stats.loading && state.stats.error == null,
                        isSearchExpanded = isSearchExpanded,
                        onSearchExpandedChange = { isSearchExpanded = it },
                        onSearchQueryChange = viewModel.actions::updateSearchQuery,
                        onSearchClear = viewModel.actions::clearSearch,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onNavigateToSettings = onNavigateToSettings,
                        activeBackendLabel = activeBackendLabel,
                        onNavigateToBackendSwitcher = onNavigateToBackendSwitcher,
                        scrollBehavior = scrollBehavior,
                    ),
                )
            },
        ) { paddingValues ->
            HomePage(
                state = state,
                callbacks = HomePageCallbacks(viewModel.actions, navigation.homePageNavigation(state, routes)),
                modifier = Modifier.padding(paddingValues),
                options = AndroidHomeOptions,
            )
        }
    }
}
