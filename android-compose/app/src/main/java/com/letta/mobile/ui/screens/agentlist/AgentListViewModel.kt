package com.letta.mobile.ui.screens.agentlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class AgentListUiState(
    val agents: List<Agent> = emptyList(),
    val favoriteAgentId: String? = null,
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isCreating: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AgentListViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentListUiState())
    val uiState: StateFlow<AgentListUiState> = _uiState.asStateFlow()

    init {
        val cached = agentRepository.agents.value
        if (cached.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                agents = cached,
                favoriteAgentId = settingsRepository.favoriteAgentId.value,
                isLoading = false,
            )
        }
        loadAgents()
    }

    fun loadAgents() {
        viewModelScope.launch {
            if (_uiState.value.agents.isEmpty()) {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            }
            try {
                agentRepository.refreshAgents()
                _uiState.value = _uiState.value.copy(
                    agents = agentRepository.agents.value,
                    favoriteAgentId = settingsRepository.favoriteAgentId.value,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = if (_uiState.value.agents.isEmpty()) e.message ?: "Failed to load agents" else null,
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            try {
                agentRepository.refreshAgents()
                _uiState.value = _uiState.value.copy(
                    agents = agentRepository.agents.value,
                    favoriteAgentId = settingsRepository.favoriteAgentId.value,
                    isRefreshing = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    error = e.message ?: "Failed to refresh",
                )
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun getFilteredAgents(): List<Agent> {
        val state = _uiState.value
        if (state.searchQuery.isBlank()) return state.agents
        val q = state.searchQuery.trim().lowercase()
        return state.agents.filter { agent ->
            agent.name.lowercase().contains(q) ||
                (agent.description?.lowercase()?.contains(q) == true) ||
                (agent.model?.lowercase()?.contains(q) == true) ||
                (agent.tags?.any { it.lowercase().contains(q) } == true)
        }
    }

    fun deleteAgent(agentId: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                agentRepository.deleteAgent(agentId)
                _uiState.value = _uiState.value.copy(
                    agents = _uiState.value.agents.filter { it.id != agentId }
                )
                onComplete()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to delete agent")
            }
        }
    }

    fun toggleFavorite(agentId: String) {
        val current = _uiState.value.favoriteAgentId
        val newFav = if (current == agentId) null else agentId
        settingsRepository.setFavoriteAgentId(newFav)
        _uiState.value = _uiState.value.copy(favoriteAgentId = newFav)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun createAgent(params: AgentCreateParams, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreating = true)
            try {
                val agent = agentRepository.createAgent(params)
                _uiState.value = _uiState.value.copy(isCreating = false)
                loadAgents()
                onSuccess(agent.id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCreating = false,
                    error = com.letta.mobile.util.mapErrorToUserMessage(e, "Failed to create agent"),
                )
            }
        }
    }
}
