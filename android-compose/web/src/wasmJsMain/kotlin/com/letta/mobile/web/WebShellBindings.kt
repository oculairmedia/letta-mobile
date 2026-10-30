package com.letta.mobile.web

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.session.graph.SharedSessionRepositoryGraph
import com.letta.mobile.data.session.ifSupported
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.settings.SettingsPageServices
import com.letta.mobile.ui.shell.pages.ShellLibraryControllers
import com.letta.mobile.ui.shell.workspace.ShellLayoutController
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceDrawer
import com.letta.mobile.data.chat.conversations.ConversationManagementPrefs
import com.letta.mobile.data.chat.conversations.TimelineConversationChat
import com.letta.mobile.data.shell.SessionShellChatPort
import com.letta.mobile.ui.shell.conversations.ShellTimelineChatBinding
import com.letta.mobile.ui.shell.conversations.shellComposerCommands
import com.letta.mobile.ui.shell.conversations.ShellTimelineChat
import com.letta.mobile.web.chat.WebChatSessionPort
import com.letta.mobile.web.data.WasmAppServerClientGateway
import com.letta.mobile.web.data.WebSettingsStore
import com.letta.mobile.web.storage.WebChatCanvas
import com.letta.mobile.web.storage.WebStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.ui.shell.pages.ShellLibrarySources

/**
 * letta-mobile-ckmub.17.5: what the web adds to the shared shell pages - its Settings services and
 * the gateway's sessions. Every page itself is the shared one desktop binds. Home and project pins
 * and a mascot pick are kept in the browser's settings store (letta-mobile-ckmub.17.7).
 */
internal class WebPageServices(val gateway: WasmAppServerClientGateway, val settings: SecureSettingsStore) {
    val sources = ShellLibrarySources(settings = settings, memfs = gateway.memfs)
}

/** The page's long-lived bindings: the App Server gateway, the browser's storage and the backend it reaches. */
internal class WebApp(scope: CoroutineScope) {
    val storage = WebStorage()
    val gateway = WasmAppServerClientGateway(scope, storage.settings)
    val settingsStore = WebSettingsStore(storage.settings)
    val backend = WebBackend(settingsStore.load())
    val services = WebPageServices(gateway, storage.settings)
    val canvas: WebChatCanvas = storage.chatCanvas()

    /** The conversation list's pins and fork lineage, in the browser's settings store. */
    val conversationPrefs = ConversationManagementPrefs.from(storage.settings)
}

/**
 * The backend the page talks to and the roster its shared session graph serves (letta-mobile-ckmub.17.1:
 * [WasmAppServerClientGateway.sessionGraph]'s agent repository). What the shell shows is the shared
 * [ShellWorkspaceState], not this.
 */
internal class WebBackend(initialConfig: LettaConfig) {
    val config: MutableState<LettaConfig> = mutableStateOf(initialConfig)
    val agents: MutableState<List<Agent>> = mutableStateOf(emptyList())

    /** Bumped to fetch the roster again (Retry). */
    val rosterRequest = mutableIntStateOf(0)

    fun retry() {
        rosterRequest.intValue += 1
    }

    /** The roster as the shared navigation takes it: (id, name). */
    val roster: List<Pair<String, String>> get() = agents.value.map { it.id.value to it.name }

    /** Fetches the roster for the current backend and hands it to [workspace]; a new backend resets the workspace. */
    suspend fun loadRoster(gateway: WasmAppServerClientGateway, workspace: ShellWorkspaceState) {
        val current = config.value
        workspace.sessionChanged(webSessionKey(current))
        val fetched = runCatching { gateway.listAgents(current) }
        // A cancelled load (the backend changed again) ends here instead of reading as a failure.
        currentCoroutineContext().ensureActive()
        agents.value = fetched.getOrElse { failure ->
            // The gateway's state carries the failure (its cleaned message); the page shows it.
            println("Letta web: agent list failed: ${failure.message}")
            emptyList()
        }
        workspace.rosterChanged(agents.value.map { it.id })
    }
}

/**
 * The web's binding of the shared chat ([ShellTimelineChatBinding]): the live session's
 * conversations, and a [WebChatSessionPort] per selected conversation with the shell's `/` palette.
 */
internal fun webTimelineChat(
    app: WebApp, shell: WebShell, connection: BackendConnectionStatus, home: com.letta.mobile.data.home.HomePageState,
) = ShellTimelineChatBinding(
    workspace = shell.workspace,
    session = webSessionKey(shell.backend.config.value),
    connection = connection,
    agents = shell.backend.agents.value,
    prefs = app.conversationPrefs,
    backend = app.gateway::chatGateway,
    open = { owner, target, scope ->
        val model = shell.backend.agents.value.firstOrNull { it.id.value == target.agentId }?.model
        val chat = TimelineConversationChat(owner, shell.navigator.shellComposerCommands(owner))
        app.gateway.chatBinding(target, model, chat)?.let { WebChatSessionPort(it, scope) }
    },
    close = WebChatSessionPort::close,
    pinnedAgents = home.pinKeys.mapNotNull(com.letta.mobile.data.home.HomePinnedItem::parseAgentKey)
        .mapTo(mutableSetOf()) { com.letta.mobile.data.model.AgentId(it) },
    pinsLoaded = home.pinsLoaded,
)

/**
 * A rename or delete in the agent editor: the roster again from the live session's agent repository,
 * without reconnecting.
 */
internal suspend fun WebBackend.reloadRoster(graph: SharedSessionRepositoryGraph, workspace: ShellWorkspaceState) {
    val repository = graph.ifSupported(SessionRoute.AGENTS) { agentRepository } ?: return
    runCatching { repository.refreshAgents() }
    agents.value = repository.agents.value
    workspace.rosterChanged(agents.value.map { it.id })
}

/** What the page beside the navigation draws from: the chat, the connection, and the shared pages' session. */
internal class WebPageInputs(
    val chat: ShellTimelineChat<WebChatSessionPort>,
    val connection: BackendConnectionStatus,
    val canvas: WebChatCanvas,
    val settings: SettingsPageServices,
    val session: WebPagesSession,
)

/** The shared pages' session: the live graph, its library controllers and the web's services. */
internal class WebPagesSession(
    val graph: SharedSessionRepositoryGraph,
    val controllers: ShellLibraryControllers,
    val services: WebPageServices,
    val onRosterChanged: () -> Unit,
)

/** The shell's navigation, the backend behind it and the workspace's layout, as the web container passes them around. */
internal class WebShell(
    val navigator: ShellWorkspaceNavigator,
    val backend: WebBackend,
    val chat: SessionShellChatPort,
    val layout: ShellLayoutController,
) {
    val workspace: ShellWorkspaceState get() = navigator.state

    /** The compact window's navigation drawer. */
    val drawer = ShellWorkspaceDrawer()

    /** Brings the agents back: the drawer in a compact window, the sidebar in a wide one. */
    fun showNavigation() {
        drawer.open = true
        layout.dispatch(ShellLayoutEvent.SetSidebarCollapsed(false))
    }
}

/** The session the workspace runs over: a new backend resets it. */
internal fun webSessionKey(config: LettaConfig): String = "${config.mode}|${config.serverUrl}"
