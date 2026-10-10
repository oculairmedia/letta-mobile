package com.letta.mobile.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.shell.rail.ShellAgentRail
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellAgentRailState
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
}

/**
 * The phone's navigation drawer: the desktop's agent rail and agent panel side by side (rail, panel, separated by tone rather than a line), so the hamburger opens the same navigation the desktop shows. [modifier] sizes
 * it; the host's drawer sheet supplies the scrim, the slide and the insets. [agentCard] is the
 * model-and-context card under the agent's name (letta-mobile-3io8k).
 */
@Composable
fun ShellNavDrawer(
    state: ShellNavDrawerState,
    actions: ShellNavDrawerActions,
    modifier: Modifier = Modifier,
    agentCard: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxHeight().testTag(ShellNavDrawerTags.DRAWER)) {
        ShellAgentRail(
            state = state.rail,
            actions = actions.rail,
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        )
        ShellAgentPanel(
            state = state.panel,
            actions = actions.panel,
            agentCard = agentCard,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        )
    }
}
