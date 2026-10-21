package com.letta.mobile.desktop

import com.letta.mobile.data.session.llmModelsOrEmpty
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.letta.mobile.data.context.limit.AgentContextRoutes
import com.letta.mobile.desktop.chat.DesktopModelControlHost
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import com.letta.mobile.ui.context.AgentContextCardActions
import com.letta.mobile.ui.context.AgentContextCardBinding
import com.letta.mobile.ui.context.AgentContextSession
import com.letta.mobile.ui.context.ContextConversationFocus
import com.letta.mobile.ui.context.ContextWindowSources
import com.letta.mobile.ui.context.FocusedAgentContextCard

/**
 * The sidebar's shared model-and-context card for the focused agent. Desktop
 * only binds: the session graph, the host's model control (falling back to the chat's model list),
 * and compaction and the context limit over the Iroh relay or, without one, the bundled App Server.
 */
@Composable
internal fun DesktopAgentContextCard(context: DesktopShellContext, frame: DesktopShellFrame) {
    val core = context.core
    val graph = core.sessionGraph.value
    val chatController = core.chatController
    val dataBindings = core.bootstrap.dataBindings
    val binding = remember(graph, dataBindings, core.attentionPrefs, chatController) {
        val session = AgentContextSession(
            readings = graph.contextTokenReadings,
            breakdown = graph.contextBreakdown,
            routes = AgentContextRoutes(dataBindings.modelControlRpc) { DesktopLocalAppServerClientRegistry.shared.currentOrNull() },
        )
        val deps = session.cardDeps(
            pickerSource = DesktopModelControlHost(
                session = dataBindings.modelControl,
                chatModels = chatController.models.available,
                reloadChatModels = chatController.models::reload,
                onModelSelected = chatController.models::select,
                recentModels = chatController.conversationManagement.recentModels,
            ).pickerSource(),
            attention = core.attentionPrefs,
        )
        AgentContextCardBinding(deps, AgentContextCardActions(onModelSelected = { entry -> chatController.models.select(entry.value) }))
    }
    FocusedAgentContextCard(
        binding = binding,
        focus = frame.contextFocus(),
        window = rememberDesktopContextWindow(context, frame),
        modelLabel = frame.chatState.composerModelLabel,
    )
}

/** The conversation the composer meter and the context card describe. */
internal fun DesktopShellFrame.contextFocus() = ContextConversationFocus(
    agentId = focus.selectedAgentId,
    conversationId = chatState.selectedConversationId,
    turnRunning = activity.isThinkingSelected || activity.isStreamingReplySelected,
)

/** The roster, the session's model list and each conversation's own model: what the windows are sized from. */
@Composable
internal fun rememberDesktopContextWindow(context: DesktopShellContext, frame: DesktopShellFrame): ContextWindowSources {
    val core = context.core
    val graph = core.sessionGraph.value
    val models by remember(graph) { graph.llmModelsOrEmpty() }.collectAsState()
    val selections by core.chatController.models.selections.collectAsState()
    return ContextWindowSources(frame.focus.rosterAgents, models, selections)
}
