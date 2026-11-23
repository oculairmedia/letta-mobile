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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.a2ui.toA2uiSurfaceStateOrNull
import com.letta.mobile.data.home.FleetRecentConversation
import com.letta.mobile.data.home.HomePageActions
import com.letta.mobile.data.home.HomePageState
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.data.home.HomeViews
import com.letta.mobile.data.home.greeting.GREETING_CAST_SIZE
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.ui.a2ui.A2uiSurfaceRenderer
import com.letta.mobile.ui.components.LettaRefreshable
import com.letta.mobile.ui.components.lettaPageGutter
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The home lane's sections in order ([HomeViews]). The shell workspace provides it from its stored
 * layout; outside a workspace it is every section in the default order.
 */
internal val LocalHomeLane = compositionLocalOf { HomeViews.Default }

/**
 * The Home page shared by Android, desktop and web: the mobile dashboard
 * (global search, recent conversations, pinned shortcuts and agents, backend counters and a
 * quick-chat composer) merged with the desktop fleet view (recent conversations across every agent
 * and the sortable agent table).
 *
 * All state lives in [HomePageState] (sharedLogic's HomePageController); the controller's actions
 * and the host's navigation go out through [callbacks] and presentation choices come in through
 * [options]. At or above [LettaDimens.Pane.wideBreakpoint] the page is one scrolling column with the
 * composer up top and the full fleet table; below it the composer docks at the bottom like a phone
 * chat bar. Either way the page leads with running work and recent conversations, then an agent
 * avatar row and a one-line stats strip ([homeSections]); counters never repeat.
 *
 * Which sections show, and their order, is the shell layout's home lane, which the workspace that
 * draws this page provides in [LocalHomeLane]. The page frame keeps the header, the search results that replace the sections, refresh and
 * the pinned-tile editor's state; a compact page docks the composer below the list whenever the
 * lane holds it, wherever the lane puts it.
 *
 * [HomePageOptions.document] is the Letta Code mod seam: a recognised A2UI document replaces the
 * native page.
 * Entering this page requests fresh aggregates once per composition lifetime; Retry is shown for a backend error.
 */
@Composable
fun HomePage(
    state: HomePageState,
    callbacks: HomePageCallbacks,
    modifier: Modifier = Modifier,
    options: HomePageOptions = HomePageOptions(),
) {
    // Entry expresses demand without bypassing the aggregate TTL; pull and Retry still force refresh.
    LaunchedEffect(callbacks.actions) { callbacks.actions.loadIfStale() }
    val documentSurface = remember(options.document) { options.document?.toA2uiSurfaceStateOrNull() }
    val base = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(HomePageTags.PAGE)
    if (documentSurface != null) {
        Box(base.verticalScroll(rememberScrollState()).padding(horizontal = LettaDimens.Space.xxl, vertical = LettaDimens.Space.xl)) {
            A2uiSurfaceRenderer(surface = documentSurface, modifier = Modifier.fillMaxWidth(), onAction = callbacks.navigation.onA2uiAction)
        }
        return
    }
    var editingPins by remember { mutableStateOf(false) }
    val lane = LocalHomeLane.current
    BoxWithConstraints(base) {
        val page = HomePageScope(
            state = state,
            callbacks = callbacks,
            options = options,
            lane = lane,
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
 * Host presentation choices: [showTitle] false when the host already titles the screen (the shell
 * binding clears it under a window header that names the page);
 * [showSearch] false when the host's own app bar carries the search; [touch] grows the controls to
 * touch-target size; [orbIndexByAgentId] colours the agent orbs; [headerActions] are the host's own
 * header controls (such as a backend chip); [document] replaces the page with a mod's
 * A2UI document when it is one the renderer recognises.
 */
@Immutable
data class HomePageOptions(
    val showTitle: Boolean = true,
    val showSearch: Boolean = true,
    val touch: Boolean = false,
    val composerPlaceholder: String = "Message your agent",
    /**
     * The prompt bar: the shared chat composer, drawn with [composerPlaceholder] and handing its
     * draft to [HomePageNavigation.onSubmitPrompt]. The shell's Home binding supplies it; null
     * leaves Home without one.
     */
    val promptBar: (@Composable (placeholder: String, onSubmit: (String) -> Unit, modifier: Modifier) -> Unit)? = null,
    val orbIndexByAgentId: Map<String, Int> = emptyMap(),
    val headerActions: (@Composable RowScope.() -> Unit)? = null,
    val document: UiGeneratedComponent? = null,
    /** Home's hello (the line, the mark and a few mascots) above the sections; null leaves it out. */
    val greeting: HomeGreetingOptions? = null,
)

internal class HomePageScope(
    val state: HomePageState,
    callbacks: HomePageCallbacks,
    val options: HomePageOptions,
    /** The home lane's sections in order ([homeSections] draws them). */
    val lane: List<RuntimeViewId>,
    val wide: Boolean,
    val editingPins: Boolean,
    val onEditingPinsChange: (Boolean) -> Unit,
) {
    val actions: HomePageActions = callbacks.actions
    val navigation: HomePageNavigation = callbacks.navigation
    /** The shared page gutter, so the rows line up under [HomeHeader]'s title. */
    val horizontalPadding = lettaPageGutter(wide)

    /** A phone host that titles and searches in its own app bar still shows the error banner here. */
    val hasCompactHeader: Boolean
        get() = drawsHeaderControls || state.stats.error != null

    private val drawsHeaderControls: Boolean
        get() = options.showTitle || options.showSearch

    /** The lane holds the composer and the host gave one; a compact page docks it below the list. */
    val hasComposer: Boolean
        get() = HomeViews.COMPOSER in lane && options.promptBar != null

    fun orbIndex(agentId: String?): Int = agentId?.let { options.orbIndexByAgentId[it] } ?: 0

    /** This host can open [shortcut]'s page, so a "See all" or a figure may link there. */
    fun offers(shortcut: HomeShortcut): Boolean = shortcut in state.availableShortcuts
}

/** The greeting first, when the host asked for it, then the home lane's sections. */
private fun LazyListScope.homeGreetingAndSections(page: HomePageScope) {
    page.options.greeting?.let { greeting ->
        item(key = "greeting") {
            HomeGreeting(page.state.avatarRowAgents().take(GREETING_CAST_SIZE), greeting, hydrating = page.state.stats.loading)
        }
    }
    homeSections(page)
}

/**
 * Touch hosts also support an explicit pull. Page entry and reconnection refresh automatically.
 */
@Composable
private fun HomeRefreshable(page: HomePageScope, modifier: Modifier, content: @Composable () -> Unit) {
    LettaRefreshable(refreshing = page.state.stats.loading, onRefresh = page.actions::refresh, modifier = modifier) { content() }
}

@Composable
private fun WideHomeLayout(page: HomePageScope) {
    // The page header stays put above the list, as on every other page, and insets itself.
    Column(Modifier.fillMaxSize()) {
        HomeHeader(page)
        HomeRefreshable(page, Modifier.weight(1f).fillMaxWidth()) { WideHomeList(page) }
    }
}

@Composable
private fun WideHomeList(page: HomePageScope) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        contentPadding = PaddingValues(
            start = page.horizontalPadding,
            end = page.horizontalPadding,
            top = LettaDimens.Space.sm,
            bottom = LettaDimens.Space.xl,
        ),
    ) {
        if (page.state.search.isActive) homeSearchResults(page) else homeGreetingAndSections(page)
    }
}

/**
 * The phone page: the header, then the list with the chat composer docked over its bottom edge. The
 * list runs on under the bar, as the compact chat's timeline does, and pads its end by the bar's
 * height, so its last row clears the bar and nothing is cut off at the bar's rounded corners.
 */
@Composable
private fun CompactHomeLayout(page: HomePageScope) {
    val docked = !page.state.search.isActive && page.hasComposer
    var barHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    Column(Modifier.fillMaxSize()) {
        if (page.hasCompactHeader) HomeHeader(page)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HomeRefreshable(page, Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
                    contentPadding = PaddingValues(
                        start = page.horizontalPadding,
                        end = page.horizontalPadding,
                        top = LettaDimens.Space.sm,
                        bottom = LettaDimens.Space.lg + if (docked) barHeight else 0.dp,
                    ),
                ) {
                    if (page.state.search.isActive) homeSearchResults(page) else homeGreetingAndSections(page)
                }
            }
            // The chat composer runs edge to edge at the bottom, as on the compact chat page.
            if (docked) {
                HomePromptBar(
                    page,
                    Modifier.align(Alignment.BottomCenter).onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                )
            }
        }
    }
}
