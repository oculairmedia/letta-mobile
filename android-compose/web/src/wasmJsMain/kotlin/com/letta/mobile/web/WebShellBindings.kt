package com.letta.mobile.web

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.shell.ShellChatPort
import com.letta.mobile.data.shell.ShellConversationRef
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.settings.SettingsPageServices
import com.letta.mobile.web.chat.WebChatCanvas
import com.letta.mobile.web.chat.WebChatLoad
import com.letta.mobile.web.data.AgentItemState
import com.letta.mobile.web.data.WasmAppServerClientGateway
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The backend the page talks to and the roster it returned (until the shared session graph is bound,
 * letta-mobile-ckmub.17.1). What the shell shows is the shared [ShellWorkspaceState], not this.
 */
internal class WebBackend(initialConfig: LettaConfig) {
    val config: MutableState<LettaConfig> = mutableStateOf(initialConfig)
    val agents: MutableState<List<AgentItemState>> = mutableStateOf(emptyList())

    /** Bumped to fetch the roster again (Retry). */
    val rosterRequest = mutableIntStateOf(0)

    fun retry() {
        rosterRequest.intValue += 1
    }

    /** The roster as the shared drawer takes it: (id, name). */
    val roster: List<Pair<String, String>> get() = agents.value.map { it.id to it.name }

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
        workspace.rosterChanged(agents.value.map { AgentId(it.id) })
    }
}

/**
 * The web's chat as the shared navigator sees it: until the shared chat controller is bound
 * (letta-mobile-ckmub.17.2), the page opens the focused agent's conversation itself, so the verbs
 * only move the workspace's focus and page.
 */
internal object WebChatPort : ShellChatPort {
    override fun conversations(): List<ShellConversationRef> = emptyList()

    override val selectedConversation: ConversationId? get() = null

    override fun selectConversation(conversation: ConversationId) = Unit

    override fun createConversationForAgent(agent: AgentId, onCreated: (ConversationId) -> Unit) = Unit

    override fun startNewChat(agent: AgentId?) = Unit

    override fun send(conversation: ConversationId, text: String) = Unit

    override fun stageComposerText(text: String) = Unit
}

/** What the page beside the navigation draws from. */
internal class WebPageInputs(
    val chat: WebChatLoad,
    val connection: BackendConnectionStatus,
    val canvas: WebChatCanvas,
    val settings: SettingsPageServices,
)

/** The shell's navigation and the backend behind it, as the web container passes them around. */
internal class WebShell(val navigator: ShellWorkspaceNavigator, val backend: WebBackend) {
    val workspace: ShellWorkspaceState get() = navigator.state
}
