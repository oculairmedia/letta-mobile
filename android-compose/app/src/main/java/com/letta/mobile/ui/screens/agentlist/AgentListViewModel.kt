package com.letta.mobile.ui.screens.agentlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.repository.AgentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class AgentListUiState(
    val searchQuery: String = "",
    val isCreating: Boolean = false,
    val isLoadingAllAgents: Boolean = false,
    val allAgents: List<Agent> = emptyList(),
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AgentListViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentListUiState())
    val uiState: StateFlow<AgentListUiState> = _uiState.asStateFlow()

    private val _refreshTrigger = MutableStateFlow(0)

    val agentsPaged: Flow<PagingData<Agent>> = _refreshTrigger
        .flatMapLatest {
            agentRepository.getAgentsPaged()
        }
        .cachedIn(viewModelScope)

    fun refresh() {
        _refreshTrigger.value++
    }

    fun deleteAgent(agentId: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                agentRepository.deleteAgent(agentId)
                refresh()
                onComplete()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to delete agent")
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)

        // Load all agents when user starts searching
        if (query.isNotBlank() && _uiState.value.allAgents.isEmpty() && !_uiState.value.isLoadingAllAgents) {
            loadAllAgents()
        }
    }

    private fun loadAllAgents() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingAllAgents = true)
            try {
                agentRepository.refreshAgents()
                val agents = agentRepository.agents.value
                _uiState.value = _uiState.value.copy(
                    allAgents = agents,
                    isLoadingAllAgents = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingAllAgents = false)
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
                refresh()
                onSuccess(agent.id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCreating = false,
                    error = e.message ?: "Failed to create agent"
                )
            }
        }
    }
}
