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
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The Home page shared by desktop and Android (letta-mobile-c3np7.3.11): the mobile dashboard
 * (global search, a drag-to-reorder grid of pinned shortcuts and agents, backend stat widgets and a
 * quick-chat composer) merged with the desktop fleet view (recent conversations across every agent
 * and the sortable agent table).
 *
 * All state lives in [HomePageState] (sharedLogic's HomePageController); the controller's actions
 * and the host's navigation go out through [callbacks] and presentation choices come in through
 * [options]. At or above [LettaDimens.Pane.wideBreakpoint] the page is one scrolling column with the
 * composer up top and the full fleet table; below it the composer docks at the bottom like a phone
 * chat bar and the fleet collapses to compact rows with sort chips.
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

    fun orbIndex(agentId: String?): Int = agentId?.let { options.orbIndexByAgentId[it] } ?: 0
}

@Composable
private fun WideHomeLayout(page: HomePageScope) {
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
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
            contentPadding = PaddingValues(horizontal = page.horizontalPadding, vertical = LettaDimens.Space.lg),
        ) {
            if (page.options.showTitle || page.options.showSearch || page.state.stats.error != null) item { HomeHeader(page) }
            if (page.state.search.isActive) homeSearchResults(page) else homeDashboard(page)
        }
        if (!page.state.search.isActive) {
            HomeComposer(page, Modifier.padding(horizontal = page.horizontalPadding, vertical = LettaDimens.Space.sm))
        }
    }
}

/** Pins first (what the user chose), then the counters, then what the fleet has been doing. */
private fun LazyListScope.homeDashboard(page: HomePageScope) {
    homePinnedSection(page)
    item { HomeStatTiles(page) }
    homeRecentConversations(page)
    homeFleetSection(page)
}
