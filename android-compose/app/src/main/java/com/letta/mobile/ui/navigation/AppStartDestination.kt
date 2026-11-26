package com.letta.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.letta.mobile.AgentListLaunchTarget
import com.letta.mobile.AppLaunchTarget
import com.letta.mobile.NotificationNavigationTarget
import com.letta.mobile.PairLinkLaunchTarget
import com.letta.mobile.ShareLaunchTarget
import com.letta.mobile.data.repository.LastChatSelection
import com.letta.mobile.data.session.SessionGraph
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.CurrentFocusInputs
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.data.shell.entrySnapshot
import com.letta.mobile.data.shell.initialCurrentFocus

/** Unknown-source legacy settings and favourite/admin preferences cannot seed current focus. */
@Suppress("UNUSED_PARAMETER")
internal fun resolveStartDestination(
    hasConfig: Boolean,
    initialTarget: AppLaunchTarget?,
    restoredChatSelection: LastChatSelection?,
    fallbackAgentId: String?,
): ShellDestination = when {
    !hasConfig -> ShellDestination.Settings()
    else -> ShellDestination.Home
}

/** Feed one source-bound entry and a complete saved envelope through the shared arbitration. */
internal fun initializeAndroidFocus(workspace: ShellWorkspaceState, inputs: CurrentFocusInputs): Boolean {
    val entry = listOfNotNull(inputs.deepLink, inputs.explicitRoute)
        .firstOrNull { it.sourceKey == inputs.sourceKey && it.contractVersion == 1 }
    if (workspace.initialize(inputs)) return entry != null && workspace.currentFocus == entry.focus
    // A new entry can replace restored focus before pages/effects mount. The owner retains tabs/rail.
    if (entry == null || entry.editorRoute != null) return false
    return workspace.selectFocus(initialCurrentFocus(inputs).focus)
}

/** A launch the workspace takes once it is up ([target]), and how it hands back that it did. */
internal class AndroidShellLaunch(val target: AppLaunchTarget?, val onConsumed: () -> Unit, val openPairingScan: () -> Unit) {
    /** This launch as a new focus entry on [source]: a notification opens exactly its conversation. */
    fun focusInputs(source: String): CurrentFocusInputs? {
        val chat = target as? NotificationNavigationTarget ?: return null
        return CurrentFocusInputs(source, deepLink = ShellDestination.Chat(chat.agentId, chat.conversationId).entrySnapshot(source))
    }

    /** Arbitrates this launch's entry on [source] through [bind] while composing, before any page or effect starts. */
    @Composable
    fun BindFocus(workspace: ShellWorkspaceState, graph: SessionGraph, source: String, bind: (CurrentFocusInputs?) -> Unit) {
        val entry = remember(target, source) { focusInputs(source) }
        remember(workspace, source, graph.id, entry) { bind(entry) }
    }
}

/** A notification opens its chat, a share waits for its agent, the agent directory opens, a pairing link scans. */
@Composable
internal fun AndroidShellLaunchEffect(shell: WorkspaceShell, launch: AndroidShellLaunch) {
    LaunchedEffect(launch.target, shell.config != null, shell.sourceReady) {
        when (val target = launch.target ?: return@LaunchedEffect) {
            // A bound source means BindFocus has already arbitrated the entry: hand it back once.
            is NotificationNavigationTarget -> if (shell.config == null || !shell.sourceReady) return@LaunchedEffect
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
