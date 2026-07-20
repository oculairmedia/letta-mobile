package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Resolves truncated tool-return previews when the user expands a tool card.
 */
internal class AdminChatTruncatedToolReturnCoordinator(
    private val scope: CoroutineScope,
    private val agentId: AgentId,
    private val timelineRepository: TimelineRepository,
    private val activeConversationId: () -> String?,
    private val fallbackConversationId: () -> String?,
) {
    private val requestedIds = mutableSetOf<String>()

    fun onTruncatedToolResultExpanded(messageId: String) {
        val convId = activeConversationId() ?: fallbackConversationId() ?: return
        synchronized(requestedIds) {
            if (!requestedIds.add(messageId)) return
        }
        scope.launch {
            val resolved = runCatching {
                timelineRepository.resolveTruncatedToolReturn(agentId.value, convId, messageId)
            }.onFailure { t ->
                Telemetry.error(
                    "AdminChat", "truncatedToolReturn.resolveFailed", t,
                    "conversationId" to convId,
                    "messageId" to messageId,
                )
            }.getOrDefault(false)
            if (!resolved) {
                synchronized(requestedIds) { requestedIds.remove(messageId) }
            }
        }
    }
}
