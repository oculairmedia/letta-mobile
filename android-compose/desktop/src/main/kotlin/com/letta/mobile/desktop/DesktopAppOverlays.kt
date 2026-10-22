package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import com.letta.mobile.data.context.limit.AgentContextRoutes
import com.letta.mobile.data.session.llmModelsOrEmpty
import com.letta.mobile.data.shell.ShellOverlays
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import com.letta.mobile.ui.context.AgentContextCardBinding
import com.letta.mobile.ui.context.AgentContextSession
import com.letta.mobile.ui.context.ContextWindowSources
import com.letta.mobile.ui.context.rememberContextWindowSources
import com.letta.mobile.ui.host.LettaConfirmPane
import com.letta.mobile.ui.host.LettaConfirmRequest
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlayActions
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlayData
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlays

@Immutable
internal data class DesktopOverlayData(
    /** The workspace's own overlays' inputs (the palette, New conversation, the drop hint). */
    val workspace: ShellWorkspaceOverlayData,
)

@Immutable
internal data class DesktopOverlayActions(
    val onIrohIdentityReset: () -> Unit,
    val workspace: ShellWorkspaceOverlayActions,
)

/** The desktop's overlays over the shared workspace's ([ShellWorkspaceOverlays]): the Iroh identity reset confirmation. */
@Composable
internal fun DesktopAppOverlays(
    visibility: ShellOverlays,
    data: DesktopOverlayData,
    actions: DesktopOverlayActions,
) {
    ShellWorkspaceOverlays(visibility, data.workspace, actions.workspace)
    if (visibility.irohResetConfirm) {
        LettaConfirmPane(
            request = LettaConfirmRequest(
                title = "Reset Iroh identity?",
                message = "This mints a new NodeId and breaks existing device pairings until you re-pair.",
                confirmLabel = "Reset identity",
            ),
            onConfirm = {
                visibility.irohResetConfirm = false
                actions.onIrohIdentityReset()
            },
            onDismiss = { visibility.irohResetConfirm = false },
        )
    }
}

/** The shared context card's binding: compaction and the limit over the Iroh relay, else the bundled App Server. */
@Composable
internal fun rememberDesktopContextCard(context: DesktopShellContext): AgentContextCardBinding {
    val core = context.core
    val graph = core.sessionGraph.value
    val rpc = core.bootstrap.dataBindings.modelControlRpc
    return remember(graph, rpc, context.modelPicker, core.attentionPrefs) {
        val routes = AgentContextRoutes(rpc) { DesktopLocalAppServerClientRegistry.shared.currentOrNull() }
        val session = AgentContextSession(graph.contextTokenReadings, graph.contextBreakdown, routes)
        AgentContextCardBinding(session.cardDeps(core.attentionPrefs), context.modelPicker)
    }
}

/** The roster, the session's model list and each conversation's own model: what the windows are sized from. */
@Composable
internal fun rememberDesktopContextWindow(context: DesktopShellContext, frame: DesktopShellFrame): ContextWindowSources {
    val models = remember(context.core.sessionGraph.value) { context.core.sessionGraph.value.llmModelsOrEmpty() }
    return rememberContextWindowSources(frame.focus.rosterAgents, models, context.core.chatController.models.selections)
}
