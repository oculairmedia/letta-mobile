package com.letta.mobile.ui.shell.workspace

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.shell_open_navigation
import com.letta.mobile.sharedui.resources.shell_workspace_title
import com.letta.mobile.ui.chat.AgentIdentity
import com.letta.mobile.ui.chat.AgentIdentityPill
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.shell.header.HeaderAction
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.SearchSlot
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import org.jetbrains.compose.resources.stringResource

/**
 * The compact window's header (the phone's, and a small desktop or browser window's): the focused
 * agent's [AgentIdentityPill] as the title, the search, and on the right the black menu button that
 * opens the navigation drawer. No tab strip and no sidebar control.
 */
@Immutable
data class ShellMinimalHeader(
    /** The focused agent; null (no agent focused) titles the header with the window's title. */
    val identity: AgentIdentity?,
    val onOpenNavigation: () -> Unit,
)

@Composable
internal fun ShellMinimalWorkspaceHeader(
    minimal: ShellMinimalHeader,
    title: String?,
    search: SearchSlot?,
    modifier: Modifier = Modifier,
) {
    ShellHeader(
        title = minimal.identity
            ?.let { identity -> HeaderTitle.Custom { AgentIdentityPill(identity) } }
            ?: HeaderTitle.Text(title ?: stringResource(Res.string.shell_workspace_title)),
        modifier = modifier,
        search = search,
        controls = HeaderControls(actions = listOf(HeaderAction.Custom { ShellDrawerMenuButton(minimal.onOpenNavigation) })),
    )
}

/** The phone header's drawer opener: a black round button with a white menu glyph. */
@Composable
private fun ShellDrawerMenuButton(onClick: () -> Unit) {
    val label = stringResource(Res.string.shell_open_navigation)
    LocalLettaHost.current.tooltip(label) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .testTag(ShellWorkspaceHeaderTags.MENU_BUTTON)
                .semantics { contentDescription = label },
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black, contentColor = Color.White),
        ) {
            Icon(LettaIcons.Menu, contentDescription = null)
        }
    }
}

/**
 * The focused agent's pill: its name from the rail's roster (else the panel's), its pin from the
 * rail's focus. A tap opens [onSwitch] (the drawer, whose rail is the agent switcher); a long press
 * pins or unpins it through the rail's pin action, where the host has one.
 */
internal fun ShellWorkspaceNavigation.focusedIdentity(actions: ShellAgentRailActions, onSwitch: () -> Unit): AgentIdentity? {
    val agentId = rail.focus.selectedAgentId ?: return null
    val name = rail.agents.firstOrNull { (id, _) -> id == agentId }?.second
        ?: panel.agent.name.takeIf { panel.agent.agentId == agentId && it.isNotBlank() }
        ?: agentId
    val pinned = agentId in rail.focus.pinnedAgentIds
    return AgentIdentity(
        agentId = agentId,
        name = name,
        isFavorite = false,
        isPinned = pinned,
        onClick = onSwitch,
        onLongClick = { actions.onAgentPinnedChange?.invoke(agentId, !pinned) },
    )
}
