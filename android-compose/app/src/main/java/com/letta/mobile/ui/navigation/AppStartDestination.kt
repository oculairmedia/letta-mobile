package com.letta.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.letta.mobile.AgentListLaunchTarget
import com.letta.mobile.AppLaunchTarget
import com.letta.mobile.NotificationNavigationTarget
import com.letta.mobile.PairLinkLaunchTarget
import com.letta.mobile.ShareLaunchTarget
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.LastChatSelection
import com.letta.mobile.data.shell.ShellDestination

/**
 * letta-mobile-v7aln, ckmub.2: the page the workspace opens on, decided once from settings read
 * synchronously: the launch's chat, else the last chat, else the favourite agent's, else Home; with no
 * backend configured, Settings.
 */
internal fun resolveStartDestination(
    hasConfig: Boolean,
    initialTarget: AppLaunchTarget?,
    restoredChatSelection: LastChatSelection?,
    fallbackAgentId: String?,
): ShellDestination = when {
    !hasConfig -> ShellDestination.Settings()
    initialTarget is NotificationNavigationTarget -> ShellDestination.Chat(initialTarget.agentId, initialTarget.conversationId)
    restoredChatSelection != null -> ShellDestination.Chat(restoredChatSelection.agentId, restoredChatSelection.conversationId)
    fallbackAgentId != null -> ShellDestination.Chat(fallbackAgentId)
    else -> ShellDestination.Home
}

/** A launch the workspace takes once it is up ([target]), and how it hands back that it did. */
internal class AndroidShellLaunch(val target: AppLaunchTarget?, val onConsumed: () -> Unit, val openPairingScan: () -> Unit)

/** A notification opens its chat, a share waits for its agent, the agent directory opens, a pairing link scans. */
@Composable
internal fun AndroidShellLaunchEffect(shell: WorkspaceShell, launch: AndroidShellLaunch) {
    LaunchedEffect(launch.target) {
        when (val target = launch.target ?: return@LaunchedEffect) {
            is NotificationNavigationTarget -> {
                shell.port.open(AgentId(target.agentId), ConversationId(target.conversationId))
                shell.workspace.showChat()
            }
            is ShareLaunchTarget -> {
                shell.port.pendingShare = target.sharedText
                shell.workspace.overlays.newConversation = true
            }
            AgentListLaunchTarget -> shell.workspace.overlays.newConversation = true
            PairLinkLaunchTarget -> launch.openPairingScan()
        }
        launch.onConsumed()
    }
}
