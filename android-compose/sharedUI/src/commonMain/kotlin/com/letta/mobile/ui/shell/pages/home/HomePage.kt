package com.letta.mobile.ui.shell.pages.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.a2ui.toA2uiSurfaceStateOrNull
import com.letta.mobile.data.home.FleetRecentConversation
import com.letta.mobile.data.home.HomePageActions
import com.letta.mobile.data.home.HomePageState
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.ui.a2ui.A2uiSurfaceRenderer
import com.letta.mobile.ui.components.LettaRefreshable
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The Home page shared by desktop and Android: the mobile dashboard
 * (global search, a drag-to-reorder grid of pinned shortcuts and agents, backend stat widgets and a
 * quick-chat composer) merged with the desktop fleet view (recent conversations across every agent
 * and the sortable agent table).
 *
 * All state lives in [HomePageState] (sharedLogic's HomePageController); the controller's actions
 * and the host's navigation go out through [callbacks] and presentation choices come in through
 * [options]. At or above [LettaDimens.Pane.wideBreakpoint] the page is one scrolling column with the
 * composer up top and the full fleet table; below it the composer docks at the bottom like a phone
 * chat bar and the page leads with running work and recent conversations, then an agent avatar row
 * and a one-line stats strip ([homeCompactDashboard]).
 *
 * [HomePageOptions.document] is the Letta Code mod seam: a recognised A2UI document replaces the
 * native page.
 */
@Composable
fun HomePage(
    state: HomePageState,
    callbacks: HomePageCallbacks,
    modifier: Modifier = Modifier,
    options: HomePageOptions = HomePageOptions(),
) {
    val documentSurface = remember(options.document) { options.document?.toA2uiSurfaceStateOrNull() }
    val base = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(HomePageTags.PAGE)
    if (documentSurface != null) {
        Box(base.verticalScroll(rememberScrollState()).padding(horizontal = LettaDimens.Space.xxl, vertical = LettaDimens.Space.xl)) {
            A2uiSurfaceRenderer(surface = documentSurface, modifier = Modifier.fillMaxWidth(), onAction = callbacks.navigation.onA2uiAction)
        }
        return
    }
    var editingPins by remember { mutableStateOf(false) }
    BoxWithConstraints(base) {
        val page = HomePageScope(
            state = state,
            callbacks = callbacks,
            options = options,
            wide = maxWidth >= LettaDimens.Pane.wideBreakpoint,
            editingPins = editingPins,
            onEditingPinsChange = { editingPins = it },
        )
        if (page.wide) WideHomeLayout(page) else CompactHomeLayout(page)
    }
}

/** Everything the page calls out to: the controller's [actions] and the host's [navigation]. */
@Immutable
data class HomePageCallbacks(
    val actions: HomePageActions,
    val navigation: HomePageNavigation,
)

/** Where the page sends the user. Optional destinations a host cannot open stay null or no-op. */
@Immutable
data class HomePageNavigation(
    /** Send the composer's text into the host's chat pipeline. */
    val onSubmitPrompt: (String) -> Unit,
    val onOpenConversation: (FleetRecentConversation) -> Unit,
    val onOpenAgent: (String) -> Unit,
    val onOpenShortcut: (HomeShortcut) -> Unit,
    /** Edit an agent's settings; null hides the configure action on pinned agents. */
    val onConfigureAgent: ((String) -> Unit)? = null,
    val onOpenMessage: (ParsedSearchMessage) -> Unit = {},
    val onOpenTool: (String) -> Unit = {},
    val onOpenBlock: (String) -> Unit = {},
    val onA2uiAction: (A2uiAction) -> Unit = {},
)

/**
 * Host presentation choices: [showTitle] false when the host already titles the screen;
 * [showSearch] false when the host's own app bar carries the search; [touch] grows the controls to
 * touch-target size; [orbIndexByAgentId] colours the agent orbs; [headerActions] are the host's own
 * header controls (a backend chip, a refresh button); [document] replaces the page with a mod's
 * A2UI document when it is one the renderer recognises.
 */
@Immutable
data class HomePageOptions(
    val showTitle: Boolean = true,
    val showSearch: Boolean = true,
    val touch: Boolean = false,
    val composerPlaceholder: String = "Message your agent",
    val orbIndexByAgentId: Map<String, Int> = emptyMap(),
    val headerActions: (@Composable RowScope.() -> Unit)? = null,
    val document: UiGeneratedComponent? = null,
)

internal class HomePageScope(
    val state: HomePageState,
    callbacks: HomePageCallbacks,
    val options: HomePageOptions,
    val wide: Boolean,
    val editingPins: Boolean,
    val onEditingPinsChange: (Boolean) -> Unit,
) {
    val actions: HomePageActions = callbacks.actions
    val navigation: HomePageNavigation = callbacks.navigation
    val horizontalPadding = if (wide) LettaDimens.Space.xxl else LettaDimens.Space.lg

    /** A phone host that titles and searches in its own app bar still shows the error banner here. */
    val hasCompactHeader: Boolean
        get() = drawsHeaderControls || state.stats.error != null

    private val drawsHeaderControls: Boolean
        get() = options.showTitle || options.showSearch

    fun orbIndex(agentId: String?): Int = agentId?.let { options.orbIndexByAgentId[it] } ?: 0

    /** This host can open [shortcut]'s page, so a "See all" or a figure may link there. */
    fun offers(shortcut: HomeShortcut): Boolean = shortcut in state.availableShortcuts
}

/**
 * The page reloads through [LettaRefreshable]: a pull on touch, the header's
 * refresh on a pointer host. The controller reports no refresh in progress, so the pull springs back.
 */
@Composable
private fun HomeRefreshable(page: HomePageScope, modifier: Modifier, content: @Composable () -> Unit) {
    LettaRefreshable(refreshing = false, onRefresh = page.actions::refresh, modifier = modifier) { content() }
}

@Composable
private fun WideHomeLayout(page: HomePageScope) {
    HomeRefreshable(page, Modifier.fillMaxSize()) { WideHomeList(page) }
}

@Composable
private fun WideHomeList(page: HomePageScope) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        contentPadding = PaddingValues(horizontal = page.horizontalPadding, vertical = LettaDimens.Space.xl),
    ) {
        item { HomeHeader(page) }
        if (page.state.search.isActive) {
            homeSearchResults(page)
        } else {
            item { HomeComposer(page) }
            homeDashboard(page)
        }
    }
}

@Composable
private fun CompactHomeLayout(page: HomePageScope) {
    Column(Modifier.fillMaxSize()) {
        HomeRefreshable(page, Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
                contentPadding = PaddingValues(horizontal = page.horizontalPadding, vertical = LettaDimens.Space.lg),
            ) {
                if (page.hasCompactHeader) item { HomeHeader(page) }
                if (page.state.search.isActive) homeSearchResults(page) else homeCompactDashboard(page)
            }
        }
        if (!page.state.search.isActive) {
            HomeComposer(page, Modifier.padding(horizontal = page.horizontalPadding, vertical = LettaDimens.Space.sm))
        }
    }
}

/** Wide: pins first (what the user chose), then the counters, then what the fleet has been doing. */
private fun LazyListScope.homeDashboard(page: HomePageScope) {
    homePinnedSection(page)
    item { HomeStatTiles(page) }
    homeRecentConversations(page)
    homeFleetSection(page)
}
