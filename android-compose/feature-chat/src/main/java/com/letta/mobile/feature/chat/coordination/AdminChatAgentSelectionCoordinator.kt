package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Persists agent name and last-opened conversation for resume shortcuts.
 */
internal class AdminChatAgentSelectionCoordinator(
    private val scope: CoroutineScope,
    private val agentId: AgentId,
    private val initialAgentName: String?,
    private val settingsRepository: ISettingsRepository,
    private val chatSessionResolver: ChatSessionResolver,
    private val uiState: StateFlow<ChatUiState>,
    private val uiStateMutable: MutableStateFlow<ChatUiState>,
    private val conversationId: () -> String?,
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

    fun observeLastChatSelection() {
        settingsRepository.setLastChatSelection(
            agentId = agentId.value,
            agentName = initialAgentName,
            conversationId = conversationId(),
        )
        scope.launch {
            uiState
                .map { state ->
                    val readyConversationId = (state.conversationState as? ConversationState.Ready)?.conversationId
                    state.agentName.takeIf { it.isNotBlank() } to readyConversationId
                }
                .distinctUntilChanged()
                .collect { (resolvedAgentName, resolvedConversationId) ->
                    settingsRepository.setLastChatSelection(
                        agentId = agentId.value,
                        agentName = resolvedAgentName,
                        conversationId = resolvedConversationId,
                    )
                }
        }
    }
}
