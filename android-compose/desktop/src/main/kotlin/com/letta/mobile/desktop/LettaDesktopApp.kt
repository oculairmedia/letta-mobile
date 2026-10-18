package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.chat.runtime.displayTitle
import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.shell.rememberShellWorkspaceState
import com.letta.mobile.ui.components.tabs.LettaTab
import com.letta.mobile.ui.shell.header.SearchSlot
import com.letta.mobile.ui.shell.header.ShellSearchContext
import com.letta.mobile.ui.shell.header.rememberShellSearchSlot
import com.letta.mobile.ui.shell.workspace.openSearchHit
import com.letta.mobile.ui.shell.workspace.ShellConversationTabs
import com.letta.mobile.ui.shell.workspace.ShellSidebarControl
import com.letta.mobile.ui.shell.workspace.ShellSidebarOverflow
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderState
import com.letta.mobile.desktop.chat.DesktopChatConnectionState
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.runtime.runDesktopMemoryPressure
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

/** The workspace header's state, and where the window's title bar takes it. */
internal class DesktopShellHeader(
    val state: ShellWorkspaceHeaderState,
    val onHoisted: (ShellWorkspaceHeaderState) -> Unit,
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
    onHeaderChromeChange: (ShellWorkspaceHeaderState) -> Unit = {},
) {
    // Launch on the fleet dashboard: it is the only view that says something
    // before a conversation is selected.
    LaunchedEffect(Unit) { runDesktopMemoryPressure() }
    val navigator = rememberShellWorkspaceState(ShellDestination.Home)
    val context = rememberDesktopShellContext(shell, navigator)
    val chatState = context.chatState.value
    val frame = rememberDesktopShellFrame(context, chatState)
    DesktopShellEffects(context, frame)
    DesktopShellRoutingEffects(context, frame, onActiveTitleChange)
    val workingDirectory = context.core.chatController.workingDirectory.current.collectAsState().value?.path
    DesktopMaterialTheme {
        DesktopWorkspaceFileViewerHost(context.core.chatScope, workingDirectory) {
            // The workspace hoists its header into the window title bar, a sibling of the shell (letta-mobile-3arhe.1).
            DesktopShellWindowContent(context, frame, DesktopShellHeader(rememberDesktopShellHeader(context, frame), onHeaderChromeChange))
        }
    }
}

/**
 * The workspace header's state: the identity block, sidebar toggle and overflow, the tab strip, and
 * the shared shell search slot (letta-mobile-ckmub.3.5), which searches Home in place while Home shows.
 */
@Composable
private fun rememberDesktopShellHeader(context: DesktopShellContext, frame: DesktopShellFrame): ShellWorkspaceHeaderState {
    val layout = context.core.layout
    val identity = desktopHeaderIdentity(context, frame)
    return ShellWorkspaceHeaderState(
        title = desktopActiveTitle(context.navigator.selectedDestination, frame.chatState.selectedConversation?.title),
        identity = identity.state,
        identityActions = identity.actions,
        sidebar = ShellSidebarControl(
            collapsed = !layout.controller.state.isSidebarVisible,
            onToggle = { layout.controller.dispatch(ShellLayoutEvent.ToggleSidebar) },
            focusRequester = layout.sidebarToggleFocusRequester,
            overflow = desktopSidebarOverflow(context, frame),
        ),
        search = rememberDesktopShellSearch(context, frame),
        tabs = ShellConversationTabs(
            tabs = rememberOpenTabs(context.navigator.tabs, frame.chatState.conversations),
            activeKey = frame.chatState.selectedConversationId,
            actions = desktopTabActions(context, frame),
        ),
    )
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
private fun desktopSidebarOverflow(context: DesktopShellContext, frame: DesktopShellFrame): ShellSidebarOverflow {
    val navigator = context.navigator
    return ShellSidebarOverflow(
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
