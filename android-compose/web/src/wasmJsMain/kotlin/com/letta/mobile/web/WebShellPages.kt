package com.letta.mobile.web

import com.letta.mobile.data.plugin.catalog.UnavailablePluginCatalog
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.storage.InMemorySettingsStore
import com.letta.mobile.ui.settings.SettingsPageState
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.pages.ShellAgentEditorHost
import com.letta.mobile.ui.shell.pages.ShellHomeHost
import com.letta.mobile.ui.shell.pages.ShellHostPages
import com.letta.mobile.ui.shell.pages.ShellLibrarySources
import com.letta.mobile.ui.shell.pages.ShellPageFocus
import com.letta.mobile.ui.shell.pages.ShellPageHost
import com.letta.mobile.ui.shell.pages.plugins.PluginsPageBinding
import com.letta.mobile.ui.shell.pages.plugins.shellPluginsPage
import com.letta.mobile.ui.shell.shellSettingsPage
import com.letta.mobile.web.chat.WebChatLoad
import com.letta.mobile.web.chat.WebChatPageNavigation
import com.letta.mobile.web.chat.WebSharedChatPage
import com.letta.mobile.web.data.WasmAppServerClientGateway

/**
 * letta-mobile-ckmub.17.5: what the web adds to the shared shell pages - its chat container, its
 * Settings services and the gateway's sessions. Every page itself is the shared one desktop binds.
 * The tab's preferences (Home and project pins, a mascot pick) last the tab, as its token does.
 */
internal class WebPageServices(val gateway: WasmAppServerClientGateway) {
    val settings = InMemorySettingsStore()
    val sources = ShellLibrarySources(settings = settings, memfs = gateway.memfs)
}

/** The page's own contributions: [fallback] is the connection notice for a chat that has not opened. */
internal class WebPageContributions(
    val navigation: WebChatPageNavigation,
    val fallback: ShellPageContent<ShellDestination>,
)

internal fun webPageHost(shell: WebShell, page: WebPageInputs, own: WebPageContributions): ShellPageHost {
    val workspace = shell.workspace
    val focused = workspace.focusedAgentId
    val services = page.session.services
    return ShellPageHost(
        navigator = shell.navigator,
        focus = ShellPageFocus(focused, shell.backend.agents.value.firstOrNull { it.id == focused }?.name),
        pages = ShellHostPages(
            chat = (page.chat as? WebChatLoad.Ready)?.let { ready ->
                ShellPageContent { _, modifier -> WebSharedChatPage(ready.port, page.canvas, own.navigation, modifier) }
            },
            settings = shellSettingsPage(
                SettingsPageState(shell.backend.config.value, page.connection),
                page.settings,
                onBack = { workspace.navigate(ShellDestination.Chat()) },
            ),
            // The host's plugin views need the desktop's canvas relay; a browser lists none yet.
            plugins = shellPluginsPage(PluginsPageBinding(UnavailablePluginCatalog())),
            fallback = own.fallback,
        ),
        home = ShellHomeHost(),
        agentEditor = ShellAgentEditorHost(onRosterChanged = page.session.onRosterChanged),
        modelControl = services.gateway.modelControl,
        settings = services.settings,
    )
}
