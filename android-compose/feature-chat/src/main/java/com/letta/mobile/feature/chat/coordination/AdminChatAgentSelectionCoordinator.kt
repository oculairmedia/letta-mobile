package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Projects cached agent metadata without writing navigation or next-start preferences.
 */
internal class AdminChatAgentSelectionCoordinator(
    private val scope: CoroutineScope,
    private val agentId: AgentId,
    private val chatSessionResolver: ChatSessionResolver,
    private val uiStateMutable: MutableStateFlow<ChatUiState>,
) {
    fun seedAgentNameFromMemoryCache() {
        val cachedName = chatSessionResolver.cachedAgentName(agentId.value) ?: return
        uiStateMutable.update { current ->
            if (current.agentName.isBlank()) current.copy(agentName = cachedName) else current
        }
    }

    fun observeAgentNameCache() {
        scope.launch {
            chatSessionResolver.observeCachedAgentName(agentId.value)
                .collect { cachedName ->
                    if (cachedName.isBlank()) return@collect
                    uiStateMutable.update { current ->
                        if (current.agentName.isBlank()) current.copy(agentName = cachedName) else current
                    }
                }
        }
    }

}
