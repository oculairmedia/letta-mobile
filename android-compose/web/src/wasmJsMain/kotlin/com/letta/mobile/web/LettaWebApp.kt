package com.letta.mobile.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.letta.mobile.data.model.isIrohBackendUrl
import com.letta.mobile.data.shell.SessionShellChatPort
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.host.rememberPrimaryPointer
import com.letta.mobile.ui.mascot.frame.ProvideFrameMascots
import com.letta.mobile.ui.mascot.rememberMascotRegistry
import com.letta.mobile.ui.shell.pages.ShellHomeBackendFleetEffect
import com.letta.mobile.ui.shell.pages.rememberShellLibraryControllers
import com.letta.mobile.ui.shell.rememberShellWorkspaceState
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspace
import com.letta.mobile.ui.shell.workspace.ReportCompositionPasses
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderBinding
import com.letta.mobile.ui.shell.workspace.rememberCoarseNow
import com.letta.mobile.ui.shell.workspace.rememberShellWorkspaceHost
import com.letta.mobile.ui.shell.workspace.rememberShellLayoutController
import com.letta.mobile.ui.shell.workspace.rememberShellWorkspaceHeader
import com.letta.mobile.ui.theme.HighContrastPreference
import com.letta.mobile.ui.theme.LocalHighContrastPreference
import com.letta.mobile.ui.theme.SharedMaterialTheme
import com.letta.mobile.ui.shell.conversations.rememberShellTimelineChat
import com.letta.mobile.web.data.WebPairedHostStore
import com.letta.mobile.web.mascot.RiveCanvasRenderer
import kotlinx.coroutines.launch

/** letta-mobile-ckmub.17.4: bootstrap and browser bindings (gateway, storage, URL) around the shared [LettaShellWorkspace]. */
@Composable
fun LettaWebApp() {
    val scope = rememberCoroutineScope()
    val app = remember { WebApp(scope) }
    val highContrastPreference = remember(app) { HighContrastPreference(app.storage.settings) }
    val backend = app.backend
    val workspace = rememberShellWorkspaceState(ShellDestination.Chat())
    val config = backend.config.value
    val layout = rememberShellLayoutController(config, app.storage.settings)
    val shell = remember(workspace, layout) {
        SessionShellChatPort().let { WebShell(ShellWorkspaceNavigator(workspace, it), backend, it, layout) }
    }
    val connection by app.gateway.state.collectAsState()
    LaunchedEffect(config.serverUrl, config.accessToken, config.mode, backend.rosterRequest.intValue) {
        backend.loadRoster(app.gateway, workspace)
    }
    // letta-mobile-ckmub.17.9: boards sync through the live session's host, as on desktop.
    LaunchedEffect(config.serverUrl) { app.storage.followCanvasHost(app.gateway.canvasRelay, isIrohBackendUrl(config.serverUrl)) }
    // The URL fragment follows the workspace's route, and back/forward follow the URL.
    val browserRoute = remember(workspace) { BrowserRouteBinding(workspace) }
    DisposableEffect(browserRoute) { onDispose(browserRoute.start()) }
    LaunchedEffect(browserRoute, workspace.route) { browserRoute.publish(workspace.route) }
    val controllers = rememberShellLibraryControllers(app.gateway.sessionGraphProvider, app.services.sources, scope)
    val home by controllers.home.state.collectAsState()
    val chat = rememberShellTimelineChat(webTimelineChat(app, shell, connection, home))
    SideEffect { shell.chat.owner = chat.owner }
    LaunchedEffect(Unit) { startWebMemoryPressure() }
    val graph by app.gateway.sessionGraphProvider.currentGraph.collectAsState()
    ShellHomeBackendFleetEffect(controllers.home, chat.list.state.conversations, backend.agents.value)
    val onRosterChanged: () -> Unit = { scope.launch { backend.reloadRoster(graph, workspace) } }
    val pairing = remember(backend, workspace) {
        WebPairingPorts(backend, workspace, WebPairedHostStore(backend.config, app.settingsStore), app.gateway.adminRpc)
    }
    val settings = webSettingsServices(backend.config, app.settingsStore, app.gateway.permissionModeSettings, pairing)
    val page = WebPageInputs(chat, connection, app.canvas, settings, WebPagesSession(graph, controllers, app.services, onRosterChanged))
    val header = rememberShellWorkspaceHeader(ShellWorkspaceHeaderBinding(shell.navigator, layout, webSearch(shell, page), chat.list::tab))
    val host = rememberShellWorkspaceHost(page, Triple(shell, chat.owner, chat.list.state)) { webWorkspaceHost(shell, it) }
    CompositionLocalProvider(LocalHighContrastPreference provides highContrastPreference) {
        SharedMaterialTheme {
            // letta-mobile-ckmub.17.36: the root reports the browser's pointer; the workspace turns it into the idiom.
            val pointer = rememberPrimaryPointer()
            ProvideLettaHost(remember(pointer) { LettaHost.Pointer.copy(input = pointer) }) {
                // letta-mobile-ckmub.17.6: Rive mascots through the shared frame pipeline.
                ProvideFrameMascots(remember { RiveCanvasRenderer() }, rememberMascotRegistry(backend.agents.value)) {
                    ReportCompositionPasses("workspace root", enabled = pagePerfRequested)
                    LettaShellWorkspace(webWorkspaceState(shell, page, header, rememberCoarseNow().value), host)
                }
            }
        }
    }
}
