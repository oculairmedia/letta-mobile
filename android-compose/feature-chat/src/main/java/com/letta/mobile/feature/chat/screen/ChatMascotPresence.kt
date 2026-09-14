package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.data.presence.AgentPresenceResolver
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.mascot.LocalMascotRegistry

/**
 * Feeds the open conversation's coarse signals to the shared [AgentPresenceResolver] and publishes
 * the result on the shell's mascot registry, so this agent's mascots - the top-bar chip, the
 * composer companion - think, speak, listen and flinch with the run. The chat screen knows one
 * conversation; the resolver still owns the mapping, exactly as on desktop. It publishes for this
 * agent only and forgets only this agent when the screen leaves, so another presence source (a
 * list-wide one, later) is never wiped by a chat closing.
 */
@Composable
internal fun ChatMascotPresenceSync(
    agentId: String,
    conversationId: String?,
    state: ChatUiState,
    composerText: String,
) {
    val registry = LocalMascotRegistry.current
    // Before the first message a conversation has no id yet; key the run on the agent so the
    // mascot still reacts to the very first send.
    val runKey = conversationId ?: agentId
    val presence = remember(agentId, runKey, state.isStreaming, state.isAgentTyping, state.error, composerText) {
        AgentPresenceResolver.resolve(
            conversations = listOf(
                ChatConversationSummary(
                    id = runKey,
                    title = "",
                    agentName = "",
                    updatedAtLabel = "",
                    lastMessagePreview = "",
                    agentId = agentId,
                ),
            ),
            // Busy from send to terminal: the streaming flag covers the run, the typing dots the
            // gaps before the first token and between tool phases.
            runningConversationId = runKey.takeIf { state.isStreaming || state.isAgentTyping },
            streamingTokens = state.isStreaming && !state.isAgentTyping,
            selectedConversationId = runKey,
            composerText = composerText,
            errorConversationId = runKey.takeIf { state.error != null },
        )
    }
    SideEffect { registry.setPresence(agentId, presence[agentId] ?: AgentPresence.IDLE) }
    DisposableEffect(registry, agentId) { onDispose { registry.clearPresence(agentId) } }
}
