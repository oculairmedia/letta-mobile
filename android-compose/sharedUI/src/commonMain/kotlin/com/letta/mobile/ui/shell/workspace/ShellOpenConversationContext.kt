package com.letta.mobile.ui.shell.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.letta.mobile.data.context.limit.AgentContextRoutes
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices
import com.letta.mobile.data.repository.modelcontrol.ModelControlSession
import com.letta.mobile.data.repository.modelcontrol.ModelPickerSource
import com.letta.mobile.data.session.graph.SharedSessionRepositoryGraph
import com.letta.mobile.data.session.llmModelsOrEmpty
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ConversationModels
import com.letta.mobile.ui.context.AgentContextCardActions
import com.letta.mobile.ui.context.AgentContextCardBinding
import com.letta.mobile.ui.context.AgentContextSession
import com.letta.mobile.ui.context.ContextConversationFocus
import com.letta.mobile.ui.context.ContextWindowSources
import com.letta.mobile.ui.context.FocusedAgentContextCard
import com.letta.mobile.ui.context.rememberFocusedContextUsage
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * The context meter and agent context card of a shell whose page serves
 * one conversation through its own session port (the web client's TimelineChatSessionPort). The
 * host hands over plain session pieces; the reading, windows and card are the shared ones above.
 */

/** The session the meter and card read: its graph, the context routes, the model control and the roster. */
class OpenConversationContextSession(
    val graph: SharedSessionRepositoryGraph,
    val routes: AgentContextRoutes,
    /** The host's admin model catalog, which the card's picker lists first; null lists the chat's models. */
    val modelControl: ModelControlSession?,
    val agents: List<Agent>,
)

/** The open conversation: its port and, when the port has a model chip, its models and picks. */
class OpenConversation(
    val agentId: String,
    val conversationId: String,
    val port: ChatSessionPort,
    val models: ConversationModels?,
)

/** The composer's meter for [conversation], handed back through [onUsage] as the port's host input. */
@Composable
fun OpenConversationContextMeter(
    session: OpenConversationContextSession,
    conversation: OpenConversation,
    onUsage: (ContextWindowUsageState) -> Unit,
) {
    val usage = rememberFocusedContextUsage(
        focus = rememberOpenConversationFocus(focusedAgent = null, conversation),
        readings = session.graph.contextTokenReadings,
        breakdown = session.graph.contextBreakdown,
        window = rememberOpenConversationWindow(session, conversation),
    )
    LaunchedEffect(usage) { onUsage(usage) }
}

/** The [focusedAgent]'s model, context and Compact card, over the open [conversation] when there is one. */
@Composable
fun OpenConversationAgentContextCard(
    session: OpenConversationContextSession,
    focusedAgent: AgentId?,
    conversation: OpenConversation?,
) {
    val models = conversation?.models
    val binding = remember(session.graph, session.routes, session.modelControl, models) {
        val pickerSource = models?.let { ModelPickerSource.forChat(session.modelControl, it.available, it::reload) }
        val graph = session.graph
        AgentContextCardBinding(
            deps = AgentContextSession(graph.contextTokenReadings, graph.contextBreakdown, session.routes).cardDeps(pickerSource),
            actions = AgentContextCardActions(onModelSelected = { entry -> models?.select(entry.value) }),
        )
    }
    val composer = conversation?.port?.composer?.collectAsState()?.value
    FocusedAgentContextCard(
        binding = binding,
        focus = rememberOpenConversationFocus(focusedAgent, conversation),
        window = rememberOpenConversationWindow(session, conversation),
        modelLabel = composer?.model?.currentLabel,
    )
}

/** The open conversation (else just the [focusedAgent]), and whether a turn runs in it. */
@Composable
private fun rememberOpenConversationFocus(focusedAgent: AgentId?, conversation: OpenConversation?): ContextConversationFocus {
    val ui = conversation?.port?.uiState?.collectAsState()?.value
    return ContextConversationFocus(
        agentId = conversation?.agentId ?: focusedAgent?.value,
        conversationId = conversation?.conversationId,
        turnRunning = ui != null && (ui.isStreaming || ui.isAgentTyping),
    )
}

/**
 * The roster, the model list and the conversation's own model: what the windows are sized from. The
 * open conversation's list is its model chip's; without one, the session graph's.
 */
@Composable
private fun rememberOpenConversationWindow(session: OpenConversationContextSession, conversation: OpenConversation?): ContextWindowSources {
    val graph = session.graph
    val models = conversation?.models
    val list by remember(graph, models) { models?.available ?: graph.llmModelsOrEmpty() }.collectAsState()
    val selections by remember(models) { models?.selections ?: MutableStateFlow(ConversationModelChoices()) }.collectAsState()
    return ContextWindowSources(session.agents, list, selections)
}
