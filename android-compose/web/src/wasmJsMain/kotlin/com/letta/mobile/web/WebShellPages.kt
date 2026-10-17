package com.letta.mobile.web

import androidx.compose.runtime.remember
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.plugin.catalog.UnavailablePluginCatalog
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.rememberChatDockGeometry
import com.letta.mobile.ui.settings.BackendConnectionNoticeActions
import com.letta.mobile.ui.settings.BackendConnectionStatusRow
import com.letta.mobile.ui.settings.SettingsPageState
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.shell.workspace.openSearchHit
import com.letta.mobile.ui.shell.pages.ShellAgentEditorHost
import com.letta.mobile.ui.shell.pages.ShellHomeHost
import com.letta.mobile.ui.shell.pages.ShellHostPages
import com.letta.mobile.ui.shell.pages.ShellLibrarySources
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
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceEvents
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderState
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceLayout
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlayActions
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlayData
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlays
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceSearch
import com.letta.mobile.ui.shell.workspace.rememberShellChatPresentation
import com.letta.mobile.web.chat.WebChatLoad
import com.letta.mobile.web.data.WasmAppServerClientGateway
import kotlin.time.Clock

/**
 * letta-mobile-ckmub.17.5: what the web adds to the shared shell pages - its Settings services and
 * the gateway's sessions. Every page itself is the shared one desktop binds. Home and project pins
 * and a mascot pick are kept in the browser's settings store (letta-mobile-ckmub.17.7).
 */
internal class WebPageServices(val gateway: WasmAppServerClientGateway, val settings: SecureSettingsStore) {
    val sources = ShellLibrarySources(settings = settings, memfs = gateway.memfs)
}

/** What the web's workspace draws, and its part in it. */
internal class WebWorkspace(val state: LettaShellWorkspaceState, val host: LettaShellWorkspaceHost)

/**
 * letta-mobile-ckmub.17.4: the browser binds the same [com.letta.mobile.ui.shell.workspace.LettaShellWorkspace]
 * the desktop window does: the shared navigation over the workspace and its roster, the shared pages
 * in the main pane, the shared overlays, and the connection's status as the panel's card.
 */
internal fun webWorkspace(shell: WebShell, page: WebPageInputs, header: ShellWorkspaceHeaderState): WebWorkspace {
    val workspace = shell.workspace
    val roster = shell.backend.roster
    val navigation = workspace.workspaceNavigation(roster, Clock.System.now())
    return WebWorkspace(
        state = LettaShellWorkspaceState(header, navigation, ShellWorkspaceLayout(sidebarVisible = shell.layout.state.isSidebarVisible)),
        host = LettaShellWorkspaceHost(
            actions = shell.navigator.workspaceNavigationActions(),
            slots = LettaShellWorkspaceSlots(
                main = { modifier ->
                    val deps = rememberShellPageDeps(page.session.graph, page.session.controllers, webPageHost(shell, page))
                    ShellPage(destination = workspace.destination, deps = deps, modifier = modifier)
                },
                agentCard = { BackendConnectionStatusRow(page.connection) },
                overlays = { ShellWorkspaceOverlays(workspace.overlays, webOverlayData(shell), webOverlayActions(shell)) },
            ),
            drawer = shell.drawer,
            events = ShellWorkspaceEvents(
                onWidthChanged = { shell.layout.dispatch(ShellLayoutEvent.WindowWidthChanged(it)) },
                onOpenCommandPalette = { workspace.overlays.commandPalette = true },
            ),
        ),
    )
}

/** The header search and the palette look through the roster (conversations come with letta-mobile-ckmub.17.15). */
internal fun webSearch(shell: WebShell, page: WebPageInputs) =
    ShellWorkspaceSearch(home = page.session.controllers.home, sources = webSearchSources(shell))

private fun webSearchSources(shell: WebShell) =
    UnifiedSearchSources(agents = shell.backend.agents.value, agentId = shell.workspace.focusedAgentId?.value)

private fun webOverlayData(shell: WebShell) = ShellWorkspaceOverlayData(
    searchSources = webSearchSources(shell),
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
            chat = (page.chat as? WebChatLoad.Ready)?.let { ready -> webChatPage(shell, page, ready) },
            settings = shellSettingsPage(
                SettingsPageState(shell.backend.config.value, page.connection),
                page.settings,
                onBack = { workspace.navigate(ShellDestination.Chat()) },
            ),
            // The host's plugin views need the desktop's canvas relay; a browser lists none yet.
            plugins = shellPluginsPage(PluginsPageBinding(UnavailablePluginCatalog())),
            fallback = webWaitingPage(shell, page),
        ),
        home = ShellHomeHost(),
        agentEditor = ShellAgentEditorHost(onRosterChanged = page.session.onRosterChanged),
        modelControl = services.gateway.modelControl,
        settings = services.settings,
    )
}

/**
 * letta-mobile-o4ygk.4.5: the conversation is the shared [ShellChatDestination] in the Pointer idiom,
 * on its board in the browser's store. The mascot beside the composer brings the agents back.
 */
private fun webChatPage(shell: WebShell, page: WebPageInputs, ready: WebChatLoad.Ready) =
    ShellPageContent<ShellDestination> { _, modifier ->
    val port = ready.port
    val target = port.target
    val agentNames = shell.backend.roster.toMap()
    val host = remember(agentNames, shell) { ChatSurfaceHost(resolveAgentName = agentNames::get, openAgentPane = shell::showNavigation) }
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
    )
}

/** A conversation that has not opened: the connection and the way on. */
private fun webWaitingPage(shell: WebShell, page: WebPageInputs) = ShellPageContent<ShellDestination> { _, modifier ->
    val chat = page.chat
    ShellChatWaitingPage(
        waiting = ShellChatWaiting(
            connection = page.connection,
            opening = shell.workspace.page is ShellDestination.Chat && chat == WebChatLoad.Opening,
            error = (chat as? WebChatLoad.Failed)?.message,
        ),
        actions = BackendConnectionNoticeActions(
            onOpenSettings = { shell.workspace.navigate(ShellDestination.Settings()) },
            onShowAgents = shell::showNavigation,
            onRetry = shell.backend::retry.takeIf { page.connection is BackendConnectionStatus.Failed },
        ),
        modifier = modifier,
    )
}
