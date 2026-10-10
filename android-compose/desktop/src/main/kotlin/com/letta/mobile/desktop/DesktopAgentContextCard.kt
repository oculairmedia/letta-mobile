package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.letta.mobile.data.compaction.AdminRpcCompactionRepository
import com.letta.mobile.data.compaction.AppServerCompactionRepository
import com.letta.mobile.data.compaction.CompactionController
import com.letta.mobile.data.compaction.CompactionRepository
import com.letta.mobile.data.compaction.orElse
import com.letta.mobile.data.context.ContextBreakdownLoader
import com.letta.mobile.data.context.contextWindowTokensOf
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.desktop.chat.DesktopModelControlHost
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import com.letta.mobile.ui.context.AgentContextCardActions
import com.letta.mobile.ui.context.AgentContextCardBinding
import com.letta.mobile.ui.context.AgentContextCardDeps
import com.letta.mobile.ui.context.AgentContextCardHost
import com.letta.mobile.ui.context.AgentContextFocus
import com.letta.mobile.ui.context.AgentContextPresentation

/**
 * letta-mobile-3io8k: the sidebar's model-and-context card for the focused agent, opening as a
 * popover under it. Desktop only binds: the session's streamed readings and agent repository, the
 * host's model control (falling back to the chat's model list), and compaction over the Iroh relay
 * or, without one, the bundled App Server.
 */
@Composable
internal fun DesktopAgentContextCard(context: DesktopShellContext, frame: DesktopShellFrame) {
    val agentId = frame.focus.selectedAgentId ?: return
    val core = context.core
    val graph = core.sessionGraph.value
    val chatController = core.chatController
    val dataBindings = core.bootstrap.dataBindings
    val deps = remember(graph, dataBindings) {
        AgentContextCardDeps(
            readings = graph.contextTokenReadings,
            breakdown = ContextBreakdownLoader(graph.agentRepository),
            compaction = CompactionController(desktopCompactionRepository(dataBindings.modelControlRpc), graph.contextTokenReadings),
            pickerSource = DesktopModelControlHost(
                session = dataBindings.modelControl,
                chatModels = chatController.availableModels,
                reloadChatModels = chatController::reloadModelCatalog,
                onModelSelected = chatController::setConversationModel,
                recentModels = chatController.conversationManagement.recentModels,
            ).pickerSource(),
        )
    }
    val models by graph.modelRepository.llmModels.collectAsState()
    val selections by chatController.conversationModelSelections.collectAsState()
    val conversationId = frame.chatState.selectedConversationId
    val agent = frame.focus.rosterAgents.firstOrNull { it.id.value == agentId }
    val override = conversationId?.let(selections::get)
    val binding = remember(deps, chatController) {
        AgentContextCardBinding(
            deps = deps,
            actions = AgentContextCardActions(onModelSelected = { entry -> chatController.setConversationModel(entry.value) }),
            presentation = AgentContextPresentation.Popover,
        )
    }
    AgentContextCardHost(
        binding = binding,
        focus = AgentContextFocus(
            agentId = agentId,
            conversationId = conversationId,
            modelLabel = frame.chatState.composerModelLabel,
            modelValue = override ?: agent?.model,
            effort = null,
            windowTokens = contextWindowTokensOf(agent, models, override),
            turnRunning = frame.activity.isThinkingSelected || frame.activity.isStreamingReplySelected,
        ),
    )
}

/** The Iroh host's relay when connected over iroh://, else the bundled App Server directly. */
private fun desktopCompactionRepository(rpc: AdminRpcInvoker?): CompactionRepository {
    val direct = AppServerCompactionRepository(client = { DesktopLocalAppServerClientRegistry.shared.currentOrNull() })
    return rpc?.let { AdminRpcCompactionRepository(it).orElse(direct) } ?: direct
}
