package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.chat.runtime.displayTitle
import com.letta.mobile.data.desktopshell.ConversationTabsReducer
import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.home.HomeShellSearch
import com.letta.mobile.data.home.HomeShellSearchState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.ui.search.LettaSearchConfig
import com.letta.mobile.ui.search.LettaSearchRow
import com.letta.mobile.ui.search.LettaSearchSection
import com.letta.mobile.ui.search.LettaSearchToggle
import com.letta.mobile.ui.shell.pages.home.HomeSearchPlaceholder

/**
 * Header unified search: one query across conversations, agents and canvases. "Filter to this
 * agent" starts ON, which is the constrained behaviour the tab picker had; unticking it widens
 * the search to everything. The query itself lives in [HomeShellSearch], which hands it to the
 * Home page while Home is showing (letta-mobile-xuhmy).
 */
@Stable
internal class DesktopHeaderSearchState {
    var scopeId: String by mutableStateOf(DesktopUnifiedSearch.ALL)
    var filterToAgent: Boolean by mutableStateOf(true)
}

/**
 * Header chrome (letta-mobile-3arhe.1): the agent-first identity block (was a footer bar) plus
 * the sidebar toggle (letta-mobile-o5m90), lifted up to Main.kt/DesktopJewelWindow's custom title
 * bar the same way onActiveTitleChange already lifts the window title - that title bar is a
 * composition sibling of the shell, not a descendant, so it cannot read this state directly.
 */
@Composable
internal fun DesktopShellHeaderChrome(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
    tabs: MutableState<ConversationTabsState>,
    onHeaderChromeChange: (DesktopHeaderChromeState) -> Unit,
) {
    val router = context.router
    val layout = context.core.layout
    val search = remember { DesktopHeaderSearchState() }
    val homeSearch = rememberHomeShellSearch(context)
    val headerIdentity = desktopShellHeaderIdentity(context, frame)
    val headerChrome = DesktopHeaderChromeState(
        identity = headerIdentity.state,
        identityActions = headerIdentity.actions,
        sidebarCollapsed = !layout.controller.state.isSidebarVisible,
        onToggleSidebar = { layout.controller.dispatch(ShellLayoutEvent.ToggleSidebar) },
        sidebarToggleFocusRequester = layout.sidebarToggleFocusRequester,
        sidebarOverflow = desktopSidebarOverflow(context, frame),
        conversationTabs = rememberConversationTabs(tabs.value, frame.chatState.conversations),
        activeConversationId = frame.chatState.selectedConversationId,
        onSelectConversationTab = { router.openConversation(ConversationId(it)) },
        onCloseConversationTab = { router.closeConversationTab(tabs, ConversationId(it)) },
        onReorderConversationTab = { conversationId, targetIndex ->
            tabs.value = ConversationTabsReducer.reorder(tabs.value, conversationId, targetIndex)
        },
        // Browser-style "+" and picker on the strip: a new chat with the focused agent, and
        // that agent's conversations and canvases, most recent first, behind a search field.
        onNewConversationTab = { context.openNewChatForFocusedAgent(frame.focus) },
        search = desktopHeaderSearch(context, frame, HeaderSearchHolders(search, homeSearch)),
    )
    SideEffect { onHeaderChromeChange(headerChrome) }
}

@Composable
private fun desktopShellHeaderIdentity(context: DesktopShellContext, frame: DesktopShellFrame): DesktopHeaderIdentity {
    val chatController = context.core.chatController
    return DesktopNowActiveBarHost(
        chatController = chatController,
        chatState = frame.chatState,
        host = NowActiveBarHostState(
            thinkingConversationId = frame.activity.thinkingConversationId,
            isStreamingReplySelected = frame.activity.isStreamingReplySelected,
            avatarStyleByAgentId = frame.focus.avatarStyleByAgentId,
            fallbackOrbIndex = frame.focus.selectedAgentOrbIndex,
        ),
        actions = NowActiveBarHostActions(
            onOpenConversation = { context.router.openConversation(ConversationId(it)) },
            onStopRun = chatController::stopActiveRun,
        ),
    )
}

/** The open tabs, in strip order, for the conversations the chat surface still knows. */
@Composable
private fun rememberConversationTabs(
    tabs: ConversationTabsState,
    conversations: List<DesktopConversationSummary>,
): List<DesktopConversationTab> {
    val conversationById = remember(conversations) { conversations.associateBy { it.id } }
    return remember(tabs, conversationById) {
        tabs.openConversationIds.mapNotNull { conversationId ->
            conversationById[conversationId]?.let { conversation ->
                DesktopConversationTab(
                    conversationId = conversation.id,
                    title = conversation.displayTitle(),
                    agentName = conversation.agentName,
                )
            }
        }
    }
}

/** The collapsed sidebar's overflow menu in the header. */
private fun desktopSidebarOverflow(context: DesktopShellContext, frame: DesktopShellFrame): DesktopHeaderSidebarOverflow {
    val navigator = context.navigator
    return DesktopHeaderSidebarOverflow(
        mode = navigator.workPlayMode,
        onNewChat = { context.openNewChatForFocusedAgent(frame.focus) },
        onDestination = { lensDestination ->
            navigator.navigate(lensNavTarget(navigator.workPlayMode, lensDestination).first)
        },
        onSettings = { navigator.navigate(DesktopDestination.Settings) },
    )
}

/** The header field's two halves: the unified search's scope/toggle and the Home-aware query. */
private class HeaderSearchHolders(val filters: DesktopHeaderSearchState, val query: HomeShellSearch)

/**
 * The header query's router to the Home page, told which page is showing: on Home the header
 * searches the Home view instead of drawing its own dropdown (letta-mobile-xuhmy).
 */
@Composable
private fun rememberHomeShellSearch(context: DesktopShellContext): HomeShellSearch {
    val home = context.panels.libraries.home
    val homeSearch = remember(home) { HomeShellSearch(home) }
    val onHome = context.navigator.selectedDestination == DesktopDestination.Home
    LaunchedEffect(homeSearch, onHome) { homeSearch.setOnHome(onHome) }
    return homeSearch
}

@Composable
private fun desktopHeaderSearch(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
    holders: HeaderSearchHolders,
): DesktopHeaderSearch {
    val homeSearch = holders.query
    val queryState by homeSearch.state.collectAsState()
    val sections = rememberUnifiedSearchSections(context, frame, holders.filters, queryState)
    return DesktopHeaderSearch(
        query = queryState.query,
        onQueryChange = homeSearch::updateQuery,
        sections = sections,
        onRowSelected = { row ->
            homeSearch.resultOpened()
            context.openSearchRow(row)
        },
        onDismiss = homeSearch::dismiss,
        config = headerSearchConfig(holders.filters, showsShellResults = queryState.showsShellResults),
    )
}

/** The unified dropdown's rows; none on Home, where the page itself shows the matches. */
@Composable
private fun rememberUnifiedSearchSections(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
    search: DesktopHeaderSearchState,
    queryState: HomeShellSearchState,
): List<LettaSearchSection> {
    val canvasDocuments by context.core.canvasShell.library.documents.collectAsState()
    val focus = frame.focus
    val conversations = frame.chatState.conversations
    return remember(
        queryState,
        search.scopeId,
        search.filterToAgent,
        conversations,
        focus.rosterAgents,
        canvasDocuments,
        focus.selectedAgentId,
        focus.avatarStyleByAgentId,
    ) {
        if (!queryState.showsShellResults) {
            emptyList()
        } else {
            DesktopUnifiedSearch.sections(
                query = queryState.query,
                conversations = conversations,
                agents = focus.rosterAgents,
                canvases = canvasDocuments,
                avatarStyleByAgentId = focus.avatarStyleByAgentId,
                agentId = focus.selectedAgentId,
                filterToAgent = search.filterToAgent,
                scopeId = search.scopeId,
            )
        }
    }
}

private fun DesktopShellContext.openSearchRow(row: LettaSearchRow) {
    val (kind, id) = DesktopUnifiedSearch.parseRowId(row.id) ?: return
    when (kind) {
        DesktopUnifiedSearch.CONVERSATIONS -> router.openConversation(ConversationId(id))
        DesktopUnifiedSearch.AGENTS -> router.openAgent(AgentId(id))
        DesktopUnifiedSearch.CANVASES -> openCanvas(CanvasId(id))
    }
}

private fun headerSearchConfig(search: DesktopHeaderSearchState, showsShellResults: Boolean): LettaSearchConfig {
    val unified = LettaSearchConfig(
        placeholder = "Search agents, conversations, canvases",
        // The header field is always present; stealing focus on
        // every recomposition would fight the composer for the caret.
        autoFocus = false,
        scopes = DesktopUnifiedSearch.scopes,
        selectedScopeId = search.scopeId,
        onScopeSelected = { search.scopeId = it },
        toggle = LettaSearchToggle(
            label = "Filter to this agent",
            checked = search.filterToAgent,
            onCheckedChange = { search.filterToAgent = it },
        ),
    )
    // On Home the field searches the page: Home's own wording, and no scopes or agent filter,
    // which only shape the unified dropdown.
    return if (showsShellResults) unified else HomeHeaderSearchConfig
}

private val HomeHeaderSearchConfig = LettaSearchConfig(
    placeholder = HomeSearchPlaceholder,
    autoFocus = false,
    showResults = false,
)
