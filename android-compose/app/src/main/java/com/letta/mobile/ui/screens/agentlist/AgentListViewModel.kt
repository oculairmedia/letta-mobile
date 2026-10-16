package com.letta.mobile.ui.screens.agentlist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSearchMatcher
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The agent list's state. Creating an agent is the shared agent editor's new-agent flow
 * (letta-mobile-ckmub.3.24); importing one from a file stays here.
 */
data class AgentListUiState(
    val agents: ImmutableList<Agent> = persistentListOf(),
    val favoriteAgentId: AgentId? = null,
    val pinnedAgentIds: Set<AgentId> = emptySet(),
    val searchQuery: String = "",
    val selectedTags: Set<String> = emptySet(),
    val isImporting: Boolean = false,
    val isLoading: Boolean = true,
    val isHydrating: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    // Dialog state (hoisted from composable)
    val showImportDialog: Boolean = false,
    val isSearchExpanded: Boolean = false,
    val showGrid: Boolean = false,
    val pendingImportName: String? = null,
    val pendingImportOverrideTools: Boolean = true,
    val pendingImportStripMessages: Boolean = false,
)

@HiltViewModel
class AgentListViewModel @Inject constructor(
    private val agentRepository: IAgentRepository,
    private val settingsRepository: ISettingsRepository,
) : ViewModel() {
    companion object {
        private const val LIST_CACHE_TTL_MS = 30_000L
        private const val STATE_STOP_TIMEOUT_MS = 5_000L
    }

    private data class TransientState(
        val searchQuery: String = "",
        val selectedTags: Set<String> = emptySet(),
        val isLoading: Boolean = true,
        val isHydrating: Boolean = false,
        val isRefreshing: Boolean = false,
        val isImporting: Boolean = false,
        val error: String? = null,
        // Dialog state (hoisted from composable)
        val showImportDialog: Boolean = false,
        val isSearchExpanded: Boolean = false,
        val showGrid: Boolean = false,
        val pendingImportName: String? = null,
        val pendingImportOverrideTools: Boolean = true,
        val pendingImportStripMessages: Boolean = false,
    )

    private val _transient = MutableStateFlow(
        TransientState(isLoading = agentRepository.agents.value.isEmpty())
    )

    val uiState: StateFlow<AgentListUiState> = combine(
        agentRepository.agents,
        settingsRepository.favoriteAgentId,
        settingsRepository.getPinnedAgentIds(),
        _transient,
    ) { agents, favId, pinnedIds, transient ->
        val displayAgents = agents
        AgentListUiState(
            agents = displayAgents.toImmutableList(),
            favoriteAgentId = favId?.let { AgentId(it) },
            pinnedAgentIds = pinnedIds.map { AgentId(it) }.toSet(),
            searchQuery = transient.searchQuery,
            selectedTags = transient.selectedTags,
            isLoading = transient.isLoading && agents.isEmpty(),
            isHydrating = transient.isHydrating,
            isRefreshing = transient.isRefreshing,
            isImporting = transient.isImporting,
            error = transient.error,
            showImportDialog = transient.showImportDialog,
            isSearchExpanded = transient.isSearchExpanded,
            showGrid = transient.showGrid,
            pendingImportName = transient.pendingImportName,
            pendingImportOverrideTools = transient.pendingImportOverrideTools,
            pendingImportStripMessages = transient.pendingImportStripMessages,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS), AgentListUiState())

    init {
        loadAgents()
        // letta-mobile-ze5l: refetch on backend switch.
        viewModelScope.launch {
            settingsRepository.activeConfigChanges.collect { refresh() }
        }
    }

    fun loadAgents() {
        viewModelScope.launch {
            if (agentRepository.agents.value.isEmpty()) {
                _transient.update { it.copy(isLoading = true, isHydrating = true, error = null) }
            } else {
                _transient.update { it.copy(isHydrating = true, error = null) }
            }
            try {
                // Local mode routes inside AgentRepository (on-device store).
                val firstPageMarker = launch {
                    agentRepository.agents
                        .drop(if (agentRepository.agents.value.isEmpty()) 0 else 1)
                        .first { it.isNotEmpty() }
                        .let { agents ->
                            _transient.update { it.copy(isLoading = false) }
                            Log.i("AgentListViewModel", "AgentList first-page ready count=${agents.size}")
                        }
                }
                agentRepository.refreshAgentsIfStale(LIST_CACHE_TTL_MS)
                firstPageMarker.cancel()
                _transient.update { it.copy(isLoading = false, isHydrating = false) }
                Log.i("AgentListViewModel", "AgentList hydrated count=${agentRepository.agents.value.size}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AgentListViewModel", "AgentList hydrate failed", e)
                _transient.update {
                    it.copy(
                        isLoading = false,
                        isHydrating = false,
                        error = if (agentRepository.agents.value.isEmpty()) {
                            e.message ?: "Failed to load agents"
                        } else null,
                    )
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _transient.update { it.copy(isRefreshing = true, isHydrating = true) }
            try {
                agentRepository.refreshAgents()
                _transient.update { it.copy(isRefreshing = false, isHydrating = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _transient.update {
                    it.copy(
                        isRefreshing = false,
                        isHydrating = false,
                        error = e.message ?: "Failed to refresh",
                    )
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _transient.update { it.copy(searchQuery = query) }
    }

    fun getFilteredAgents(): List<Agent> {
        val state = uiState.value
        var result: List<Agent> = state.agents

        if (state.selectedTags.isNotEmpty()) {
            result = result.filter { agent ->
                state.selectedTags.all { tag -> tag in agent.tags }
            }
        }

        if (state.searchQuery.isNotBlank()) {
            result = AgentSearchMatcher.filter(result, state.searchQuery)
        }

        return result
    }

    fun getAllTags(): List<String> {
        return uiState.value.agents
            .flatMap { it.tags }
            .distinct()
            .sorted()
    }

    fun toggleTag(tag: String) {
        _transient.update { current ->
            val updated = if (tag in current.selectedTags) {
                current.selectedTags - tag
            } else {
                current.selectedTags + tag
            }
            current.copy(selectedTags = updated)
        }
    }

    fun clearTags() {
        _transient.update { it.copy(selectedTags = emptySet()) }
    }

    fun deleteAgent(agentId: AgentId, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                agentRepository.deleteAgent(agentId)
                if (uiState.value.favoriteAgentId == agentId) {
                    settingsRepository.setFavoriteAgentId(null)
                }
                onComplete()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _transient.update { it.copy(error = e.message ?: "Failed to delete agent") }
            }
        }
    }

    fun toggleFavorite(agentId: AgentId) {
        val current = uiState.value.favoriteAgentId
        val newFav = if (current == agentId) null else agentId.value
        settingsRepository.setFavoriteAgentId(newFav)
    }

    fun togglePinned(agentId: AgentId) {
        viewModelScope.launch {
            val isPinned = agentId in uiState.value.pinnedAgentIds
            settingsRepository.setAgentPinned(agentId.value, !isPinned)
        }
    }

    fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String?,
        overrideExistingTools: Boolean,
        stripMessages: Boolean,
        onSuccess: (ImportedAgentsResponse) -> Unit,
    ) {
        viewModelScope.launch {
            _transient.update { it.copy(isImporting = true, error = null) }
            try {
                val response = agentRepository.importAgent(
                    AgentImportParams(
                        fileName = fileName,
                        fileBytes = fileBytes,
                        overrideName = overrideName?.takeIf { it.isNotBlank() },
                        overrideExistingTools = overrideExistingTools,
                        stripMessages = stripMessages,
                    ),
                )
                _transient.update { it.copy(isImporting = false) }
                onSuccess(response)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _transient.update {
                    it.copy(
                        isImporting = false,
                        error = mapErrorToUserMessage(e, "Failed to import agent"),
                    )
                }
            }
        }
    }

    // Dialog state management (hoisted from composable)
    fun showImportDialog() {
        _transient.update { it.copy(showImportDialog = true) }
    }

    fun hideImportDialog() {
        _transient.update { it.copy(showImportDialog = false) }
    }

    fun setSearchExpanded(expanded: Boolean) {
        _transient.update { it.copy(isSearchExpanded = expanded) }
    }

    fun setShowGrid(show: Boolean) {
        _transient.update { it.copy(showGrid = show) }
    }

    fun setPendingImportName(name: String?) {
        _transient.update { it.copy(pendingImportName = name) }
    }

    fun setPendingImportOverrideTools(override: Boolean) {
        _transient.update { it.copy(pendingImportOverrideTools = override) }
    }

    fun setPendingImportStripMessages(strip: Boolean) {
        _transient.update { it.copy(pendingImportStripMessages = strip) }
    }
}
