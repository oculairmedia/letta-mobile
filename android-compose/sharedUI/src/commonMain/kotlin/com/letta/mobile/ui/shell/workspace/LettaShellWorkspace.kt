package com.letta.mobile.ui.shell.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.desktopshell.ShellLayoutReducer
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.ui.host.InspectorFrame
import com.letta.mobile.ui.host.InspectorHost
import com.letta.mobile.ui.host.LocalPaneWindowClass
import com.letta.mobile.ui.host.OverlayHostRoot
import com.letta.mobile.ui.host.PaneWindowClass
import com.letta.mobile.ui.host.ProvideLettaIdiom
import com.letta.mobile.ui.host.ProvideInspectorHost
import com.letta.mobile.ui.host.rememberInspectorHost
import com.letta.mobile.ui.host.rememberOverlayHost
import com.letta.mobile.ui.mascot.DriveMascotStage
import com.letta.mobile.ui.mascot.MascotTransportLayer
import com.letta.mobile.ui.shell.LocalShellFloatingChromeInset
import com.letta.mobile.ui.shell.ShellNavDrawerSheet
import com.letta.mobile.ui.shell.rail.ShellLibraryRail
import com.letta.mobile.ui.shell.rail.railLightDismiss
import com.letta.mobile.ui.memory.ReleaseCachesUnderPressure
import com.letta.mobile.ui.a11y.AccessibilityTraversal
import com.letta.mobile.ui.a11y.accessibilityTraversal
import com.letta.mobile.ui.shell.shortcuts.ProvideShortcutHub
import com.letta.mobile.ui.shell.shortcuts.rememberShellShortcuts
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanel
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.flow.drop

/**
 * Shared shell workspace mounted by the desktop window, the browser and the Android activity.
 * The header (tab strip and "+", search, now-active identity), the agent
 * rail, the agent panel (mascot hero, model card, sections, chats, canvases), the page, and the
 * overlays over them, in one overlay root with one inspector frame and one mascot layer.
 *
 * At or above [LettaDimens.Pane.wideBreakpoint] the rail and panel sit beside the page (the panel
 * folds away under the layout's width rule or when collapsed); below it, or with
 * [ShellWorkspaceLayout.forceCompact], they fold into a modal drawer and the header turns minimal
 * ([ShellMinimalHeader]: the agent pill, the search, and the menu that opens the drawer).
 * Hosts only bind: their state into [LettaShellWorkspaceState], their verbs and content into
 * [LettaShellWorkspaceHost].
 */
@Composable
fun LettaShellWorkspace(state: LettaShellWorkspaceState, host: LettaShellWorkspaceHost, modifier: Modifier = Modifier) {
    ReleaseCachesUnderPressure()
    val drawer = host.drawer
    ProvideShortcutHub {
        Surface(
            modifier = modifier.fillMaxSize().testTag(LettaShellWorkspaceTags.WORKSPACE),
            color = MaterialTheme.colorScheme.background,
        ) {
            // The window's one overlay queue (snackbars, undo, banners), drawn over the whole shell.
            OverlayHostRoot(rememberOverlayHost(), Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(rememberShellShortcuts(host.events.shortcuts))
                        .railLightDismiss(state.navigation.rail.expanded, host.actions.onRailDismiss),
                ) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val width = maxWidth
                        LaunchedEffect(width) { host.events.onWidthChanged(width.value) }
                        val fit = workspaceFit(state.layout, width)
                        ProvideWorkspaceIdiom(fit) {
                            WorkspaceColumn(WorkspaceFrame(state, host, drawer, fit))
                            host.slots.overlays()
                        }
                    }
                }
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

// letta-mobile-ckmub.17.36: the workspace is where the root's pointer becomes the idiom.
/**
 * Everything the workspace draws reads the idiom its fit implies
 * ([lettaIdiom]: a compact window is Touch on every platform), and panes resolve against the same
 * fold, so a modal in a narrow desktop window is the phone's sheet.
 */
@Composable
private fun ProvideWorkspaceIdiom(fit: WorkspaceFit, content: @Composable () -> Unit) {
    val windowClass = if (fit.compact) PaneWindowClass.Compact else PaneWindowClass.Wide
    CompositionLocalProvider(LocalPaneWindowClass provides windowClass) {
        ProvideLettaIdiom(compact = fit.compact, content = content)
    }
}

/** One composition's view of the workspace: what it draws and how it fits. */
private class WorkspaceFrame(
    val state: LettaShellWorkspaceState,
    val host: LettaShellWorkspaceHost,
    val drawer: ShellWorkspaceDrawer,
    val fit: WorkspaceFit,
)

/** Where the agent pane's mascot stands: the open drawer when compact, the visible sidebar when wide. */
private fun WorkspaceFrame.agentPaneVisible(): Boolean = if (fit.compact) drawer.open else fit.sidebarVisible

/**
 * A compact window's header is the minimal one (every host, the desktop's small window too): the
 * focused agent's pill, the search and the drawer's menu, with no tabs or sidebar control.
 */
@Composable
private fun WorkspaceFrame.header(): ShellWorkspaceHeaderState {
    if (!fit.compact) return state.header
    val navigation = state.navigation
    val railActions = host.actions.rail
    val currentDrawer = drawer
    // Retain callbacks across frames with the same navigation instead of capturing WorkspaceFrame.
    val minimal = remember(navigation, railActions, currentDrawer) {
        val openDrawer = { currentDrawer.open = true }
        ShellMinimalHeader(
            identity = navigation.focusedIdentity(railActions, onSwitch = openDrawer),
            onOpenNavigation = openDrawer,
        )
    }
    return state.header.copy(sidebar = null, tabs = ShellConversationTabs(), minimal = minimal)
}

/**
 * The workspace owns the window's insets once, for every host: the side cutouts here, the
 * status bar under the inline header (its colour runs up behind it), and the page below takes the
 * top as consumed so no page pads for it again (the compact drawer pads its own content for it).
 * A compact inline window has no header bar: the body runs up under the status bar, the minimal
 * header floats over it, and the pages get the band they lie under as
 * [LocalShellFloatingChromeInset] instead. The bottom bar and the keyboard stay
 * the pages' (the composer rides the keyboard). A window without insets (desktop, browser) sees none.
 */
@Composable
private fun WorkspaceColumn(frame: WorkspaceFrame) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val header = frame.header()
        val floating = frame.floatingChrome(header)
        when (val chrome = frame.host.chrome) {
            ShellWindowChrome.Inline -> if (floating == null) InlineHeader(header)
            is ShellWindowChrome.Hoisted -> SideEffect { chrome.onHeader(header) }
        }
        WorkspaceBody(frame, floating, Modifier.weight(1f))
    }
}

/** A compact window drawn inline floats its minimal header over the page instead of drawing a bar. */
@Composable
private fun WorkspaceFrame.floatingChrome(header: ShellWorkspaceHeaderState): FloatingChrome? {
    if (!fit.compact || host.chrome != ShellWindowChrome.Inline) return null
    val statusBar = with(LocalDensity.current) { WindowInsets.safeDrawing.getTop(this).toDp() }
    return FloatingChrome(header, statusBar)
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
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .height(header.barHeight(plain = LettaDimens.Control.minTouchTarget))
            .testTag(LettaShellWorkspaceTags.INLINE_HEADER),
    )
    if (!tabbed) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun WorkspaceBody(frame: WorkspaceFrame, floating: FloatingChrome?, modifier: Modifier) {
    // The panel's card and the page's panels open their detail in the frame around the page.
    val inspectors = rememberInspectorHost()
    DriveMascotStage(frame.state.navigation.rail.focus.selectedAgentId?.let(::AgentId), frame.agentPaneVisible())
    Box(modifier.fillMaxWidth()) {
        // Every seated mascot draws here, over the shell, and travels between seats (wbin4.4).
        MascotTransportLayer {
            ProvideInspectorHost(inspectors) {
                CompositionLocalProvider(LocalShellFloatingChromeInset provides (floating?.inset ?: 0.dp)) {
                    if (frame.fit.compact) CompactBody(frame, inspectors, floating) else WideBody(frame, inspectors)
                }
            }
        }
    }
}

@Composable
private fun WideBody(frame: WorkspaceFrame, inspectors: InspectorHost) {
    val navigation = frame.state.navigation
    val actions = frame.host.actions
    val slots = frame.host.slots
    // The page, rail and panel sit under the inline header, which padded for the status bar.
    val underHeader = Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
    Row(Modifier.fillMaxSize().then(underHeader).testTag(LettaShellWorkspaceTags.WIDE)) {
        ShellLibraryRail(
            state = navigation.rail,
            actions = actions.rail,
            modifier = Modifier.accessibilityTraversal(AccessibilityTraversal.SIDEBAR),
        )
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ShellCollapsibleSidebar(
            visible = frame.fit.sidebarVisible,
            reducedMotion = frame.state.layout.reducedMotion,
            modifier = Modifier.accessibilityTraversal(AccessibilityTraversal.SIDEBAR),
        ) {
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
private fun CompactBody(frame: WorkspaceFrame, inspectors: InspectorHost, floating: FloatingChrome?) {
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
        // The page under the floating header, inside the drawer's content so the open drawer covers both.
        // The page takes the top inset as consumed (it gets the floating band instead); the drawer is
        // not under that, so its sheet still pads its rail and panel below the status bar.
        Box(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
            Row(Modifier.fillMaxSize()) {
                InspectorFrame(Modifier.weight(1f).fillMaxHeight(), host = inspectors) { slots.main(Modifier.fillMaxSize()) }
                slots.sidePane()
            }
            floating?.let { ShellFloatingHeader(it) }
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
