package com.letta.mobile.ui.screens.agentlist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ModelRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class AgentListUiState(
    val agents: ImmutableList<Agent> = persistentListOf(),
    val availableTools: ImmutableList<Tool> = persistentListOf(),
    val llmModels: ImmutableList<LlmModel> = persistentListOf(),
    val embeddingModels: ImmutableList<EmbeddingModel> = persistentListOf(),
    val favoriteAgentId: String? = null,
    val pinnedAgentIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val selectedTags: Set<String> = emptySet(),
    val isImporting: Boolean = false,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isCreating: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AgentListViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val settingsRepository: SettingsRepository,
    private val toolRepository: ToolRepository,
    private val modelRepository: ModelRepository,
) : ViewModel() {
    companion object {
        private const val LIST_CACHE_TTL_MS = 30_000L
    }

    private data class TransientState(
        val searchQuery: String = "",
        val selectedTags: Set<String> = emptySet(),
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val isCreating: Boolean = false,
        val isImporting: Boolean = false,
        val error: String? = null,
    )

    private val _transient = MutableStateFlow(
        TransientState(isLoading = agentRepository.agents.value.isEmpty())
    )

    /** Pre-combine models + transient into a single flow to stay within combine()'s 5-param limit. */
    private data class Overlay(
        val llm: List<LlmModel>,
        val emb: List<EmbeddingModel>,
        val transient: TransientState,
    )

    private val overlay = combine(
        modelRepository.llmModels,
        modelRepository.embeddingModels,
        _transient,
    ) { llm, emb, transient -> Overlay(llm, emb, transient) }

    val uiState: StateFlow<AgentListUiState> = combine(
        agentRepository.agents,
        settingsRepository.favoriteAgentId,
        settingsRepository.getPinnedAgentIds(),
        toolRepository.getTools(),
        overlay,
    ) { agents, favId, pinnedIds, tools, overlay ->
        AgentListUiState(
            agents = agents.toImmutableList(),
            availableTools = tools.toImmutableList(),
            llmModels = overlay.llm.toImmutableList(),
            embeddingModels = overlay.emb.toImmutableList(),
            favoriteAgentId = favId,
            pinnedAgentIds = pinnedIds,
            searchQuery = overlay.transient.searchQuery,
            selectedTags = overlay.transient.selectedTags,
            isLoading = overlay.transient.isLoading,
            isRefreshing = overlay.transient.isRefreshing,
            isCreating = overlay.transient.isCreating,
            isImporting = overlay.transient.isImporting,
            error = overlay.transient.error,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AgentListUiState())

    init {
        loadAgents()
        loadAvailableTools()
        loadAvailableModels()
    }

    fun loadAvailableTools() {
        viewModelScope.launch {
            try {
                toolRepository.refreshToolsIfStale(LIST_CACHE_TTL_MS)
            } catch (_: Exception) {
                Log.w("AgentListViewModel", "Failed to load available tools")
            }
        }
    }

    fun loadAvailableModels() {
        viewModelScope.launch {
            try {
                modelRepository.refreshLlmModels()
                modelRepository.refreshEmbeddingModels()
            } catch (_: Exception) {
                Log.w("AgentListViewModel", "Failed to load available models")
            }
        }
    }

    fun loadAgents() {
        viewModelScope.launch {
            if (agentRepository.agents.value.isEmpty()) {
                _transient.update { it.copy(isLoading = true, error = null) }
            }
            try {
                agentRepository.refreshAgentsIfStale(LIST_CACHE_TTL_MS)
                _transient.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _transient.update {
                    it.copy(
                        isLoading = false,
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
            _transient.update { it.copy(isRefreshing = true) }
            try {
                agentRepository.refreshAgents()
                _transient.update { it.copy(isRefreshing = false) }
            } catch (e: Exception) {
                _transient.update {
                    it.copy(
                        isRefreshing = false,
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
            val q = state.searchQuery.trim().lowercase()
            result = result.filter { agent ->
                agent.name.lowercase().contains(q) ||
                    (agent.description?.lowercase()?.contains(q) == true) ||
                    (agent.model?.lowercase()?.contains(q) == true) ||
                    agent.tags.any { it.lowercase().contains(q) }
            }
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

    fun deleteAgent(agentId: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                agentRepository.deleteAgent(agentId)
                if (uiState.value.favoriteAgentId == agentId) {
                    settingsRepository.setFavoriteAgentId(null)
                }
                onComplete()
            } catch (e: Exception) {
                _transient.update { it.copy(error = e.message ?: "Failed to delete agent") }
            }
        }
    }

    fun toggleFavorite(agentId: String) {
        val current = uiState.value.favoriteAgentId
        val newFav = if (current == agentId) null else agentId
        settingsRepository.setFavoriteAgentId(newFav)
    }

    fun togglePinned(agentId: String) {
        viewModelScope.launch {
            val isPinned = agentId in uiState.value.pinnedAgentIds
            settingsRepository.setAgentPinned(agentId, !isPinned)
        }
    }

    fun clearError() {
        _transient.update { it.copy(error = null) }
    }

    fun createAgent(params: AgentCreateParams, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            _transient.update { it.copy(isCreating = true) }
            try {
                val agent = agentRepository.createAgent(params)
                _transient.update { it.copy(isCreating = false) }
                agentRepository.refreshAgents()
                onSuccess(agent.id)
            } catch (e: Exception) {
                _transient.update {
                    it.copy(
                        isCreating = false,
                        error = mapErrorToUserMessage(e, "Failed to create agent"),
                    )
                }
            }
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
                    fileName = fileName,
                    fileBytes = fileBytes,
                    overrideName = overrideName?.takeIf { it.isNotBlank() },
                    overrideExistingTools = overrideExistingTools,
                    stripMessages = stripMessages,
                )
                _transient.update { it.copy(isImporting = false) }
                onSuccess(response)
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
}
