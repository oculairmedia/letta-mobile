package com.letta.mobile.web

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.session.graph.SharedSessionRepositoryGraph
import com.letta.mobile.data.session.ifSupported
import com.letta.mobile.data.shell.ShellChatPort
import com.letta.mobile.data.shell.ShellConversationRef
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.chat.session.TimelineChatTarget
import com.letta.mobile.ui.components.tabs.LettaTab
import com.letta.mobile.ui.settings.SettingsPageServices
import com.letta.mobile.ui.shell.pages.ShellLibraryControllers
import com.letta.mobile.ui.shell.workspace.ShellLayoutController
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceDrawer
import com.letta.mobile.web.chat.WebChatLoad
import com.letta.mobile.web.data.WasmAppServerClientGateway
import com.letta.mobile.web.data.WebSettingsStore
import com.letta.mobile.web.storage.WebChatCanvas
import com.letta.mobile.web.storage.WebStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The page's long-lived bindings: the App Server gateway, the browser's storage and the backend it reaches. */
internal class WebApp(scope: CoroutineScope) {
    val storage = WebStorage()
    val gateway = WasmAppServerClientGateway(scope, storage.settings)
    val settingsStore = WebSettingsStore(storage.settings)
    val backend = WebBackend(settingsStore.load())
    val services = WebPageServices(gateway, storage.settings)
    val canvas: WebChatCanvas = storage.chatCanvas()
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
        workspace.sessionChanged("${current.mode}|${current.serverUrl}")
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
 * The web's chat as the shared navigator sees it. Until the shared chat controller is bound
 * (letta-mobile-ckmub.17.15) the page opens the focused agent's conversation itself; this keeps the
 * conversations it has shown, so the tab strip lists them and selecting one focuses its agent.
 */
internal class WebChatPort(private val workspace: ShellWorkspaceState) : ShellChatPort {
    private val shown = mutableStateMapOf<ConversationId, TimelineChatTarget>()

    /** The page now shows [target]'s conversation: it is the agent's last active one, with a tab. */
    fun shown(target: TimelineChatTarget) {
        val id = ConversationId(target.conversationId)
        shown[id] = target
        workspace.conversationShown(id, AgentId(target.agentId))
    }

    /** The tab for a shown conversation, titled by its agent. */
    fun tab(id: String): LettaTab? = shown[ConversationId(id)]?.let { LettaTab(key = id, title = it.agentName) }

    override fun conversations(): List<ShellConversationRef> =
        shown.values.map { ShellConversationRef(ConversationId(it.conversationId), AgentId(it.agentId), updatedAtLabel = "") }

    override val selectedConversation: ConversationId? get() = workspace.selectedConversationId

    override fun selectConversation(conversation: ConversationId) {
        shown[conversation]?.let { workspace.focusedAgentId = AgentId(it.agentId) }
    }

    override fun createConversationForAgent(agent: AgentId, onCreated: (ConversationId) -> Unit) = Unit

    override fun startNewChat(agent: AgentId?) = Unit

    override fun send(conversation: ConversationId, text: String) = Unit

    override fun stageComposerText(text: String) = Unit
}

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
    val chat: WebChatLoad,
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
    val chat: WebChatPort,
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
