package com.letta.mobile.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.shell.pages.ShellHomeFleet
import com.letta.mobile.ui.shell.pages.ShellHomeFleetEffect
import com.letta.mobile.ui.shell.pages.rememberShellLibraryControllers
import com.letta.mobile.ui.shell.rememberShellWorkspaceState
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspace
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderBinding
import com.letta.mobile.ui.shell.workspace.rememberShellLayoutController
import com.letta.mobile.ui.shell.workspace.rememberShellWorkspaceHeader
import com.letta.mobile.ui.theme.SharedMaterialTheme
import com.letta.mobile.web.chat.WebChatLoad
import com.letta.mobile.web.chat.WebChatOpener
import com.letta.mobile.web.chat.rememberWebChatLoad
import kotlinx.coroutines.launch

/**
 * letta-mobile-ckmub.17.4: the web app is bootstrap and browser bindings around the shared
 * [LettaShellWorkspace] the desktop window mounts ([webWorkspace]). Web-side are the App Server gateway,
 * the browser's storage ([WebApp]), and the URL fragment bound to the shared route ([BrowserRouteBinding]).
 */
@Composable
fun LettaWebApp() {
    val scope = rememberCoroutineScope()
    val app = remember { WebApp(scope) }
    val backend = app.backend
    val workspace = rememberShellWorkspaceState(ShellDestination.Chat())
    val config = backend.config.value
    val layout = rememberShellLayoutController(config, app.storage.settings)
    val shell = remember(workspace, layout) {
        WebChatPort(workspace).let { WebShell(ShellWorkspaceNavigator(workspace, it), backend, it, layout) }
    }
    val connection by app.gateway.state.collectAsState()
    LaunchedEffect(config.serverUrl, config.accessToken, config.mode, backend.rosterRequest.intValue) {
        backend.loadRoster(app.gateway, workspace)
    }
    // The URL fragment follows the workspace's route, and back/forward follow the URL.
    val browserRoute = remember(workspace) { BrowserRouteBinding(workspace) }
    DisposableEffect(browserRoute) { onDispose(browserRoute.start()) }
    LaunchedEffect(browserRoute, workspace.route) { browserRoute.publish(workspace.route) }
    val focusedAgent = backend.agents.value.firstOrNull { it.id == workspace.focusedAgentId }
    val chat = rememberWebChatLoad(remember(app) { WebChatOpener(app.gateway::openChat) }, focusedAgent, connection)
    LaunchedEffect(chat) { (chat as? WebChatLoad.Ready)?.let { shell.chat.shown(it.port.target) } }
    // letta-mobile-ckmub.17.5: the shared pages over the live session graph, as desktop binds them.
    val graph by app.gateway.sessionGraphProvider.currentGraph.collectAsState()
    val controllers = rememberShellLibraryControllers(app.gateway.sessionGraphProvider, app.services.sources, scope)
    ShellHomeFleetEffect(controllers.home, ShellHomeFleet(conversations = emptyList(), roster = backend.agents.value))
    val onRosterChanged: () -> Unit = { scope.launch { backend.reloadRoster(graph, workspace) } }
    val settings = webSettingsServices(backend.config, app.settingsStore)
    val page = WebPageInputs(chat, connection, app.canvas, settings, WebPagesSession(graph, controllers, app.services, onRosterChanged))
    val header = rememberShellWorkspaceHeader(ShellWorkspaceHeaderBinding(shell.navigator, layout, webSearch(shell, page), shell.chat::tab))
    SharedMaterialTheme {
        ProvideLettaHost(LettaHost.Pointer) {
            val bound = webWorkspace(shell, page, header)
            LettaShellWorkspace(bound.state, bound.host)
        }
    }
}
