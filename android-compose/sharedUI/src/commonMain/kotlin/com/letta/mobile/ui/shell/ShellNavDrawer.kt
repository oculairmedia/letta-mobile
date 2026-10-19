package com.letta.mobile.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.shell.rail.ShellAgentRail
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellAgentRailState
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffoldDefaults
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanel
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState

/** Everything the navigation drawer draws: the agent rail and the focused agent's panel. */
@Immutable
data class ShellNavDrawerState(
    val rail: ShellAgentRailState,
    val panel: ShellAgentPanelState,
)

/** What the navigation drawer asks its host to do. */
data class ShellNavDrawerActions(
    val rail: ShellAgentRailActions = ShellAgentRailActions(),
    val panel: ShellAgentPanelActions = ShellAgentPanelActions(),
)

object ShellNavDrawerTags {
    const val DRAWER = "shell-nav-drawer"

    /** The rail's tinted surface: the full drawer height, behind the system bars. */
    const val RAIL_SURFACE = "shell-nav-drawer-rail-surface"

    /** The panel's surface: the full drawer height, behind the system bars. */
    const val PANEL_SURFACE = "shell-nav-drawer-panel-surface"
}

/**
 * The phone's navigation drawer: the desktop's agent rail and agent panel side by side (rail, panel,
 * separated by tone rather than a line), so the hamburger opens the same navigation the desktop
 * shows. [modifier] sizes it. [agentCard] is the model-and-context card under the agent's name
 *.
 *
 * Edge to edge ( drawer bug): the rail's and the panel's surfaces fill the whole
 * height, under the status bar and the navigation bar, and only their content pads for [insets] — the
 * rail for the top, bottom and start (a landscape cutout), the panel for the top and bottom. A host
 * sheet must therefore not pad for the insets itself (see [ShellNavDrawerSheet]), or the rail's tint
 * stops short of the bars. Desktop and web windows report no insets, so nothing moves there.
 */
@Composable
fun ShellNavDrawer(
    state: ShellNavDrawerState,
    actions: ShellNavDrawerActions,
    modifier: Modifier = Modifier,
    insets: WindowInsets = LettaPageScaffoldDefaults.insets,
    agentCard: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxHeight().testTag(ShellNavDrawerTags.DRAWER)) {
        Box(
            Modifier
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .testTag(ShellNavDrawerTags.RAIL_SURFACE),
        ) {
            ShellAgentRail(
                state = state.rail,
                actions = actions.rail,
                modifier = Modifier.windowInsetsPadding(insets.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .testTag(ShellNavDrawerTags.PANEL_SURFACE),
        ) {
            ShellAgentPanel(
                state = state.panel,
                actions = actions.panel,
                agentCard = agentCard,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Vertical)),
            )
        }
    }
}

/**
 * [ShellNavDrawer] in the modal drawer sheet a phone host slides in. The sheet's own inset padding is
 * off ([NoSheetInsets]): the drawer pads its content for [insets] itself so its surfaces reach the
 * window's edges.
 */
@Composable
fun ShellNavDrawerSheet(
    state: ShellNavDrawerState,
    actions: ShellNavDrawerActions,
    modifier: Modifier = Modifier,
    insets: WindowInsets = LettaPageScaffoldDefaults.insets,
    agentCard: (@Composable () -> Unit)? = null,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest, windowInsets = NoSheetInsets) {
        ShellNavDrawer(state = state, actions = actions, modifier = modifier, insets = insets, agentCard = agentCard)
    }
}

private val NoSheetInsets = WindowInsets(0, 0, 0, 0)
