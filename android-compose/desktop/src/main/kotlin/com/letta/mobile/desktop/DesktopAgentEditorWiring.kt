package com.letta.mobile.desktop

import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.desktop.agent.DesktopAgentEditorDeps
import com.letta.mobile.desktop.agent.DesktopAgentEditorNavigation
import com.letta.mobile.desktop.agent.desktopAgentEditorPage
import com.letta.mobile.ui.shell.ShellPageContent

/** letta-mobile-ckmub.3.24: the shared agent editor page, bound to this shell's session and navigator. */
internal fun DesktopShellContext.agentEditorPage(): ShellPageContent<ShellDestination.AgentEditor> =
    desktopAgentEditorPage(desktopAgentEditorDeps(this), desktopAgentEditorNavigation(this))

/** letta-mobile-ckmub.3.24: what the shared agent editor works over in this session. */
private fun desktopAgentEditorDeps(context: DesktopShellContext): DesktopAgentEditorDeps = DesktopAgentEditorDeps(
    sessionGraph = context.core.sessionGraph.value,
    chatController = context.core.chatController,
    settings = context.core.bootstrap.secureSettingsStore,
    subagents = context.panels.subagents.repository,
)

/** The editor closes into the chat, opens the agent's other pages, and shows a picked mascot at once. */
private fun desktopAgentEditorNavigation(context: DesktopShellContext): DesktopAgentEditorNavigation {
    val navigator = context.navigator
    return DesktopAgentEditorNavigation(
        onClose = { navigator.editAgentId = null },
        onOpen = navigator::navigate,
        onIdentityChange = { agentId, identity -> navigator.avatarOverrides = navigator.avatarOverrides + (agentId to identity) },
        onRosterChanged = context.core.chatController::retryConnection,
    )
}
