package com.letta.mobile.ui.screens.dashboard

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.ShellPageDeps
import com.letta.mobile.ui.shell.header.HeaderAction
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.shell.header.rememberShellSearchSlot
import com.letta.mobile.ui.shell.pages.home.HomePageCallbacks
import com.letta.mobile.ui.shell.pages.home.HomePageOptions
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.shell.shellHomePage
import kotlinx.coroutines.launch

/** The phone presentation of the shared Home page: the shell header and drawer carry title and search. */
private val AndroidHomeOptions = HomePageOptions(showTitle = false, showSearch = false, touch = true)

/**
 * The Admin tab's home: the shared shell header (search, backend label, settings) and admin drawer around
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
    onNavigateToProviders: () -> Unit = {},
    onNavigateToSchedules: () -> Unit = {},
    onNavigateToRuns: () -> Unit = {},
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
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val navigation = remember(
        onNavigateToAgents, onNavigateToConversations, onNavigateToTools, onNavigateToBlocks,
        onNavigateToSettings, onNavigateToChat, onNavigateToUsage, onNavigateToTemplates,
        onNavigateToProviders, onNavigateToSchedules, onNavigateToRuns,
        onNavigateToMcp, onNavigateToAbout, onNavigateToTelemetry,
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
            onNavigateToProviders = onNavigateToProviders,
            onNavigateToSchedules = onNavigateToSchedules,
            onNavigateToRuns = onNavigateToRuns,
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
        // Edge to edge: the shared page frame owns the system-bar and keyboard insets
        // (letta-mobile-ckmub.2.1); the shared shell header sits below the status bar while the
        // page colour runs up behind it. Its search slot searches this Home view in place.
        val search = rememberShellSearchSlot(
            home = viewModel.actions,
            onHome = true,
            sources = UnifiedSearchSources(),
            onOpen = {},
        )
        LettaPageScaffold(
            containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
            header = {
                ShellHeader(
                    // The backend label under the title opens the switcher, when this host can switch.
                    title = HeaderTitle.Text(title, activeBackendLabel, onSubtitleClick = onNavigateToBackendSwitcher),
                    search = search,
                    controls = HeaderControls(
                        nav = HeaderNav.Menu(onClick = { scope.launch { drawerState.open() } }),
                        actions = listOf(HeaderAction.Icon(LettaIcons.Settings, "Settings", onNavigateToSettings)),
                    ),
                )
            },
        ) { paddingValues ->
            val callbacks = HomePageCallbacks(viewModel.actions, navigation.homePageNavigation(state, routes))
            ShellPage(
                destination = ShellDestination.Home,
                deps = ShellPageDeps(home = shellHomePage(state, callbacks, AndroidHomeOptions)),
                modifier = Modifier.padding(paddingValues),
            )
        }
    }
}
