package com.letta.mobile.desktop.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.letta.mobile.data.agenteditor.AgentCreator
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.secrets.AgentSecretsFeature
import com.letta.mobile.data.secrets.AgentVaultController
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.desktop.DesktopShellContext
import com.letta.mobile.desktop.workspace.DesktopDreamingDeps
import com.letta.mobile.desktop.workspace.DesktopDreamingPane
import com.letta.mobile.desktop.workspace.DesktopWorkspaceSources
import com.letta.mobile.desktop.workspace.secrets
import com.letta.mobile.ui.agenteditor.AgentEditorPanels
import com.letta.mobile.ui.shell.pages.ShellAgentEditorHost
import com.letta.mobile.ui.shell.pages.vault.AgentVaultPage
import com.letta.mobile.ui.shell.pages.vault.AgentVaultPageOptions

/**
 * letta-mobile-ckmub.3.24 / ckmub.17.5: what the desktop adds to the shared agent editor (sharedUI's
 * binding over the session graph). A new agent is created through the chat controller, which opens
 * its first conversation; the editor sits in the side pane beside the chat, which draws its title and
 * close; a mascot pick shows on the rail's orbs at once; a rename or delete reloads the roster.
 * Platform-UI-Reason: container only (binds the desktop's Dreaming and vault panels).
 */
internal fun DesktopShellContext.agentEditorHost(): ShellAgentEditorHost {
    val chat = core.chatController
    val panels = DesktopEditorPanelDeps(core.bootstrap.secureSettingsStore, this.panels.subagents.repository)
    return ShellAgentEditorHost(
        creator = AgentCreator { params -> chat.createAgentAndOpen(params) },
        modelCatalog = chat.models.available,
        panels = { agent -> desktopPanels(agent.value, panels) },
        onIdentityChange = { agent, identity -> avatarOverrides.value += agent.value to identity },
        onRosterChanged = chat::retryConnection,
        embedded = true,
    )
}

/** What the desktop's editor panels work over: the device store and the background-task registry. */
private class DesktopEditorPanelDeps(
    val settings: SecureSettingsStore,
    val subagents: ISubagentRepository?,
)

/** The agent's Dreaming view and, where the vault is on, its secrets (letta-mobile-iczm7, bzvro.25). */
private fun desktopPanels(agentId: String, deps: DesktopEditorPanelDeps) = AgentEditorPanels(
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
