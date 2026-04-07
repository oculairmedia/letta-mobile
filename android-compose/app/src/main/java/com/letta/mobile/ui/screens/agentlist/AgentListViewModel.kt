package com.letta.mobile.ui.screens.agentlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.repository.AgentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class AgentListUiState(
    val agents: List<Agent> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isCreating: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AgentListViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentListUiState())
    val uiState: StateFlow<AgentListUiState> = _uiState.asStateFlow()

    init {
        loadAgents()
    }

    fun loadAgents() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                agentRepository.refreshAgents()
                _uiState.value = _uiState.value.copy(
                    agents = agentRepository.agents.value,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load agents",
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

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun createAgent(name: String, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreating = true)
            try {
                val agent = agentRepository.createAgent(AgentCreateParams(name = name))
                _uiState.value = _uiState.value.copy(isCreating = false)
                loadAgents()
                onSuccess(agent.id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCreating = false,
                    error = e.message ?: "Failed to create agent",
                )
            }
        }
    }
}
