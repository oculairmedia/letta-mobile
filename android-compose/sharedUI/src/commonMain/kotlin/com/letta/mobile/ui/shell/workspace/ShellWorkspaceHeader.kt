package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.shell_new_conversation_tab
import com.letta.mobile.sharedui.resources.shell_open_navigation
import com.letta.mobile.sharedui.resources.shell_sidebar_hide
import com.letta.mobile.sharedui.resources.shell_sidebar_show
import com.letta.mobile.sharedui.resources.shell_workspace_title
import com.letta.mobile.ui.components.tabs.LettaTab
import com.letta.mobile.ui.components.tabs.LettaTabStrip
import com.letta.mobile.ui.components.tabs.LettaTabStripActions
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.shell.header.HeaderAction
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.SearchSlot
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.nowactive.NowActiveBarActions
import com.letta.mobile.ui.shell.nowactive.NowActiveBarState
import com.letta.mobile.ui.shell.nowactive.NowActiveIdentity
import com.letta.mobile.ui.shell.nowactive.NowActiveRunControls
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/** The collapsed sidebar's overflow: its sections stay one click away. */
@Immutable
data class ShellSidebarOverflow(
    val mode: WorkPlayMode,
    val onNewChat: () -> Unit,
    val onDestination: (LensDestination) -> Unit,
    val onSettings: () -> Unit,
)

/**
 * The header's sidebar control: the one control that survives collapse, so it also
 * anchors focus when the sidebar closes ([focusRequester]). In a compact window it opens the drawer.
 */
@Immutable
data class ShellSidebarControl(
    val collapsed: Boolean,
    val onToggle: () -> Unit,
    val focusRequester: FocusRequester? = null,
    val overflow: ShellSidebarOverflow? = null,
    /** The compact window's control: it opens the navigation drawer instead of toggling a sidebar. */
    val opensDrawer: Boolean = false,
)

/** The conversation tabs and the strip's "+" (a new chat with the focused agent). */
@Immutable
data class ShellConversationTabs(
    val tabs: List<LettaTab> = emptyList(),
    val activeKey: String? = null,
    val actions: LettaTabStripActions = LettaTabStripActions(),
)

/**
 * What the workspace header draws (lifted from the desktop title bar): the
 * sidebar control, the tab strip, else the now-active identity, else [title]; the search; and the
 * run controls. The desktop draws it in its title bar, the browser at the top of the page.
 */
@Immutable
data class ShellWorkspaceHeaderState(
    /** The window's title when there are no tabs and no identity; null is the app's name. */
    val title: String? = null,
    val identity: NowActiveBarState? = null,
    val identityActions: NowActiveBarActions = NowActiveBarActions.None,
    val sidebar: ShellSidebarControl? = null,
    val search: SearchSlot? = null,
    val tabs: ShellConversationTabs = ShellConversationTabs(),
)

object ShellWorkspaceHeaderTags {
    const val SIDEBAR_TOGGLE = "shell-sidebar-toggle"
}

/**
 * The shared [ShellHeader] with the workspace's pieces. [dragLaneWidth] is the blank lane the tab
 * strip leaves for a window that drags by its title bar; the browser leaves none.
 */
@Composable
fun ShellWorkspaceHeader(state: ShellWorkspaceHeaderState, modifier: Modifier = Modifier, dragLaneWidth: Dp = 0.dp) {
    ShellHeader(
        title = headerTitle(state.labelled(), dragLaneWidth),
        modifier = modifier,
        search = state.search,
        controls = HeaderControls(
            nav = state.sidebar?.let { control -> HeaderNav.Custom { ShellSidebarControls(control) } } ?: HeaderNav.None,
            actions = state.identity?.let { identity ->
                listOf(HeaderAction.Custom { NowActiveRunControls(state = identity, actions = state.identityActions) })
            }.orEmpty(),
        ),
    )
}

/** The header's own words: the app's name as the fallback title, and the strip's "+" label. */
@Composable
private fun ShellWorkspaceHeaderState.labelled(): ShellWorkspaceHeaderState = copy(
    title = title ?: stringResource(Res.string.shell_workspace_title),
    tabs = tabs.copy(actions = tabs.actions.copy(newLabel = stringResource(Res.string.shell_new_conversation_tab))),
)

@Composable
private fun ShellSidebarControls(control: ShellSidebarControl) {
    ShellSidebarToggleButton(control)
    control.overflow?.takeIf { control.collapsed && !control.opensDrawer }?.let { ShellSidebarOverflowMenu(it) }
    Box(modifier = Modifier.width(LettaDimens.Space.xs))
}

/** The tab strip when tabs are open, else the agent identity block, else the window title. */
private fun headerTitle(state: ShellWorkspaceHeaderState, dragLaneWidth: Dp): HeaderTitle {
    val identity = state.identity
    val tabs = state.tabs
    return when {
        tabs.tabs.isNotEmpty() -> HeaderTitle.Custom(fill = true) {
            LettaTabStrip(
                tabs = tabs.tabs,
                activeKey = tabs.activeKey,
                actions = tabs.actions,
                dragLaneWidth = dragLaneWidth,
                modifier = Modifier.fillMaxHeight(),
            )
        }
        identity != null -> HeaderTitle.Custom {
            // A fixed max width, not a weight: the free width stays the window's drag lane.
            NowActiveIdentity(
                state = identity,
                actions = state.identityActions,
                modifier = Modifier.widthIn(max = LettaDimens.Pane.headerIdentityMaxWidth),
            )
        }
        else -> HeaderTitle.Text(state.title.orEmpty())
    }
}

/** The sidebar toggle (a panel glyph, not a hamburger); in a compact window, the drawer's menu. */
@Composable
fun ShellSidebarToggleButton(control: ShellSidebarControl, modifier: Modifier = Modifier) {
    val label = stringResource(
        when {
            control.opensDrawer -> Res.string.shell_open_navigation
            control.collapsed -> Res.string.shell_sidebar_show
            else -> Res.string.shell_sidebar_hide
        },
    )
    val icon = when {
        control.opensDrawer -> LettaIcons.Menu
        control.collapsed -> LettaIcons.PanelLeftOpen
        else -> LettaIcons.PanelLeftClose
    }
    LocalLettaHost.current.tooltip(label) {
        IconButton(
            onClick = control.onToggle,
            modifier = modifier
                .let { m -> control.focusRequester?.let { m.focusRequester(it) } ?: m }
                .testTag(ShellWorkspaceHeaderTags.SIDEBAR_TOGGLE)
                .semantics { contentDescription = label },
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(LettaDimens.Control.icon),
            )
        }
    }
}
