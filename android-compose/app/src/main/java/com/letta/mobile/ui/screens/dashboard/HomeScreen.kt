package com.letta.mobile.ui.screens.dashboard

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/** The backend label under the Home title and the switcher it opens, when this host can switch. */
internal data class HomeBackendLabel(val label: String?, val onOpenSwitcher: (() -> Unit)?)

/** What a Home host passes: where everything leads, the backend label and the title. */
internal data class HomeScreenSpec(
    val navigation: HomeNavigationCallbacks,
    val routes: HomeChatRoutes,
    val backend: HomeBackendLabel = HomeBackendLabel(null, null),
    val title: String = "Letta",
)

/**
 * The Admin tab's home: the shared shell header (search, backend label, settings) and admin drawer around
 * the shared Home page (letta-mobile-c3np7.3.11.1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(spec: HomeScreenSpec, viewModel: DashboardViewModel = hiltViewModel()) {
    val (navigation, routes, backend, title) = spec
    val state by viewModel.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val onNavigateToSettings = navigation.onNavigateToSettings

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
            context = com.letta.mobile.ui.shell.header.ShellSearchContext(onHome = true),
            sources = UnifiedSearchSources(),
            onOpen = {},
        )
        LettaPageScaffold(
            containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
            header = {
                ShellHeader(
                    // The backend label under the title opens the switcher, when this host can switch.
                    title = HeaderTitle.Text(title, backend.label, onSubtitleClick = backend.onOpenSwitcher),
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
