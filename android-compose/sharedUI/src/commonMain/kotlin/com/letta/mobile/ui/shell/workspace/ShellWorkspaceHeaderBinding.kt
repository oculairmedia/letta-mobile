package com.letta.mobile.ui.shell.workspace

import androidx.compose.runtime.Composable
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.home.HomePageActions
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.toShellDestination
import com.letta.mobile.ui.components.tabs.LettaTab
import com.letta.mobile.ui.components.tabs.LettaTabStripActions
import com.letta.mobile.ui.shell.header.ShellSearchContext
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.shell.header.rememberShellSearchSlot

/** What the header's search looks through, and Home's own search while Home shows. */
class ShellWorkspaceSearch(
    val home: HomePageActions?,
    val sources: UnifiedSearchSources,
)

/**
 * What a host whose navigation is the shared [ShellWorkspaceNavigator] gives the header: the
 * sidebar's [layout], the header [search], and how an open tab is titled ([tabFor] a conversation
 * id; null drops the tab until the conversation is known).
 */
class ShellWorkspaceHeaderBinding(
    val navigator: ShellWorkspaceNavigator,
    val layout: ShellLayoutController,
    val search: ShellWorkspaceSearch,
    val tabFor: (String) -> LettaTab?,
)

/** The workspace header over the shared navigator: sidebar toggle and overflow, search, and the tab strip. */
@Composable
fun rememberShellWorkspaceHeader(binding: ShellWorkspaceHeaderBinding): ShellWorkspaceHeaderState {
    val navigator = binding.navigator
    val state = navigator.state
    val layout = binding.layout
    return ShellWorkspaceHeaderState(
        sidebar = ShellSidebarControl(
            collapsed = !layout.state.isSidebarVisible,
            onToggle = { layout.dispatch(ShellLayoutEvent.ToggleSidebar) },
            overflow = navigator.sidebarOverflow(),
        ),
        search = rememberShellSearchSlot(
            home = binding.search.home,
            context = ShellSearchContext(onHome = state.page == ShellDestination.Home, inChat = state.page is ShellDestination.Chat),
            sources = binding.search.sources,
            onOpen = { hit -> navigator.openSearchHit(hit) },
        ),
        tabs = ShellConversationTabs(
            tabs = state.tabs.openConversationIds.mapNotNull(binding.tabFor),
            activeKey = state.selectedConversationId?.value,
            actions = navigator.tabStripActions(),
        ),
    )
}

/** The collapsed sidebar's overflow over the shared navigator: a new chat, the agent's sections, Settings. */
fun ShellWorkspaceNavigator.sidebarOverflow(): ShellSidebarOverflow = ShellSidebarOverflow(
    mode = state.workPlayMode,
    onNewChat = { openNewChat(state.focusedAgentId) },
    onDestination = { section -> navigate(section.toShellDestination(state.focusedAgentId?.value)) },
    onSettings = { navigate(ShellDestination.Settings()) },
)

/** The tab strip's verbs; its browser-style "+" is a new chat with the focused agent. */
fun ShellWorkspaceNavigator.tabStripActions(): LettaTabStripActions = LettaTabStripActions(
    onSelect = { openConversation(ConversationId(it)) },
    onClose = { closeConversationTab(ConversationId(it)) },
    onReorder = { id, index -> reorderConversationTab(ConversationId(id), index) },
    onNew = { openNewChat(state.focusedAgentId) },
)
