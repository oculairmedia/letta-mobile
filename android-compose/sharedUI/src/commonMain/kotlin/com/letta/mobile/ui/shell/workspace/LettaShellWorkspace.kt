package com.letta.mobile.ui.shell.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.letta.mobile.data.desktopshell.ShellLayoutReducer
import com.letta.mobile.ui.host.InspectorFrame
import com.letta.mobile.ui.host.InspectorHost
import com.letta.mobile.ui.host.OverlayHostRoot
import com.letta.mobile.ui.host.ProvideInspectorHost
import com.letta.mobile.ui.host.rememberInspectorHost
import com.letta.mobile.ui.host.rememberOverlayHost
import com.letta.mobile.ui.mascot.LocalMascotTransport
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.MascotTransportLayer
import com.letta.mobile.ui.shell.ShellNavDrawerSheet
import com.letta.mobile.ui.shell.rail.ShellLibraryRail
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.rail.railLightDismiss
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanel
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.flow.drop

/**
 * letta-mobile-ckmub.17.4: the one shell workspace the desktop window and the browser both mount
 * (moved out of :desktop). The header (tab strip and "+", search, now-active identity), the agent
 * rail, the agent panel (mascot hero, model card, sections, chats, canvases), the page, and the
 * overlays over them, in one overlay root with one inspector frame and one mascot layer.
 *
 * At or above [LettaDimens.Pane.wideBreakpoint] the rail and panel sit beside the page (the panel
 * folds away under the layout's width rule or when collapsed); below it, or with
 * [ShellWorkspaceLayout.forceCompact], they fold into a modal drawer the header's menu opens.
 * Hosts only bind: their state into [LettaShellWorkspaceState], their verbs and content into
 * [LettaShellWorkspaceHost].
 */
@Composable
fun LettaShellWorkspace(state: LettaShellWorkspaceState, host: LettaShellWorkspaceHost, modifier: Modifier = Modifier) {
    val drawer = host.drawer ?: remember { ShellWorkspaceDrawer() }
    Surface(modifier = modifier.fillMaxSize().testTag(LettaShellWorkspaceTags.WORKSPACE), color = MaterialTheme.colorScheme.background) {
        // The window's one overlay queue (snackbars, undo, banners), drawn over the whole shell.
        OverlayHostRoot(rememberOverlayHost(), Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(rememberPaletteShortcut(host.events.onOpenCommandPalette))
                    .railLightDismiss(state.navigation.rail.expanded, host.actions.onRailDismiss),
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val width = maxWidth
                    LaunchedEffect(width) { host.events.onWidthChanged(width.value) }
                    WorkspaceColumn(WorkspaceFrame(state, host, drawer, workspaceFit(state.layout, width)))
                }
                host.slots.overlays()
            }
        }
    }
}

/** How the workspace fits the window: folded into the drawer, and whether the agent panel shows. */
private class WorkspaceFit(val compact: Boolean, val sidebarVisible: Boolean)

private fun workspaceFit(layout: ShellWorkspaceLayout, width: Dp): WorkspaceFit = WorkspaceFit(
    compact = layout.forceCompact || width < LettaDimens.Pane.wideBreakpoint,
    // The person's choice, removed (not shrunk) below the layout's width rule.
    sidebarVisible = layout.sidebarVisible && !ShellLayoutReducer.defaultCollapsedForWidth(width.value),
)

/** One composition's view of the workspace: what it draws and how it fits. */
private class WorkspaceFrame(
    val state: LettaShellWorkspaceState,
    val host: LettaShellWorkspaceHost,
    val drawer: ShellWorkspaceDrawer,
    val fit: WorkspaceFit,
)

/** Where the agent pane's mascot stands: the open drawer when compact, the visible sidebar when wide. */
private fun WorkspaceFrame.agentPaneVisible(): Boolean = if (fit.compact) drawer.open else fit.sidebarVisible

/** In a compact window the header's sidebar control opens the drawer. */
private fun WorkspaceFrame.header(): ShellWorkspaceHeaderState {
    if (!fit.compact) return state.header
    return state.header.copy(sidebar = ShellSidebarControl(collapsed = true, onToggle = { drawer.open = true }, opensDrawer = true))
}
@Composable
private fun WorkspaceColumn(frame: WorkspaceFrame) {
    Column(Modifier.fillMaxSize()) {
        val header = frame.header()
        when (val chrome = frame.host.chrome) {
            ShellWindowChrome.Inline -> InlineHeader(header)
            is ShellWindowChrome.Hoisted -> SideEffect { chrome.onHeader(header) }
        }
        WorkspaceBody(frame, Modifier.weight(1f))
    }
}

/**
 * The header at the top of the page; with tabs the active tab runs into the page, so no line. Its
 * height is the bar's, as a title bar bounds it on the desktop: the header's free width fills its
 * height, so an unbounded header would take the whole window and leave the body none.
 */
@Composable
private fun InlineHeader(header: ShellWorkspaceHeaderState) {
    val tabbed = header.tabs.tabs.isNotEmpty()
    ShellWorkspaceHeader(
        header,
        Modifier
            .fillMaxWidth()
            .height(inlineHeaderHeight(tabbed))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .testTag(LettaShellWorkspaceTags.INLINE_HEADER),
    )
    if (!tabbed) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The bar's height: browser-thin with tabs (one line per tab), else room for the identity block. */
internal fun inlineHeaderHeight(tabbed: Boolean): Dp =
    if (tabbed) LettaDimens.Control.actionButton else LettaDimens.Control.minTouchTarget

@Composable
private fun WorkspaceBody(frame: WorkspaceFrame, modifier: Modifier) {
    // The panel's card and the page's panels open their detail in the frame around the page.
    val inspectors = rememberInspectorHost()
    ShellMascotStage(frame.state.navigation.rail.focus, frame.agentPaneVisible())
    Box(modifier.fillMaxWidth()) {
        // Every seated mascot draws here, over the shell, and travels between seats (wbin4.4).
        MascotTransportLayer {
            ProvideInspectorHost(inspectors) {
                if (frame.fit.compact) CompactBody(frame, inspectors) else WideBody(frame, inspectors)
            }
        }
    }
}

@Composable
private fun WideBody(frame: WorkspaceFrame, inspectors: InspectorHost) {
    val navigation = frame.state.navigation
    val actions = frame.host.actions
    val slots = frame.host.slots
    Row(Modifier.fillMaxSize().testTag(LettaShellWorkspaceTags.WIDE)) {
        ShellLibraryRail(state = navigation.rail, actions = actions.rail)
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ShellCollapsibleSidebar(visible = frame.fit.sidebarVisible, reducedMotion = frame.state.layout.reducedMotion) {
            ShellAgentPanel(
                state = navigation.panel,
                actions = actions.panel,
                agentCard = slots.agentCard,
                modifier = Modifier
                    .width(LettaDimens.Pane.agentPanelWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            )
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        InspectorFrame(Modifier.weight(1f).fillMaxHeight(), host = inspectors) { slots.main(Modifier.fillMaxSize()) }
        slots.sidePane()
    }
}

@Composable
private fun CompactBody(frame: WorkspaceFrame, inspectors: InspectorHost) {
    val drawer = frame.drawer
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(drawer.open) { if (drawer.open) drawerState.open() else drawerState.close() }
    // A swipe or a scrim press closes it from the drawer's side.
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.targetValue == DrawerValue.Open }.drop(1).collect { drawer.open = it }
    }
    val slots = frame.host.slots
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ShellNavDrawerSheet(
                state = frame.state.navigation.drawerState(),
                actions = frame.host.actions.closing(drawer),
                agentCard = slots.agentCard,
            )
        },
        modifier = Modifier.testTag(LettaShellWorkspaceTags.COMPACT),
    ) {
        Row(Modifier.fillMaxSize()) {
            InspectorFrame(Modifier.weight(1f).fillMaxHeight(), host = inspectors) { slots.main(Modifier.fillMaxSize()) }
            slots.sidePane()
        }
    }
}

/**
 * The collapsible sidebar container: removed (not shrunk) when hidden, and with reduced motion the
 * transition is skipped rather than shortened.
 */
@Composable
fun ShellCollapsibleSidebar(
    visible: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = if (reducedMotion) EnterTransition.None else expandHorizontally(),
        exit = if (reducedMotion) ExitTransition.None else shrinkHorizontally(),
        modifier = modifier,
    ) {
        Row(Modifier.fillMaxHeight()) { content() }
    }
}

/**
 * One rule for where the focused agent's mascot stands (wbin4.4): the agent pane's hero seat while
 * the pane shows, else rest. The previous agent is let go when focus moves.
 */
@Composable
private fun ShellMascotStage(focus: ShellRailFocus, agentPaneVisible: Boolean) {
    val selectedAgentId = focus.selectedAgentId
    val transport = LocalMascotTransport.current
    var placedAgent by remember { mutableStateOf<String?>(null) }
    val stage = if (agentPaneVisible) MascotStage.AGENT_PANE_HERO else null
    LaunchedEffect(selectedAgentId, stage) {
        placedAgent?.takeIf { it != selectedAgentId }?.let(transport::rest)
        placedAgent = selectedAgentId
        val agent = selectedAgentId ?: return@LaunchedEffect
        if (stage != null) transport.transportTo(agent, stage) else transport.rest(agent)
    }
}

/**
 * Ctrl/Cmd+K anywhere in the workspace opens the command palette. The workspace takes focus once so
 * a page with nothing focused yet (a fresh browser tab) still hears the shortcut.
 */
@Composable
private fun rememberPaletteShortcut(onOpen: (() -> Unit)?): Modifier {
    if (onOpen == null) return Modifier
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) { runCatching { requester.requestFocus() } }
    return Modifier
        .onPreviewKeyEvent { event ->
            val palette = event.type == KeyEventType.KeyDown && event.key == Key.K && (event.isCtrlPressed || event.isMetaPressed)
            if (palette) onOpen()
            palette
        }
        .focusRequester(requester)
        .focusable()
}
