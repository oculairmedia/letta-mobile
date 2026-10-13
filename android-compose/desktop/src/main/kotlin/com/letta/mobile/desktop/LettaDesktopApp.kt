package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.letta.mobile.data.chat.runtime.displayTitle
import com.letta.mobile.data.desktopshell.ConversationTabsReducer
import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.components.tabs.LettaTab
import com.letta.mobile.ui.components.tabs.LettaTabStripActions
import com.letta.mobile.ui.shell.header.SearchSlot
import com.letta.mobile.ui.shell.header.ShellSearchContext
import com.letta.mobile.ui.shell.header.rememberShellSearchSlot
import com.letta.mobile.desktop.chat.DesktopChatConnectionState
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.workspace.DesktopWorkspaceFileViewerHost
import dev.nucleusframework.application.NucleusApplicationScope
import java.awt.Window
import kotlinx.coroutines.flow.StateFlow

/** Application-scoped inputs the desktop shell composes over. */
internal data class DesktopAppShellBindings(
    val nucleusApplicationScope: NucleusApplicationScope,
    val window: Window,
    val deepLinks: StateFlow<DesktopDeepLinkRequest?>,
    val quickQuery: DesktopQuickQueryCoordinator,
)

/**
 * The desktop shell. letta-mobile-bglj6.1.10: it only assembles the pieces - the services and
 * navigation ([rememberDesktopShellContext]), the state derived from them each composition
 * ([rememberDesktopShellFrame]), the effects, the window content and the header chrome.
 */
@Composable
internal fun LettaDesktopApp(
    shell: DesktopAppShellBindings,
    onActiveTitleChange: (String) -> Unit = {},
    onHeaderChromeChange: (DesktopHeaderChromeState) -> Unit = {},
) {
    // Launch on the fleet dashboard: it is the only view that says something
    // before a conversation is selected.
    val selectedDestination = rememberSaveable { mutableStateOf(DesktopDestination.Home) }
    // Spotify-style library toggle: icon rail <-> expanded names-and-spaces list.
    val railExpanded = rememberSaveable { mutableStateOf(false) }
    val navigator = remember { DesktopShellNavigator(selectedDestination, railExpanded) }
    val context = rememberDesktopShellContext(shell, navigator)
    val chatState = context.chatState.value
    val conversationTabs = remember(chatState.sessionGraphId) { mutableStateOf(ConversationTabsState()) }
    val frame = rememberDesktopShellFrame(context, chatState)
    DesktopShellEffects(context, frame, conversationTabs)
    DesktopShellRoutingEffects(context, frame, onActiveTitleChange)
    val workingDirectory by context.core.chatController.selectedConversationWorkingDirectory.collectAsState()
    DesktopMaterialTheme {
        DesktopWorkspaceFileViewerHost(context.core.chatScope, workingDirectory) {
            DesktopShellWindowContent(context, frame)
        }
        DesktopShellHeaderChrome(context, frame, conversationTabs, onHeaderChromeChange)
    }
}

/**
 * Lifts the header's state to the window title bar, which composes as a sibling of the shell
 * (letta-mobile-3arhe.1): the identity block, sidebar toggle and overflow, the tab strip, and the
 * shared shell search slot (letta-mobile-ckmub.3.5), which searches Home in place while Home shows.
 */
@Composable
private fun DesktopShellHeaderChrome(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
    tabs: MutableState<ConversationTabsState>,
    onHeaderChromeChange: (DesktopHeaderChromeState) -> Unit,
) {
    val router = context.router
    val layout = context.core.layout
    val identity = desktopHeaderIdentity(context, frame)
    val headerChrome = DesktopHeaderChromeState(
        identity = identity.state,
        identityActions = identity.actions,
        sidebarCollapsed = !layout.controller.state.isSidebarVisible,
        onToggleSidebar = { layout.controller.dispatch(ShellLayoutEvent.ToggleSidebar) },
        sidebarToggleFocusRequester = layout.sidebarToggleFocusRequester,
        sidebarOverflow = desktopSidebarOverflow(context, frame),
        conversationTabs = rememberOpenTabs(tabs.value, frame.chatState.conversations),
        activeConversationId = frame.chatState.selectedConversationId,
        conversationTabActions = LettaTabStripActions(
            onSelect = { router.openConversation(ConversationId(it)) },
            onClose = { router.closeConversationTab(tabs, ConversationId(it)) },
            onReorder = { id, index -> tabs.value = ConversationTabsReducer.reorder(tabs.value, id, index) },
            // Browser-style "+" on the strip: a new chat with the focused agent.
            onNew = { context.openNewChatForFocusedAgent(frame.focus) },
            newLabel = "New conversation tab",
        ),
        search = rememberDesktopShellSearch(context, frame),
    )
    SideEffect { onHeaderChromeChange(headerChrome) }
}

/**
 * The shared header search over this window's unified search sources (conversations, message text,
 * roster, canvases). "Filter to this agent" starts on only while a chat is the page.
 */
@Composable
private fun rememberDesktopShellSearch(context: DesktopShellContext, frame: DesktopShellFrame): SearchSlot {
    val destination = context.navigator.selectedDestination
    return rememberShellSearchSlot(
        home = context.panels.libraries.home,
        context = ShellSearchContext(
            onHome = destination == DesktopDestination.Home,
            inChat = destination == DesktopDestination.Conversations,
        ),
        sources = frame.lists.search,
        onOpen = { hit -> context.router.openSearchHit(hit, context::openCanvas) },
    )
}

@Composable
private fun desktopHeaderIdentity(context: DesktopShellContext, frame: DesktopShellFrame): DesktopHeaderIdentity =
    DesktopNowActiveBarHost(
        chatController = context.core.chatController,
        chatState = frame.chatState,
        host = NowActiveBarHostState(
            thinkingConversationId = frame.activity.thinkingConversationId,
            isStreamingReplySelected = frame.activity.isStreamingReplySelected,
        ),
        actions = NowActiveBarHostActions(
            onOpenConversation = { context.router.openConversation(ConversationId(it)) },
            onStopRun = context.core.chatController::stopActiveRun,
        ),
    )

/** The open tabs, in strip order, for the conversations the chat surface still knows. */
@Composable
private fun rememberOpenTabs(tabs: ConversationTabsState, conversations: List<DesktopConversationSummary>): List<LettaTab> {
    val byId = remember(conversations) { conversations.associateBy { it.id } }
    return remember(tabs, byId) {
        tabs.openConversationIds.mapNotNull { id ->
            byId[id]?.let { LettaTab(key = it.id, title = it.displayTitle(), subtitle = it.agentName) }
        }
    }
}

/** The collapsed sidebar's overflow menu in the header. */
private fun desktopSidebarOverflow(context: DesktopShellContext, frame: DesktopShellFrame): DesktopHeaderSidebarOverflow {
    val navigator = context.navigator
    return DesktopHeaderSidebarOverflow(
        mode = navigator.workPlayMode,
        onNewChat = { context.openNewChatForFocusedAgent(frame.focus) },
        onDestination = { navigator.navigate(lensNavTarget(navigator.workPlayMode, it).first) },
        onSettings = { navigator.navigate(DesktopDestination.Settings) },
    )
}

/** The window title, and deep links into the app. */
@Composable
private fun DesktopShellRoutingEffects(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
    onActiveTitleChange: (String) -> Unit,
) {
    val navigator = context.navigator
    val chatState = frame.chatState
    val activeTitle = desktopActiveTitle(navigator.selectedDestination, chatState.selectedConversation?.title)
    LaunchedEffect(activeTitle) { onActiveTitleChange(activeTitle) }
    DesktopDeepLinkRouting(
        deepLinks = context.shell.deepLinks,
        chatState = chatState,
        actions = DesktopDeepLinkRoutingActions(
            // Deep links must win over the full-page agent editor, matching
            // the sidebar navigation paths that clear edit mode before routing.
            onDestinationSelected = navigator::navigate,
            onSelectConversation = { conversationId ->
                navigator.editAgentId = null
                context.core.chatController.selectConversation(conversationId)
            },
            onOpenAgent = { context.router.openAgent(AgentId(it)) },
            onPairLink = { navigator.pendingPairLink = it },
        ),
    )
}

private data class DesktopDeepLinkRoutingActions(
    val onDestinationSelected: (com.letta.mobile.data.shell.ShellDestination) -> Unit,
    val onSelectConversation: (String) -> Unit,
    val onOpenAgent: (String) -> Unit,
    val onPairLink: (String) -> Unit,
)

/**
 * Routes deep links into the app, buffering targets that arrive before the
 * conversation list has loaded (cold-start protocol activation):
 * selectConversation ignores unknown ids and openAgent would treat the empty
 * list as "no existing chat", so both wait for the initial load to settle.
 */
@Composable
private fun DesktopDeepLinkRouting(
    deepLinks: StateFlow<DesktopDeepLinkRequest?>,
    chatState: DesktopChatSurfaceState,
    actions: DesktopDeepLinkRoutingActions,
) {
    var pendingConversationId by remember { mutableStateOf<String?>(null) }
    var pendingAgentId by remember { mutableStateOf<String?>(null) }
    DesktopDeepLinkEffect(
        deepLinks = deepLinks,
        handlers = DesktopDeepLinkHandlers(
            onDestinationSelected = actions.onDestinationSelected,
            onConversationSelected = { pendingConversationId = it },
            onAgentSelected = { pendingAgentId = it },
            onPairLink = actions.onPairLink,
        ),
    )
    LaunchedEffect(pendingConversationId, chatState.conversations) {
        val target = pendingConversationId ?: return@LaunchedEffect
        if (chatState.conversations.any { it.id == target }) {
            actions.onSelectConversation(target)
            pendingConversationId = null
        }
    }
    LaunchedEffect(pendingAgentId, chatState.isLoading, chatState.connectionState) {
        val target = pendingAgentId ?: return@LaunchedEffect
        if (initialConversationLoadSettled(chatState)) {
            actions.onOpenAgent(target)
            pendingAgentId = null
        }
    }
}

private fun initialConversationLoadSettled(chatState: DesktopChatSurfaceState): Boolean {
    if (chatState.isLoading) return false
    return chatState.connectionState != DesktopChatConnectionState.Loading
}

private fun desktopActiveTitle(destination: DesktopDestination, conversationTitle: String?): String {
    if (destination != DesktopDestination.Conversations) return destination.label
    return conversationTitle ?: "Letta Desktop"
}
