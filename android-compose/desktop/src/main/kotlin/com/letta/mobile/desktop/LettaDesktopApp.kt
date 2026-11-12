package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.letta.mobile.data.chat.runtime.displayTitle
import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.CurrentFocusChatMode
import com.letta.mobile.data.shell.FocusAvailability
import com.letta.mobile.data.shell.ShellConversationRef
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.shell.rememberShellWorkspaceState
import com.letta.mobile.ui.canvas.boards.ProvideCanvasHostWithBoards
import com.letta.mobile.ui.canvas.mount.CloseCanvasPaneShownOnBoard
import com.letta.mobile.ui.components.tabs.LettaTab
import com.letta.mobile.ui.shell.header.SearchSlot
import com.letta.mobile.ui.shell.header.ShellSearchContext
import com.letta.mobile.ui.shell.header.rememberShellSearchSlot
import com.letta.mobile.ui.shell.workspace.openSearchHit
import com.letta.mobile.ui.shell.workspace.ShellConversationTabs
import com.letta.mobile.ui.shell.workspace.ShellSidebarControl
import com.letta.mobile.ui.shell.workspace.ShellSidebarOverflow
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderState
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.runtime.installDesktopMemoryPressure
import com.letta.mobile.desktop.workspace.DesktopWorkspaceFileViewerHost
import dev.nucleusframework.application.NucleusApplicationScope
import java.awt.Window
import kotlinx.coroutines.flow.MutableStateFlow

/** Application-scoped inputs the desktop shell composes over. */
internal data class DesktopAppShellBindings(
    val nucleusApplicationScope: NucleusApplicationScope,
    val window: Window,
    val deepLinks: MutableStateFlow<DesktopDeepLinkRequest?>,
    val quickQuery: DesktopQuickQueryCoordinator,
)

/** The workspace header's state, and where the window's title bar takes it. */
internal class DesktopShellHeader(
    val state: ShellWorkspaceHeaderState,
    val onHoisted: (ShellWorkspaceHeaderState) -> Unit,
)

/** Assemble desktop services, workspace state, effects, window content and header chrome. */
@Composable
internal fun LettaDesktopApp(
    shell: DesktopAppShellBindings,
    onActiveTitleChange: (String) -> Unit = {},
    onHeaderChromeChange: (ShellWorkspaceHeaderState) -> Unit = {},
) {
    // Launch on the fleet dashboard while no conversation is selected.
    DisposableEffect(Unit) { installDesktopMemoryPressure().let { onDispose(it::close) } }
    val navigator = rememberShellWorkspaceState(ShellDestination.Home)
    val context = rememberDesktopShellContext(shell, navigator)
    val chatState = context.chatState.value
    val frame = rememberDesktopShellFrame(context, chatState)
    DesktopShellEffects(context, frame)
    DesktopShellRoutingEffects(context, frame, onActiveTitleChange)
    val workingDirectory = context.core.chatController.workingDirectory.current.collectAsState().value?.path
    DesktopMaterialTheme {
        ProvideCanvasHostWithBoards(context.core.canvasShell.viewers, context.core.canvasShell.boards, context.core.canvasShell.picker) {
            // Entering canvas mode with the pane's board mounted puts it behind the chat: the pane copy closes.
            val canvasShell = context.core.canvasShell
            CloseCanvasPaneShownOnBoard(canvasShell.activeSession?.canvasId, canvasShell::close)
            DesktopWorkspaceFileViewerHost(context.core.chatScope, workingDirectory) {
                // Boards register where the canvas_* tools look and open by id for canvas modules; the header hoists into the title bar.
                val header = DesktopShellHeader(rememberDesktopShellHeader(context, frame), onHeaderChromeChange)
                DesktopShellWindowContent(context, frame, header)
            }
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
        title = desktopActiveTitle(context.navigator.selectedDestination,
            frame.chatState.conversations.firstOrNull { it.id == context.navigator.selectedConversationId?.value }?.title),
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
            tabs = rememberOpenTabs(context.navigator.tabs, frame.chatState.conversations, context.navigator),
            activeKey = context.navigator.selectedConversationId?.value,
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
        ownerConversationId = context.navigator.selectedConversationId,
        host = NowActiveBarHostState(
            thinkingConversationId = frame.activity.thinkingConversationId,
            isStreamingReplySelected = frame.activity.isStreamingReplySelected,
        ),
        actions = NowActiveBarHostActions(
            onOpenConversation = { context.router.openConversation(ConversationId(it)) },
            onStopRun = context.core.chatController::stopActiveRun,
        ),
    )
/** Keep every owner tab visible even while its catalog row is absent. */
@Composable
internal fun rememberOpenTabs(tabs: ConversationTabsState, conversations: List<DesktopConversationSummary>,
    workspace: ShellWorkspaceState? = null): List<LettaTab> {
    val byId = remember(conversations) { conversations.associateBy { it.id } }
    val unavailable = workspace?.selectedConversationId.takeIf { workspace?.conversationAvailability == FocusAvailability.Unavailable }
    return remember(tabs, byId, unavailable) {
        tabs.openConversationIds.map { id ->
            val row = byId[id]
            LettaTab(id, if (id == unavailable?.value) "Unavailable conversation" else row?.displayTitle() ?: "Conversation",
                row?.agentName, id == tabs.transientConversationId)
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
    val activeTitle = desktopActiveTitle(navigator.selectedDestination,
        chatState.conversations.firstOrNull { it.id == navigator.selectedConversationId?.value }?.title)
    LaunchedEffect(activeTitle) { onActiveTitleChange(activeTitle) }
    DesktopDeepLinkRouting(
        deepLinks = context.shell.deepLinks,
        actions = DesktopDeepLinkRoutingActions(
            onDestinationSelected = navigator::navigate,
            onPairLink = { navigator.pendingPairLink = it },
        ),
    )
    DesktopFocusProjection(context, chatState)
}

private data class DesktopDeepLinkRoutingActions(
    val onDestinationSelected: (ShellDestination) -> Unit,
    val onPairLink: (String) -> Unit,
)

@Composable
private fun DesktopDeepLinkRouting(
    deepLinks: MutableStateFlow<DesktopDeepLinkRequest?>,
    actions: DesktopDeepLinkRoutingActions,
) {
    DesktopDeepLinkEffect(
        deepLinks,
        DesktopDeepLinkHandlers(actions.onDestinationSelected, {}, {}, actions.onPairLink),
    )
}

/** Re-evaluate the owner target when the catalog or its source changes. */
@Composable
private fun DesktopFocusProjection(context: DesktopShellContext, chatState: DesktopChatSurfaceState) {
    val workspace = context.navigator
    LaunchedEffect(workspace.currentFocus, workspace.sourceKey, workspace.sourceGeneration, workspace.focusRevision,
        chatState.conversations, chatState.connectionState, chatState.isLoading) {
        if (workspace.page is ShellDestination.Chat && workspace.currentFocus.conversationId == null) {
            val token = workspace.requestToken()
            context.core.chatController.clearSelection { workspace.accepts(token) }
        }
        projectDesktopFocus(workspace, chatState) { id ->
            val token = workspace.requestToken()
            context.core.chatController.selectConversation(id.value, allowUnlisted = true) { workspace.accepts(token) }
        }
    }
}

/** Only the owner's current target may turn a catalog observation into a chat load. */
internal fun projectDesktopFocus(
    workspace: ShellWorkspaceState,
    chatState: DesktopChatSurfaceState,
    select: (ConversationId) -> Unit,
) {
    if (workspace.sourceKey == null || workspace.page !is ShellDestination.Chat) return
    val focus = workspace.currentFocus
    val token = workspace.requestToken()
    val references = chatState.conversations.map { row ->
        ShellConversationRef(ConversationId(row.id), row.agentId?.let(::AgentId), row.updatedAtLabel, tags = row.tags)
    }
    val target = when (focus.chatMode) {
        CurrentFocusChatMode.Exact -> focus.conversationId
        CurrentFocusChatMode.Resume -> focus.agentId?.let { workspace.resolveAgentChat(it, references) }
        CurrentFocusChatMode.New -> null
    } ?: return
    val reference = references.singleOrNull { it.id == target }
    if (reference == null && focus.chatMode == CurrentFocusChatMode.Exact) {
        select(target)
        return
    }
    val agent = reference?.agent ?: return
    if (workspace.completeFocus(token, target, agent, reference)) select(target)
}

private fun desktopActiveTitle(destination: DesktopDestination, conversationTitle: String?): String {
    if (destination != DesktopDestination.Conversations) return destination.label
    return conversationTitle ?: "Letta Desktop"
}
