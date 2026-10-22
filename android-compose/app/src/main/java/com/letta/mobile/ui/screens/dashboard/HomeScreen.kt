package com.letta.mobile.ui.screens.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.runtime.ChatScreenStatus
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.toBackendLabel
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.di.pluginViewsOf
import com.letta.mobile.ui.navigation.WorkspaceShell
import com.letta.mobile.ui.navigation.androidChatPage
import com.letta.mobile.ui.navigation.androidPluginsPage
import com.letta.mobile.ui.navigation.androidWaitingPage
import com.letta.mobile.ui.screens.agenteditor.androidAgentEditorHost
import com.letta.mobile.ui.screens.config.androidSettingsPage
import com.letta.mobile.ui.shell.pages.ShellHomeConnection
import com.letta.mobile.ui.shell.pages.ShellHomeHost
import com.letta.mobile.ui.shell.pages.ShellHostPages
import com.letta.mobile.ui.shell.pages.ShellPageFocus
import com.letta.mobile.ui.shell.pages.ShellPageHost
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageSlots
import com.letta.mobile.ui.shell.pages.home.HomeConnectionState
import com.letta.mobile.ui.shell.pages.home.HomePageOptions
import com.letta.mobile.ui.shell.pages.plugins.rememberPluginsEntry
import com.letta.mobile.util.Telemetry

/**
 * letta-mobile-ckmub.2: what Android adds to the shared shell pages: its chat page, its Settings and
 * Plugins, the Home connection hero, and the agent editor's creator and Dreaming panel. Every page
 * itself is the shared one desktop and web bind.
 */
@Composable
internal fun androidPageHost(shell: WorkspaceShell): ShellPageHost {
    val workspace = shell.workspace
    val sources = shell.drawerModel.sources
    val agents by shell.drawerModel.agents.collectAsStateWithLifecycle()
    val focused = workspace.focusedAgentId
    val connection = rememberAndroidConnection(shell)
    val context = LocalContext.current
    val plugins = remember(context) { pluginViewsOf(context) }
    val settings = shell.exits.settings.copy(
        onProviders = { workspace.navigate(ShellDestination.Providers) },
        onPlugins = rememberPluginsEntry(plugins) { workspace.navigate(ShellDestination.Plugins) },
    )
    return ShellPageHost(
        navigator = shell.navigator,
        focus = ShellPageFocus(focused, agents.firstOrNull { it.id == focused }?.name),
        pages = ShellHostPages(
            chat = androidChatPage(shell),
            settings = androidSettingsPage(onBack = { workspace.navigate(ShellDestination.Home) }, destinations = settings),
            plugins = androidPluginsPage(plugins, onBack = { workspace.navigate(ShellDestination.Settings()) }),
            slots = ChannelsPageSlots.Material,
            fallback = androidWaitingPage(shell, connection),
        ),
        home = ShellHomeHost(
            // The workspace header's search searches Home while it shows.
            options = HomePageOptions(showSearch = false, touch = true),
            connection = connection.homeHero(onRetry = { sources.sessions.rebuild() }),
            onA2uiAction = ::dropUnroutableA2uiAction,
        ),
        agentEditor = androidAgentEditorHost(shell),
        modelControl = sources.modelControl,
        settings = sources.library.settings,
    )
}

/** The backend's state as the shared pages show it: none configured, failed, or there. */
@Composable
private fun rememberAndroidConnection(shell: WorkspaceShell): BackendConnectionStatus {
    val sessions = shell.drawerModel.sources.sessions
    val error by sessions.sessionError.collectAsStateWithLifecycle()
    val agents by shell.drawerModel.agents.collectAsStateWithLifecycle()
    return when {
        shell.config == null -> BackendConnectionStatus.Unconfigured
        error != null -> BackendConnectionStatus.Failed(error?.message ?: "Couldn't reach the backend")
        agents.isEmpty() -> BackendConnectionStatus.Connecting
        else -> BackendConnectionStatus.Connected(shell.config.toBackendLabel().orEmpty())
    }
}

/** Home's connection hero until the backend is there; null once it is. */
private fun BackendConnectionStatus.homeHero(onRetry: () -> Unit): ShellHomeConnection? {
    val status = when (this) {
        BackendConnectionStatus.Unconfigured -> ChatScreenStatus.ConfigNeeded(null)
        BackendConnectionStatus.Connecting -> ChatScreenStatus.Loading
        is BackendConnectionStatus.Failed -> ChatScreenStatus.BackendOffline(message)
        is BackendConnectionStatus.Connected -> return null
    }
    return ShellHomeConnection(HomeConnectionState(status, (this as? BackendConnectionStatus.Failed)?.message), onRetry, wordmark = "LETTA")
}

/** No transport carries a Home card's A2UI action on Android yet (as on desktop): it is logged and dropped. */
private fun dropUnroutableA2uiAction(action: A2uiAction) {
    Telemetry.event(
        "AndroidA2ui", "action.unroutable",
        "surfaceId" to action.surfaceId, "name" to action.name,
        level = Telemetry.Level.WARN,
    )
}
