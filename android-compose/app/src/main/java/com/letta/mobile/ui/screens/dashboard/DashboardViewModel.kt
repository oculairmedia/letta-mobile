package com.letta.mobile.ui.screens.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.toParsed
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.AllConversationsRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.RunRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PinnedAgent(val id: String, val name: String)

@androidx.compose.runtime.Immutable
data class DashboardUiState(
    val serverUrl: String = "",
    val isConnected: Boolean = false,
    val agentCount: Int? = null,
    val conversationCount: Int? = null,
    val toolCount: Int? = null,
    val blockCount: Int? = null,
    val usageSummary: DashboardUsageSummary? = null,
    val isUsageLoading: Boolean = true,
    val favoriteAgentId: String? = null,
    val favoriteAgentName: String? = null,
    val pinnedAgents: ImmutableList<PinnedAgent> = persistentListOf(),
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val isSearchActive: Boolean = false,
    val agentResults: ImmutableList<Agent> = persistentListOf(),
    val messageResults: ImmutableList<ParsedSearchMessage> = persistentListOf(),
    val toolResults: ImmutableList<Tool> = persistentListOf(),
    val blockResults: ImmutableList<Block> = persistentListOf(),
    val error: String? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val allConversationsRepository: AllConversationsRepository,
    private val toolRepository: ToolRepository,
    private val blockRepository: IBlockRepository,
    private val settingsRepository: SettingsRepository,
    private val messageRepository: MessageRepository,
    private val runRepository: RunRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private var cachedBlocks: List<Block> = emptyList()

    private val _uiState = MutableStateFlow(
        DashboardUiState(
            serverUrl = settingsRepository.activeConfig.value?.serverUrl ?: "",
            favoriteAgentId = settingsRepository.favoriteAgentId.value,
        )
    )
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        migrateAdminToFavorite()
        loadProgressively()
        observeFavoriteAndPinned()
        setupSearch()
    }

    private fun migrateAdminToFavorite() {
        if (settingsRepository.favoriteAgentId.value == null &&
            settingsRepository.adminAgentId.value != null
        ) {
            settingsRepository.setFavoriteAgentId(settingsRepository.adminAgentId.value)
        }
    }

    private fun observeFavoriteAndPinned() {
        viewModelScope.launch {
            combine(
                settingsRepository.favoriteAgentId,
                settingsRepository.getPinnedAgentIds(),
            ) { favId, pinnedIds -> favId to pinnedIds }
                .collect { (favId, pinnedIds) ->
                    val favName = favId?.let { agentRepository.getCachedAgent(it)?.name }
                    val pinned = pinnedIds.mapNotNull { id ->
                        agentRepository.getCachedAgent(id)?.let { PinnedAgent(it.id, it.name) }
                    }
                    _uiState.value = _uiState.value.copy(
                        favoriteAgentId = favId,
                        favoriteAgentName = favName,
                        pinnedAgents = pinned.toImmutableList(),
                    )
                    if (favId != null && favName == null) {
                        try {
                            val fetched = agentRepository.getAgent(favId).first()
                            _uiState.value = _uiState.value.copy(favoriteAgentName = fetched.name)
                        } catch (_: Exception) { }
                    }
                }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            isSearchActive = query.isNotBlank(),
        )
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _uiState.value = _uiState.value.copy(
            searchQuery = "",
            isSearchActive = false,
            agentResults = persistentListOf(),
            messageResults = persistentListOf(),
            toolResults = persistentListOf(),
            blockResults = persistentListOf(),
        )
    }

    private fun setupSearch() {
        viewModelScope.launch {
            _searchQuery
                .debounce(300L)
                .distinctUntilChanged()
                .collect { query ->
                    if (query.isBlank()) {
                        _uiState.value = _uiState.value.copy(
                            agentResults = persistentListOf(),
                            messageResults = persistentListOf(),
                            toolResults = persistentListOf(),
                            blockResults = persistentListOf(),
                            isSearching = false,
                        )
                        return@collect
                    }

                    val q = query.trim().lowercase()
                    val agents = agentRepository.agents.value.filter { agent ->
                        agent.name.lowercase().contains(q) ||
                            (agent.description?.lowercase()?.contains(q) == true)
                    }
                    val tools = toolRepository.getTools().value.filter { tool ->
                        tool.name.lowercase().contains(q) ||
                            (tool.description?.lowercase()?.contains(q) == true)
                    }
                    val blocks = cachedBlocks.filter { block ->
                        (block.label?.lowercase()?.contains(q) == true) ||
                            (block.description?.lowercase()?.contains(q) == true) ||
                            block.value.lowercase().contains(q)
                    }
                    _uiState.value = _uiState.value.copy(
                        agentResults = agents.toImmutableList(),
                        toolResults = tools.toImmutableList(),
                        blockResults = blocks.toImmutableList(),
                        isSearching = true,
                    )

                    try {
                        val results = messageRepository.searchMessages(
                            MessageSearchRequest(
                                query = query,
                                roles = listOf("user", "assistant"),
                                limit = 20,
                            )
                        )
                        _uiState.value = _uiState.value.copy(
                            messageResults = results.map { it.toParsed() }.toImmutableList(),
                            isSearching = false,
                        )
                    } catch (e: Exception) {
                        Log.w("DashboardVM", "Message search failed", e)
                        _uiState.value = _uiState.value.copy(isSearching = false)
                    }
                }
        }
    }

    fun clearFavorite() {
        settingsRepository.setFavoriteAgentId(null)
    }

    fun unpinAgent(agentId: String) {
        viewModelScope.launch {
            settingsRepository.setAgentPinned(agentId, false)
        }
    }

    fun loadProgressively() {
        viewModelScope.launch {
            try {
                agentRepository.refreshAgents()
                _uiState.value = _uiState.value.copy(
                    agentCount = agentRepository.agents.value.size,
                    isConnected = true,
                )
            } catch (e: Exception) {
                Log.w("DashboardVM", "Agent count failed", e)
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }

        viewModelScope.launch {
            try {
                allConversationsRepository.refresh()
                _uiState.value = _uiState.value.copy(
                    conversationCount = allConversationsRepository.conversations.value.size,
                )
            } catch (e: Exception) {
                Log.w("DashboardVM", "Conversation count failed", e)
            }
        }

        viewModelScope.launch {
            try {
                toolRepository.refreshTools()
                _uiState.value = _uiState.value.copy(
                    toolCount = toolRepository.getTools().value.size,
                )
            } catch (e: Exception) {
                Log.w("DashboardVM", "Tool count failed", e)
            }
        }

        viewModelScope.launch {
            try {
                val blocks = blockRepository.listAllBlocks()
                cachedBlocks = blocks
                _uiState.value = _uiState.value.copy(
                    blockCount = blocks.size,
                )
            } catch (e: Exception) {
                Log.w("DashboardVM", "Block count failed", e)
            }
        }

        viewModelScope.launch {
            try {
                val windowEnd = Instant.now()
                val windowStart = windowEnd.minus(24, ChronoUnit.HOURS)
                val recentRuns = runRepository.getRecentRuns(limit = 100)
                    .filter { run ->
                        val createdAt = run.createdAt ?: return@filter false
                        try {
                            val createdInstant = Instant.parse(createdAt)
                            !createdInstant.isBefore(windowStart) && !createdInstant.isAfter(windowEnd)
                        } catch (_: Exception) {
                            false
                        }
                    }
                val steps = recentRuns
                    .map { run ->
                        async {
                            runRepository.getRunSteps(run.id)
                        }
                    }
                    .awaitAll()
                    .flatten()
                _uiState.value = _uiState.value.copy(
                    usageSummary = DashboardUsageCalculator.calculate(steps),
                    isUsageLoading = false,
                )
            } catch (e: Exception) {
                Log.w("DashboardVM", "Usage summary failed", e)
                _uiState.value = _uiState.value.copy(isUsageLoading = false)
            }
        }
    }
}
