package com.letta.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.letta.mobile.AgentListLaunchTarget
import com.letta.mobile.AppLaunchTarget
import com.letta.mobile.NotificationNavigationTarget
import com.letta.mobile.PairLinkLaunchTarget
import com.letta.mobile.ShareLaunchTarget
import com.letta.mobile.data.repository.LastChatSelection
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.CurrentFocusInputs
import com.letta.mobile.data.shell.ShellWorkspaceState
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
internal class AndroidShellLaunch(
    val target: AppLaunchTarget?, val onConsumed: () -> Unit, val openPairingScan: () -> Unit,
    val focusInputs: CurrentFocusInputs? = null,
)

/** A notification opens its chat, a share waits for its agent, the agent directory opens, a pairing link scans. */
@Composable
internal fun AndroidShellLaunchEffect(shell: WorkspaceShell, launch: AndroidShellLaunch) {
    LaunchedEffect(launch.target, launch.focusInputs, shell.config != null, shell.sourceReady) {
        when (val target = launch.target ?: return@LaunchedEffect) {
            is NotificationNavigationTarget -> {
                if (shell.config == null || !shell.sourceReady) return@LaunchedEffect
                val entry = launch.focusInputs?.deepLink ?: return@LaunchedEffect
                if (entry.sourceKey != shell.workspace.sourceKey) {
                    launch.onConsumed()
                    return@LaunchedEffect
                }
                if (shell.workspace.currentFocus != entry.focus) return@LaunchedEffect
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
