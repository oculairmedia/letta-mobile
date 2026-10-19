package com.letta.mobile.ui.shell.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.shell.ShellNavDrawerActions
import com.letta.mobile.ui.shell.ShellNavDrawerState
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.shortcuts.ShellShortcutBinding
import com.letta.mobile.ui.shell.rail.ShellAgentRailState
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailMapping
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState

object LettaShellWorkspaceTags {
    const val WORKSPACE = "shell-workspace"
    const val WIDE = "shell-workspace-wide"
    const val COMPACT = "shell-workspace-compact"
    const val INLINE_HEADER = "shell-workspace-inline-header"
}

/** The workspace's navigation: the agent rail (with its library) and the focused agent's panel. */
@Immutable
data class ShellWorkspaceNavigation(
    val rail: ShellLibraryRailState,
    val panel: ShellAgentPanelState,
)

/** What the rail and the panel ask the host to do. */
data class ShellWorkspaceNavigationActions(
    val rail: ShellAgentRailActions = ShellAgentRailActions(),
    val panel: ShellAgentPanelActions = ShellAgentPanelActions(),
    /** A press outside the expanded rail (the agent library) closes it. */
    val onRailDismiss: () -> Unit = {},
)

/** How the workspace lays itself out. */
@Immutable
data class ShellWorkspaceLayout(
    /** The person's sidebar choice; below the layout's width rule the sidebar is removed anyway. */
    val sidebarVisible: Boolean = true,
    val reducedMotion: Boolean = false,
    /** Fold the rail and sidebar into the drawer whatever the width (the desktop's phone preview). */
    val forceCompact: Boolean = false,
)

/** Whether the compact window's navigation drawer is open; a host that opens it from its own chrome passes one in. */
@Stable
class ShellWorkspaceDrawer {
    var open: Boolean by mutableStateOf(false)
}

/**
 * Where the workspace header goes. [Inline] draws it at the top of the workspace (the browser);
 * [Hoisted] hands it to the host, which draws [ShellWorkspaceHeader] in its own title bar (the
 * desktop window, whose title bar is a sibling of the content).
 */
sealed interface ShellWindowChrome {
    data object Inline : ShellWindowChrome

    class Hoisted(val onHeader: (ShellWorkspaceHeaderState) -> Unit) : ShellWindowChrome
}

/** The host's content inside the workspace. */
class LettaShellWorkspaceSlots(
    /** The page beside the navigation (the shared shell pages). */
    val main: @Composable (Modifier) -> Unit,
    /** The model-and-context card under the agent's name in the panel. */
    val agentCard: (@Composable () -> Unit)? = null,
    /** A pane after the page (desktop's background tasks). */
    val sidePane: @Composable () -> Unit = {},
    /** Drawn over the whole workspace (pickers, the palette, banners). */
    val overlays: @Composable () -> Unit = {},
)

/** Everything the workspace draws. */
@Immutable
data class LettaShellWorkspaceState(
    val header: ShellWorkspaceHeaderState,
    val navigation: ShellWorkspaceNavigation,
    val layout: ShellWorkspaceLayout = ShellWorkspaceLayout(),
)

/** How the host takes part: its actions, its slots, where its header goes and its window events. */
class LettaShellWorkspaceHost(
    val actions: ShellWorkspaceNavigationActions,
    val slots: LettaShellWorkspaceSlots,
    val chrome: ShellWindowChrome = ShellWindowChrome.Inline,
    /** The compact drawer's state; null keeps the workspace's own. */
    val drawer: ShellWorkspaceDrawer? = null,
    val events: ShellWorkspaceEvents = ShellWorkspaceEvents(),
)

/** The window events the workspace reports. */
class ShellWorkspaceEvents(
    /** The workspace's measured width in dp, for the host's layout reducer. */
    val onWidthChanged: (Float) -> Unit = {},
    /** The host's binding of the shared keymap; null leaves every chord to the focused field. */
    val shortcuts: ShellShortcutBinding? = null,
)

/** The drawer's view of the navigation: the rail's orbs (its library stays the wide rail's) and the panel. */
internal fun ShellWorkspaceNavigation.drawerState(): ShellNavDrawerState {
    val groups = ShellRailMapping.groups(rail.agents, rail.focus.selectedAgentId)
    return ShellNavDrawerState(
        rail = ShellAgentRailState(entries = ShellRailMapping.entries(groups, rail.focus), homeSelected = rail.homeSelected),
        panel = panel,
    )
}

/** The navigation's actions with the drawer closing after every pick that leaves it. */
internal fun ShellWorkspaceNavigationActions.closing(drawer: ShellWorkspaceDrawer): ShellNavDrawerActions {
    val close = { drawer.open = false }
    return ShellNavDrawerActions(
        rail = rail.copy(
            onAgentSelected = rail.onAgentSelected.then(close),
            onHome = rail.onHome.then(close),
            onNewSession = rail.onNewSession.then(close),
            onAgentSettings = rail.onAgentSettings?.then(close),
        ),
        panel = panel.copy(
            onOpenSection = panel.onOpenSection.then(close),
            onOpenSettings = panel.onOpenSettings.then(close),
            onNewChat = panel.onNewChat.then(close),
            onEditAgent = panel.onEditAgent.then(close),
            onConversationSelected = panel.onConversationSelected.then(close),
            onOpenCanvas = panel.onOpenCanvas.then(close),
        ),
    )
}

private fun (() -> Unit).then(after: () -> Unit): () -> Unit = {
    this()
    after()
}

private fun <T> ((T) -> Unit).then(after: () -> Unit): (T) -> Unit = { value ->
    this(value)
    after()
}
