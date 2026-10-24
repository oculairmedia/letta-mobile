package com.letta.mobile.web

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.chat.session.TimelineSubagentViewSessions
import com.letta.mobile.ui.chat.subagents.rememberSubagentDualView
import com.letta.mobile.ui.chat.subagents.rememberSubagentDualViewHost
import com.letta.mobile.ui.shell.workspace.ShellChatLook
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.plugin.catalog.UnavailablePluginCatalog
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.shell.workspace.OpenConversation
import com.letta.mobile.ui.shell.workspace.OpenConversationAgentContextCard
import com.letta.mobile.ui.shell.workspace.OpenConversationContextSession
import com.letta.mobile.ui.context.AgentContextCardBinding
import com.letta.mobile.ui.shell.workspace.rememberOpenConversationChat
import com.letta.mobile.ui.chat.surface.rememberChatDockGeometry
import com.letta.mobile.ui.settings.BackendConnectionNoticeActions
import com.letta.mobile.ui.settings.BackendConnectionStatusRow
import com.letta.mobile.ui.settings.SettingsPageState
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.shell.workspace.openSearchHit
import com.letta.mobile.ui.shell.pages.ShellAgentEditorHost
import com.letta.mobile.ui.shell.pages.home.HomePageOptions
import com.letta.mobile.ui.shell.pages.ShellHomeHost
import com.letta.mobile.ui.shell.pages.ShellHostPages
import com.letta.mobile.ui.shell.pages.ShellPageFocus
import com.letta.mobile.ui.shell.pages.ShellPageHost
import com.letta.mobile.ui.shell.pages.plugins.PluginsPageBinding
import com.letta.mobile.ui.shell.pages.plugins.shellPluginsPage
import com.letta.mobile.ui.shell.pages.rememberShellPageDeps
import com.letta.mobile.ui.shell.shellSettingsPage
import com.letta.mobile.ui.shell.workspace.workspaceNavigation
import com.letta.mobile.ui.shell.workspace.workspaceNavigationActions
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceHost
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceSlots
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceState
import com.letta.mobile.ui.shell.workspace.ShellCanvasOwner
import com.letta.mobile.ui.shell.workspace.ShellChatDestination
import com.letta.mobile.ui.shell.workspace.ShellChatPage
import com.letta.mobile.ui.shell.workspace.ShellChatWaiting
import com.letta.mobile.ui.shell.workspace.ShellChatWaitingPage
import com.letta.mobile.ui.shell.workspace.ShellWindowChrome
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceEvents
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderState
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceLayout
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlayActions
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlayData
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlays
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceSearch
import com.letta.mobile.ui.shell.workspace.rememberShellChatPresentation
import com.letta.mobile.data.chat.runtime.toChatConversationSummaries
import com.letta.mobile.web.chat.WebChatSessionPort
import kotlin.time.Instant

/**
 * letta-mobile-ckmub.17.4: the browser binds the same [com.letta.mobile.ui.shell.workspace.LettaShellWorkspace]
 * the desktop window does: the shared navigation over the workspace and its roster, the shared pages
 * in the main pane, the shared overlays, and the shared agent context card with the connection's status.
 */
internal fun webWorkspaceState(shell: WebShell, page: WebPageInputs, header: ShellWorkspaceHeaderState, now: Instant) =
    LettaShellWorkspaceState(
        header,
        shell.workspace.workspaceNavigation(shell.backend.roster, now, conversations = page.chat.list),
        ShellWorkspaceLayout(sidebarVisible = shell.layout.state.isSidebarVisible),
    )

/** The web's part in the workspace, kept across passes: every slot reads the [page] as it now stands. */
internal fun webWorkspaceHost(shell: WebShell, page: androidx.compose.runtime.State<WebPageInputs>): LettaShellWorkspaceHost {
    val workspace = shell.workspace
    return LettaShellWorkspaceHost(
        actions = shell.navigator.workspaceNavigationActions(page.value.chat.list),
        slots = LettaShellWorkspaceSlots(
            main = { modifier ->
                val deps = rememberShellPageDeps(page.value.session.graph, page.value.session.controllers, webPageHost(shell, page.value))
                ShellPage(destination = workspace.destination, deps = deps, modifier = modifier)
            },
            agentCard = {
                Column {
                    val session = webContextSession(shell, page.value)
                    OpenConversationAgentContextCard(session, workspace.focusedAgentId, page.value.openConversation()) { picker ->
                        AgentContextCardBinding(session.context.cardDeps(attention = null), picker)
                    }
                    BackendConnectionStatusRow(page.value.connection)
                }
            },
            // The browser has no background-task roster yet (parity-exemptions.txt).
            sidePane = {},
            overlays = {
                ShellWorkspaceOverlays(workspace.overlays, webOverlayData(shell, page.value), webOverlayActions(shell))
            },
        ),
        chrome = ShellWindowChrome.Inline,
        drawer = shell.drawer,
        events = ShellWorkspaceEvents(
            onWidthChanged = { shell.layout.dispatch(ShellLayoutEvent.WindowWidthChanged(it)) },
            shortcuts = webShortcuts(shell),
        ),
    )
}

/** The header search and the palette look through the roster and the conversations. */
internal fun webSearch(shell: WebShell, page: WebPageInputs) =
    ShellWorkspaceSearch(home = page.session.controllers.home, sources = webSearchSources(shell, page))

private fun webSearchSources(shell: WebShell, page: WebPageInputs) = UnifiedSearchSources(
    conversations = page.chat.list.state.conversations.toChatConversationSummaries(shell.backend.roster.toMap()),
    agents = shell.backend.agents.value,
    agentId = shell.workspace.focusedAgentId?.value,
)

private fun webOverlayData(shell: WebShell, page: WebPageInputs) = ShellWorkspaceOverlayData(
    searchSources = webSearchSources(shell, page),
    railAgents = shell.backend.roster,
    rosterAgents = shell.backend.agents.value,
)

private fun webOverlayActions(shell: WebShell) = ShellWorkspaceOverlayActions(
    onOpenAgent = { shell.navigator.openAgent(AgentId(it)) },
    onNewAgent = shell.workspace::openNewAgent,
    onOpenSearchHit = { shell.navigator.openSearchHit(it) },
)

private fun webPageHost(shell: WebShell, page: WebPageInputs): ShellPageHost {
    val workspace = shell.workspace
    val focused = workspace.focusedAgentId
    val services = page.session.services
    return ShellPageHost(
        navigator = shell.navigator,
        focus = ShellPageFocus(focused, shell.backend.agents.value.firstOrNull { it.id == focused }?.name),
        pages = ShellHostPages(
            chat = page.chat.port?.let { port -> webChatPage(shell, page, port) },
            settings = shellSettingsPage(
                SettingsPageState(shell.backend.config.value, page.connection),
                page.settings,
                onBack = { workspace.navigate(ShellDestination.Chat()) },
            ),
            // The host's plugin views need the desktop's canvas relay; a browser lists none yet.
            plugins = shellPluginsPage(PluginsPageBinding(UnavailablePluginCatalog())),
            fallback = webWaitingPage(shell, page),
        ),
        home = ShellHomeHost(options = HomePageOptions(showSearch = false)),
        agentEditor = ShellAgentEditorHost(onRosterChanged = page.session.onRosterChanged),
        modelControl = services.gateway.modelControl,
        settings = services.settings,
    )
}

/**
 * letta-mobile-o4ygk.4.5: the conversation is the shared [ShellChatDestination] in the Pointer idiom,
 * on its board in the browser's store. The mascot beside the composer brings the agents back.
 */
private fun webChatPage(shell: WebShell, page: WebPageInputs, port: WebChatSessionPort) =
    ShellPageContent<ShellDestination> { _, modifier ->
    val target = port.target
    val agentNames = shell.backend.roster.toMap()
    // letta-mobile-zxk2n.8: subagents open in the dual view; the web has no registry, so a row opens its child's default conversation.
    val subagentViews = rememberSubagentDualView(ConversationId(target.conversationId), webSubagentViewSessions(page))
    val modelPicker = rememberOpenConversationChat(webContextSession(shell, page), port.openConversation(), port::updateContextUsage)
    val base = remember(agentNames, shell, modelPicker) {
        ChatSurfaceHost(resolveAgentName = agentNames::get, openAgentPane = shell::showNavigation, modelPicker = modelPicker)
    }
    val host = rememberSubagentDualViewHost(base, subagentViews, emptyList())
    ShellChatDestination(
        page = ShellChatPage(
            port = port,
            host = host,
            board = page.canvas.board,
            owner = ShellCanvasOwner(target.conversationId, target.agentId, target.agentName),
            dockGeometry = rememberChatDockGeometry(page.canvas.dockGeometry),
        ),
        presentation = rememberShellChatPresentation(page.canvas.openOnCanvas, session = port),
        modifier = modifier,
        look = ShellChatLook(subagentViews = subagentViews),
    )
}

/** A conversation that has not opened: the connection and the way on. */
private fun webWaitingPage(shell: WebShell, page: WebPageInputs) = ShellPageContent<ShellDestination> { _, modifier ->
    ShellChatWaitingPage(
        waiting = ShellChatWaiting(
            connection = page.connection,
            opening = shell.workspace.page is ShellDestination.Chat && page.connectedAndOpening(),
            error = page.chat.list.state.error,
        ),
        actions = BackendConnectionNoticeActions(
            onOpenSettings = { shell.workspace.navigate(ShellDestination.Settings()) },
            onShowAgents = shell::showNavigation,
            onRetry = shell.backend::retry.takeIf { page.connection is BackendConnectionStatus.Failed },
        ),
        modifier = modifier,
    )
}

/** What the shared context meter, card and model picker read: the live graph, the gateway's routes and the roster. */
private fun webContextSession(shell: WebShell, page: WebPageInputs) = OpenConversationContextSession(
    graph = page.session.graph,
    routes = page.session.services.gateway.contextRoutes,
    modelControl = page.session.services.gateway.modelControl,
    agents = shell.backend.agents.value,
    onAddProvider = { shell.workspace.navigate(ShellDestination.Providers) },
)

private fun WebChatSessionPort.openConversation() = OpenConversation(target.agentId, target.conversationId, this, models)

private fun WebPageInputs.openConversation(): OpenConversation? = chat.port?.openConversation()

/** Where the dual view reads a subagent's conversation: the gateway's live session. */
private fun webSubagentViewSessions(page: WebPageInputs) = TimelineSubagentViewSessions(page.session.services.gateway::subagentTimeline)

private fun WebPageInputs.connectedAndOpening() = connection is BackendConnectionStatus.Connected && chat.list.state.opening
