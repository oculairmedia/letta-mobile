package com.letta.mobile.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
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
import com.letta.mobile.ui.theme.LettaDimens

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
 * The phone's navigation drawer: the desktop's agent rail and agent panel side by side (rail,
 * hairline, panel), so the hamburger opens the same navigation the desktop shows. [modifier] sizes
 * it; the host's drawer sheet supplies the scrim, the slide and the insets.
 */
@Composable
fun ShellNavDrawer(
    state: ShellNavDrawerState,
    actions: ShellNavDrawerActions,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxHeight().testTag(ShellNavDrawerTags.DRAWER)) {
        ShellAgentRail(
            state = state.rail,
            actions = actions.rail,
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        )
        Box(
            Modifier
                .fillMaxHeight()
                .width(LettaDimens.Stroke.hairline)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        ShellAgentPanel(
            state = state.panel,
            actions = actions.panel,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        )
    }
}
