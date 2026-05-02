package com.letta.mobile.ui.screens.conversations

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.ConversationInspectorMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.AllConversationsRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class ConversationDisplay(
    val conversation: Conversation,
    val agentName: String,
    val isPinned: Boolean = false,
)

@androidx.compose.runtime.Immutable
data class ConversationsUiState(
    val conversations: ImmutableList<ConversationDisplay> = persistentListOf(),
    val agents: ImmutableList<Agent> = persistentListOf(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val searchQuery: String = "",
    val selectedConversation: ConversationDisplay? = null,
    val inspectorMessages: ImmutableList<ConversationInspectorMessage> = persistentListOf(),
    val isInspectorLoading: Boolean = false,
    val inspectorError: String? = null,
    val recompilePreview: String? = null,
    val error: String? = null,
)

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val allConversationsRepository: AllConversationsRepository,
    private val conversationRepository: ConversationRepository,
    private val agentRepository: AgentRepository,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    companion object {
        private const val LIST_CACHE_TTL_MS = 30_000L
    }

    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    private var agentNameCache = mutableMapOf<String, String>()
    private var pinnedConversationIds: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            settingsRepository.getPinnedConversationIds().collectLatest { pinnedIds ->
                pinnedConversationIds = pinnedIds
                val selectedConversation = _uiState.value.selectedConversation
                _uiState.value = _uiState.value.copy(
                    conversations = applyPinnedState(_uiState.value.conversations).toImmutableList(),
                    selectedConversation = selectedConversation?.let {
                        it.copy(isPinned = it.conversation.id in pinnedIds)
                    },
                )
            }
        }
        val cachedAgents = agentRepository.agents.value
        if (cachedAgents.isNotEmpty()) {
            agentNameCache = cachedAgents.associate { it.id to it.name }.toMutableMap()
        }
        val cachedConversations = allConversationsRepository.conversations.value
        if (cachedConversations.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                conversations = applyPinnedState(cachedConversations.map { it.toDisplay() }).toImmutableList(),
                agents = cachedAgents.toImmutableList(),
                isLoading = false,
            )
        }
        loadConversations()
    }

    fun loadConversations() {
        viewModelScope.launch {
            if (_uiState.value.conversations.isEmpty()) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }
            try {
                launch { 
                    agentRepository.refreshAgentsIfStale(LIST_CACHE_TTL_MS)
                    agentNameCache = agentRepository.agents.value
                        .associate { it.id to it.name }
                        .toMutableMap()
                    _uiState.value = _uiState.value.copy(
                        agents = agentRepository.agents.value.toImmutableList(),
                    )
                }
                launch {
                    allConversationsRepository.refreshIfStale(LIST_CACHE_TTL_MS)
                    _uiState.value = _uiState.value.copy(
                        conversations = applyPinnedState(allConversationsRepository.conversations.value.map { it.toDisplay() }).toImmutableList(),
                        isLoading = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                Log.w("ConversationsVM", "Load failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = if (_uiState.value.conversations.isEmpty()) e.message else null,
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            try {
                allConversationsRepository.refresh()
                _uiState.value = _uiState.value.copy(
                    conversations = applyPinnedState(allConversationsRepository.conversations.value.map { it.toDisplay() }).toImmutableList(),
                    agents = agentRepository.agents.value.toImmutableList(),
                    isRefreshing = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            val display = _uiState.value.conversations.firstOrNull { it.conversation.id == conversationId } ?: return@launch
            try {
                allConversationsRepository.handleOptimisticDelete(conversationId)
                _uiState.value = _uiState.value.copy(
                    conversations = _uiState.value.conversations.filter { it.conversation.id != conversationId }.toImmutableList(),
                    selectedConversation = if (_uiState.value.selectedConversation?.conversation?.id == conversationId) null else _uiState.value.selectedConversation,
                )
                conversationRepository.deleteConversation(conversationId, display.conversation.agentId)
            } catch (e: Exception) {
                Log.w("ConversationsVM", "Delete failed", e)
                loadConversations()
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun getFilteredConversations(): List<ConversationDisplay> {
        val state = _uiState.value
        if (state.searchQuery.isBlank()) return state.conversations
        val q = state.searchQuery.trim().lowercase()
        return state.conversations.filter { display ->
            (display.conversation.summary?.lowercase()?.contains(q) == true) ||
                display.agentName.lowercase().contains(q) ||
                display.conversation.id.lowercase().contains(q)
        }
    }

    fun renameConversation(conversationId: String, agentId: String, newName: String) {
        viewModelScope.launch {
            try {
                conversationRepository.updateConversation(conversationId, agentId, newName)
                val selectedConversation = _uiState.value.selectedConversation
                _uiState.value = _uiState.value.copy(
                    conversations = _uiState.value.conversations.map {
                        if (it.conversation.id == conversationId) {
                            it.copy(conversation = it.conversation.copy(summary = newName))
                        } else it
                    }.toImmutableList(),
                    selectedConversation = selectedConversation?.takeIf { it.conversation.id == conversationId }
                        ?.copy(conversation = selectedConversation.conversation.copy(summary = newName))
                        ?: selectedConversation,
                )
            } catch (e: Exception) {
                Log.w("ConversationsVM", "Rename failed", e)
            }
        }
    }

    fun forkConversation(conversationId: String, agentId: String, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val forked = conversationRepository.forkConversation(conversationId, agentId)
                onSuccess(forked.id)
                loadConversations()
            } catch (e: Exception) {
                Log.w("ConversationsVM", "Fork failed", e)
            }
        }
    }

    fun openConversationAdmin(display: ConversationDisplay) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                selectedConversation = display,
                isInspectorLoading = true,
                inspectorMessages = persistentListOf(),
                inspectorError = null,
                recompilePreview = null,
            )
            try {
                val conversation = conversationRepository.getConversation(display.conversation.id)
                val inspectorResult = runCatching {
                    messageRepository.fetchConversationInspectorMessages(display.conversation.id)
                }
                _uiState.value = _uiState.value.copy(
                    selectedConversation = display.copy(conversation = conversation),
                    inspectorMessages = inspectorResult.getOrDefault(emptyList()).toImmutableList(),
                    inspectorError = inspectorResult.exceptionOrNull()?.message,
                    isInspectorLoading = false,
                )
            } catch (e: Exception) {
                Log.w("ConversationsVM", "Admin detail load failed", e)
                _uiState.value = _uiState.value.copy(
                    selectedConversation = null,
                    inspectorMessages = persistentListOf(),
                    inspectorError = e.message,
                    isInspectorLoading = false,
                )
            }
        }
    }

    fun closeConversationAdmin() {
        _uiState.value = _uiState.value.copy(
            selectedConversation = null,
            inspectorMessages = persistentListOf(),
            isInspectorLoading = false,
            inspectorError = null,
            recompilePreview = null,
        )
    }

    fun setConversationArchived(display: ConversationDisplay, archived: Boolean) {
        viewModelScope.launch {
            try {
                conversationRepository.setConversationArchived(display.conversation.id, display.conversation.agentId, archived)
                _uiState.value = _uiState.value.copy(
                    conversations = _uiState.value.conversations.map {
                        if (it.conversation.id == display.conversation.id) {
                            it.copy(conversation = it.conversation.copy(archived = archived))
                        } else it
                    }.toImmutableList(),
                    selectedConversation = _uiState.value.selectedConversation?.takeIf { it.conversation.id == display.conversation.id }
                        ?.copy(conversation = display.conversation.copy(archived = archived))
                        ?: _uiState.value.selectedConversation,
                )
            } catch (e: Exception) {
                Log.w("ConversationsVM", "Archive toggle failed", e)
            }
        }
    }

    fun cancelConversationRuns(display: ConversationDisplay) {
        viewModelScope.launch {
            try {
                conversationRepository.cancelConversation(display.conversation.id, display.conversation.agentId)
            } catch (e: Exception) {
                Log.w("ConversationsVM", "Cancel failed", e)
            }
        }
    }

    fun recompileConversation(display: ConversationDisplay) {
        viewModelScope.launch {
            try {
                val result = conversationRepository.recompileConversation(display.conversation.id, false, display.conversation.agentId)
                _uiState.value = _uiState.value.copy(recompilePreview = result)
            } catch (e: Exception) {
                Log.w("ConversationsVM", "Recompile failed", e)
            }
        }
    }

    fun createConversation(agentId: String, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val conversation = conversationRepository.createConversation(agentId)
                onSuccess(conversation.id)
                allConversationsRepository.handleOptimisticUpdate(conversation)
                _uiState.value = _uiState.value.copy(
                    conversations = applyPinnedState(_uiState.value.conversations + conversation.toDisplay()).toImmutableList()
                )
            } catch (e: Exception) {
                Log.w("ConversationsVM", "Create failed", e)
            }
        }
    }

    fun toggleConversationPinned(display: ConversationDisplay) {
        viewModelScope.launch {
            val nextPinned = !display.isPinned
            settingsRepository.setConversationPinned(display.conversation.id, nextPinned)
            val updatedPinnedIds = if (nextPinned) {
                pinnedConversationIds + display.conversation.id
            } else {
                pinnedConversationIds - display.conversation.id
            }
            pinnedConversationIds = updatedPinnedIds
            _uiState.value = _uiState.value.copy(
                conversations = applyPinnedState(_uiState.value.conversations, updatedPinnedIds).toImmutableList(),
                selectedConversation = _uiState.value.selectedConversation?.takeIf {
                    it.conversation.id == display.conversation.id
                }?.copy(isPinned = nextPinned) ?: _uiState.value.selectedConversation,
            )
        }
    }

    private fun Conversation.toDisplay() = ConversationDisplay(
        conversation = this,
        agentName = agentNameCache[agentId] ?: agentId.take(8),
        isPinned = id in pinnedConversationIds,
    )

    private fun applyPinnedState(
        displays: List<ConversationDisplay>,
        pinnedIds: Set<String> = pinnedConversationIds,
    ): List<ConversationDisplay> = displays
        .map { it.copy(isPinned = it.conversation.id in pinnedIds) }
        .sortedWith(
            compareByDescending<ConversationDisplay> { it.isPinned }
                .thenByDescending { conversationSortInstant(it.conversation) }
        )

    private fun conversationSortInstant(conversation: Conversation): Instant = runCatching {
        Instant.parse(conversation.lastMessageAt ?: conversation.createdAt ?: Instant.EPOCH.toString())
    }.getOrDefault(Instant.EPOCH)
}
