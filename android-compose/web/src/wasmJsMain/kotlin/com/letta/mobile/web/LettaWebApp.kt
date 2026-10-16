package com.letta.mobile.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.settings.BackendConnectionNotice
import com.letta.mobile.ui.settings.BackendConnectionNoticeActions
import com.letta.mobile.ui.settings.BackendConnectionStatusRow
import com.letta.mobile.ui.settings.SettingsPageServices
import com.letta.mobile.ui.settings.SettingsPageState
import com.letta.mobile.ui.shell.ShellNavDrawer
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.ShellPageDeps
import com.letta.mobile.ui.shell.shellSettingsPage
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.SharedMaterialTheme
import com.letta.mobile.web.chat.WebChatCanvas
import com.letta.mobile.web.chat.WebChatLoad
import com.letta.mobile.web.chat.WebChatOpener
import com.letta.mobile.web.chat.WebChatPageNavigation
import com.letta.mobile.web.chat.WebSharedChatPage
import com.letta.mobile.web.chat.rememberWebChatLoad
import com.letta.mobile.web.data.WasmAppServerClientGateway
import com.letta.mobile.web.data.WebSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * letta-mobile-ckmub.8: the web app is a container around the shared shell. Wide windows show the
 * shared navigation ([ShellNavDrawer]: the agent rail and the agent panel) beside the page, as the
 * desktop does; narrow ones put it in a modal drawer, as Android does. The pages are the shared chat
 * page and the shared Settings page. What stays web-side is the binding: the App Server gateway,
 * the browser's settings storage and the in-memory canvas store.
 */
@Composable
fun LettaWebApp() {
    val scope = rememberCoroutineScope()
    val gateway = remember { WasmAppServerClientGateway(scope) }
    val settingsStore = remember { WebSettingsStore() }
    val savedSettings = remember(settingsStore) { settingsStore.load() }
    val shell = remember { WebShellState(savedSettings.config) }
    // letta-mobile-o4ygk.4.5: boards live in memory for the tab's lifetime (no web persistence yet).
    val canvas = remember { WebChatCanvas(InMemoryCanvasDocumentStore(), savedSettings.openChatsOnCanvas) }
    val connection by gateway.state.collectAsState()
    LoadRoster(gateway, shell)
    LaunchedEffect(Unit) { shell.openRoute(kotlinx.browser.window.location.hash.removePrefix("#")) }
    LaunchedEffect(shell.route) { kotlinx.browser.window.location.hash = shell.route }
    val chat = rememberWebChatLoad(remember(gateway) { WebChatOpener(gateway::openChat) }, shell.selectedAgent, connection)
    val page = WebPageInputs(
        chat = chat,
        connection = connection,
        canvas = canvas,
        settings = webSettingsServices(shell, settingsStore),
    )
    SharedMaterialTheme {
        ProvideLettaHost(LettaHost.Pointer) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                BoxWithConstraints {
                    if (maxWidth < LettaDimens.Pane.wideBreakpoint) CompactWebShell(shell, page) else WideWebShell(shell, page)
                }
            }
        }
    }
}

/** Fetches the roster whenever the backend changes or Retry asks again. */
@Composable
private fun LoadRoster(gateway: WasmAppServerClientGateway, shell: WebShellState) {
    val config = shell.config
    LaunchedEffect(config.serverUrl, config.accessToken, config.mode, shell.rosterRequest) {
        try {
            shell.rosterLoaded(gateway.listAgents(config))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // The gateway's state carries the failure (its cleaned message); the page shows it.
            shell.rosterLost()
            println("Letta web: agent list failed: ${failure.message}")
        }
    }
}

/** What the page beside the navigation draws from. */
private class WebPageInputs(
    val chat: WebChatLoad,
    val connection: BackendConnectionStatus,
    val canvas: WebChatCanvas,
    val settings: SettingsPageServices,
)

@Composable
private fun WideWebShell(shell: WebShellState, page: WebPageInputs) {
    Row(modifier = Modifier.fillMaxSize()) {
        WebNavigation(shell, page.connection, afterNavigate = {}, modifier = Modifier.width(WideNavigationWidth))
        WebPage(shell, page, onShowAgents = null, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CompactWebShell(shell: WebShellState, page: WebPageInputs) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                WebNavigation(shell, page.connection, afterNavigate = { scope.launch { drawer.close() } })
            }
        },
    ) {
        WebPage(shell, page, onShowAgents = { scope.launch { drawer.open() } }, modifier = Modifier.fillMaxSize())
    }
}

/** The shared navigation; the connection's status sits under the agent's name. */
@Composable
private fun WebNavigation(
    shell: WebShellState,
    connection: BackendConnectionStatus,
    afterNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = remember(shell.agents, shell.destination) { Clock.System.now() }
    ShellNavDrawer(
        state = shell.navDrawerState(now),
        actions = shell.navDrawerActions(afterNavigate),
        modifier = modifier.fillMaxHeight(),
        agentCard = { BackendConnectionStatusRow(connection) },
    )
}

/**
 * The shared ShellPage beside the navigation; [onShowAgents] opens the navigation where it is hidden.
 * Home and a conversation that has not opened fall back to the connection notice (until the web binds Home).
 */
@Composable
private fun WebPage(shell: WebShellState, page: WebPageInputs, onShowAgents: (() -> Unit)?, modifier: Modifier) {
    val ready = page.chat as? WebChatLoad.Ready
    val navigation = WebChatPageNavigation(onShowAgents ?: {}, agentNamesById = shell.agents.associate { it.id.value to it.name })
    val deps = ShellPageDeps(
        chat = ready?.let { ShellPageContent { _, m -> WebSharedChatPage(it.port, page.canvas, navigation, m) } },
        settings = shellSettingsPage(SettingsPageState(shell.config, page.connection), page.settings, onBack = shell::closeSettings),
        fallback = ShellPageContent { _, m -> WebWaitingPage(shell, page, onShowAgents, m) },
    )
    ShellPage(destination = shell.destination, deps = deps, modifier = modifier)
}

/** Home, or a conversation that has not opened: the connection, what to do next, and the way on. */
@Composable
private fun WebWaitingPage(shell: WebShellState, page: WebPageInputs, onShowAgents: (() -> Unit)?, modifier: Modifier) {
    val chat = page.chat
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (shell.destination is ShellDestination.Chat && chat == WebChatLoad.Opening) {
            CircularProgressIndicator()
        } else {
            BackendConnectionNotice(
                status = page.connection,
                error = (chat as? WebChatLoad.Failed)?.message ?: (page.connection as? BackendConnectionStatus.Failed)?.message,
                actions = BackendConnectionNoticeActions(
                    onOpenSettings = { shell.destination = ShellDestination.Settings() },
                    onShowAgents = onShowAgents,
                    onRetry = shell::retry.takeIf { page.connection is BackendConnectionStatus.Failed },
                ),
            )
        }
    }
}

/** The rail plus the desktop sidebar's width. */
private val WideNavigationWidth = LettaDimens.Pane.railWidth + 231.dp
