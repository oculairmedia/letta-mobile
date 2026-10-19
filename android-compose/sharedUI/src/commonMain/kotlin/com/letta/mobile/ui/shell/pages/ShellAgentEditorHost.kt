package com.letta.mobile.ui.shell.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.agenteditor.AgentCreator
import com.letta.mobile.data.agenteditor.AgentEditorController
import com.letta.mobile.data.agenteditor.AgentEditorLinks
import com.letta.mobile.data.agenteditor.AgentEditorMode
import com.letta.mobile.data.agenteditor.AgentEditorRepositories
import com.letta.mobile.data.agenteditor.RepositoryAgentEditorBackend
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.session.graph.SharedSessionRepositoryGraph
import com.letta.mobile.data.session.ifSupported
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.agenteditor.AgentEditorPageOptions
import com.letta.mobile.ui.agenteditor.AgentEditorPanels
import com.letta.mobile.ui.shell.AgentEditorBinding
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.shellAgentEditorPage
import kotlinx.coroutines.flow.StateFlow

/**
 * What the host adds to the shared agent editor: its own way to create an agent (the desktop's chat
 * opens the new agent's first conversation; null creates it on the session's repository), the models
 * its chat offers, its platform panels, who else hears a mascot pick, and what reloads its roster.
 */
class ShellAgentEditorHost(
    val creator: AgentCreator? = null,
    val modelCatalog: StateFlow<List<LlmModel>>? = null,
    val panels: (AgentId) -> AgentEditorPanels = { AgentEditorPanels() },
    val onIdentityChange: (AgentId, MascotIdentity) -> Unit = { _, _ -> },
    val onRosterChanged: () -> Unit = {},
    /** The host frames the editor (the desktop's side pane draws its title and close); else the page has Back. */
    val embedded: Boolean = false,
)

/**
 * The shared agent editor over the session's repositories (it was
 * desktop's `DesktopAgentEditor` binding). A save or delete closes it into the chat; a rename or delete
 * reloads the host's roster.
 */
internal fun shellAgentEditorBinding(
    graph: SharedSessionRepositoryGraph,
    host: ShellPageHost,
): ShellPageContent<ShellDestination.AgentEditor> = shellAgentEditorPage(
    AgentEditorBinding(
        controller = { id -> rememberAgentEditor(id?.let(::AgentId), graph, host.agentEditor) },
        options = { id, controller -> editorOptions(id?.let(::AgentId), controller, host) },
    ),
)

@Composable
private fun rememberAgentEditor(agent: AgentId?, graph: SharedSessionRepositoryGraph, host: ShellAgentEditorHost): AgentEditorController {
    val scope = rememberCoroutineScope()
    // Keyed by the agent and the graph only: a host rebuilds its creator lambda on every composition.
    return remember(agent, graph) {
        // letta-mobile-by4vm.13: a route the session does not serve is absent, not a throwing stub.
        val repositories = AgentEditorRepositories(
            agents = graph.ifSupported(SessionRoute.AGENTS) { agentRepository },
            tools = graph.ifSupported(SessionRoute.TOOLS) { toolRepository },
            models = graph.ifSupported(SessionRoute.MODELS) { modelRepository },
            modelCatalog = host.modelCatalog,
        )
        val creator = host.creator
        val backend = creator?.let { RepositoryAgentEditorBackend(repositories, it) } ?: RepositoryAgentEditorBackend(repositories)
        AgentEditorController(backend = backend, scope = scope, mode = AgentEditorMode.of(agent?.value))
    }
}

private fun editorOptions(agent: AgentId?, controller: AgentEditorController, host: ShellPageHost): AgentEditorPageOptions {
    val editor = host.agentEditor
    val workspace = host.navigator.state
    val close = { workspace.editAgentId = null }
    return AgentEditorPageOptions(
        onBack = close.takeUnless { editor.embedded },
        onSaved = {
            val state = controller.state.value
            val renamed = agent != null && state.agent?.name != state.form.name.trim()
            close()
            if (renamed) editor.onRosterChanged()
        },
        onDeleted = {
            close()
            editor.onRosterChanged()
        },
        links = agent?.let { editorLinks(it, workspace::navigate) } ?: AgentEditorLinks(),
        panels = agent?.let(editor.panels) ?: AgentEditorPanels(),
        settings = host.settings,
        onIdentityChange = { id, identity -> editor.onIdentityChange(AgentId(id), identity) },
        embedded = editor.embedded,
    )
}

/** The agent's other pages, opened through [open]. */
private fun editorLinks(agent: AgentId, open: (ShellDestination) -> Unit) = AgentEditorLinks(
    memory = { open(ShellDestination.Memory(agent.value)) },
    schedules = { open(ShellDestination.Schedules(agent.value)) },
    channels = { open(ShellDestination.Channels) },
)
