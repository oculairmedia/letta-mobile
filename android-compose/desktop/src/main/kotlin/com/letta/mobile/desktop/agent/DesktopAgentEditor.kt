package com.letta.mobile.desktop.agent

import com.letta.mobile.data.session.ifSupported
import com.letta.mobile.data.backend.SessionRoute
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.agenteditor.AgentCreator
import com.letta.mobile.data.agenteditor.AgentEditorController
import com.letta.mobile.data.agenteditor.AgentEditorLinks
import com.letta.mobile.data.agenteditor.AgentEditorMode
import com.letta.mobile.data.agenteditor.AgentEditorRepositories
import com.letta.mobile.data.agenteditor.RepositoryAgentEditorBackend
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.secrets.AgentSecretsFeature
import com.letta.mobile.data.secrets.AgentVaultController
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.data.DesktopSessionGraph
import com.letta.mobile.desktop.workspace.DesktopDreamingDeps
import com.letta.mobile.desktop.workspace.DesktopDreamingPane
import com.letta.mobile.desktop.workspace.DesktopWorkspaceSources
import com.letta.mobile.desktop.workspace.secrets
import com.letta.mobile.ui.agenteditor.AgentEditorPageOptions
import com.letta.mobile.ui.agenteditor.AgentEditorPanels
import com.letta.mobile.ui.shell.AgentEditorBinding
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.pages.vault.AgentVaultPage
import com.letta.mobile.ui.shell.pages.vault.AgentVaultPageOptions
import com.letta.mobile.ui.shell.shellAgentEditorPage

/** What the desktop's agent editor works over: this session's repositories, the chat, the device store. */
internal class DesktopAgentEditorDeps(
    val sessionGraph: DesktopSessionGraph,
    val chatController: DesktopChatController,
    val settings: SecureSettingsStore,
    val subagents: ISubagentRepository?,
)

/** Where the editor's actions lead on the desktop. */
internal class DesktopAgentEditorNavigation(
    val onClose: () -> Unit,
    val onOpen: (ShellDestination) -> Unit,
    /** A mascot pick, shown on the rail's orbs at once. */
    val onIdentityChange: (agentId: String, identity: MascotIdentity) -> Unit,
    /** The roster changed under the chat (a rename, a delete): reload it. */
    val onRosterChanged: () -> Unit,
)

/**
 * letta-mobile-ckmub.3.24 (hard cut S15): the desktop's binding of the shared agent editor, shown in
 * the side pane beside the chat (it draws the pane's title and close; the page omits its header). A
 * new agent is created through the chat controller, which opens its first conversation.
 * Platform-UI-Reason: container only (binds the shared AgentEditorPage to the desktop session).
 */
internal fun desktopAgentEditorPage(
    deps: DesktopAgentEditorDeps,
    navigation: DesktopAgentEditorNavigation,
): ShellPageContent<ShellDestination.AgentEditor> = shellAgentEditorPage(
    AgentEditorBinding(
        controller = { agentId -> rememberDesktopAgentEditor(agentId, deps) },
        options = { agentId, controller -> desktopEditorOptions(agentId, controller, deps, navigation) },
    ),
)

@Composable
private fun rememberDesktopAgentEditor(agentId: String?, deps: DesktopAgentEditorDeps): AgentEditorController {
    val scope = rememberCoroutineScope()
    val graph = deps.sessionGraph
    val chat = deps.chatController
    return remember(agentId, graph, chat) {
        // letta-mobile-by4vm.13: a route the session does not serve is absent, not a throwing stub.
        val repositories = AgentEditorRepositories(
            agents = graph.ifSupported(SessionRoute.AGENTS) { agentRepository },
            tools = graph.ifSupported(SessionRoute.TOOLS) { toolRepository },
            models = graph.ifSupported(SessionRoute.MODELS) { modelRepository },
            modelCatalog = chat.availableModels,
        )
        AgentEditorController(
            backend = RepositoryAgentEditorBackend(repositories, AgentCreator { params -> chat.createAgentAndOpen(params) }),
            scope = scope,
            mode = AgentEditorMode.of(agentId),
        )
    }
}

private fun desktopEditorOptions(
    agentId: String?,
    controller: AgentEditorController,
    deps: DesktopAgentEditorDeps,
    navigation: DesktopAgentEditorNavigation,
): AgentEditorPageOptions = AgentEditorPageOptions(
    onSaved = {
        val state = controller.state.value
        val renamed = agentId != null && state.agent?.name != state.form.name.trim()
        navigation.onClose()
        if (renamed) navigation.onRosterChanged()
    },
    onDeleted = {
        navigation.onClose()
        navigation.onRosterChanged()
    },
    links = agentId?.let { id ->
        AgentEditorLinks(
            memory = { navigation.onOpen(ShellDestination.Memory(id)) },
            schedules = { navigation.onOpen(ShellDestination.Schedules(id)) },
            channels = { navigation.onOpen(ShellDestination.Channels) },
        )
    } ?: AgentEditorLinks(),
    panels = agentId?.let { id -> desktopPanels(id, deps) } ?: AgentEditorPanels(),
    settings = deps.settings,
    onIdentityChange = navigation.onIdentityChange,
    embedded = true,
)

/** The agent's Dreaming view and, where the vault is on, its secrets (letta-mobile-iczm7, bzvro.25). */
private fun desktopPanels(agentId: String, deps: DesktopAgentEditorDeps) = AgentEditorPanels(
    dreaming = { DesktopDreamingPane(agentId, rememberCoroutineScope(), DesktopDreamingDeps(deps.settings, deps.subagents)) },
    secrets = if (AgentSecretsFeature.Desktop.enabled) {
        { DesktopAgentVault(agentId) }
    } else {
        null
    },
)

/** The agent's vault; its values live only while the pane is open. */
@Composable
private fun DesktopAgentVault(agentId: String) {
    val scope = rememberCoroutineScope()
    val vault = remember(agentId, scope) { AgentVaultController(DesktopWorkspaceSources().secrets(), scope) }
    LaunchedEffect(vault) { vault.selectAgent(agentId) }
    DisposableEffect(vault) { onDispose { vault.close() } }
    val state by vault.state.collectAsState()
    AgentVaultPage(state = state, actions = vault, options = AgentVaultPageOptions(showTitle = false))
}
