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
    val isAgentCountLoading: Boolean = true,
    val conversationCount: Int? = null,
    val isConversationCountLoading: Boolean = true,
    val toolCount: Int? = null,
    val isToolCountLoading: Boolean = true,
    val blockCount: Int? = null,
    val isBlockCountLoading: Boolean = true,
    val usageSummary: DashboardUsageSummary? = null,
    val isUsageLoading: Boolean = true,
    val favoriteAgentId: String? = null,
    val favoriteAgentName: String? = null,
    val pinnedAgents: ImmutableList<PinnedAgent> = persistentListOf(),
    val isPinnedAgentsLoading: Boolean = true,
    val pinnedShortcuts: ImmutableList<DashboardShortcut> = persistentListOf(),
    val isPinnedShortcutsLoading: Boolean = true,
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
    private data class SearchSnapshot(
        val query: String,
        val agents: List<Agent>,
        val tools: List<Tool>,
        val blocks: List<Block>,
    )

    private val _searchQuery = MutableStateFlow("")
    private val _cachedBlocks = MutableStateFlow<List<Block>>(emptyList())
    private var messageSearchJob: kotlinx.coroutines.Job? = null

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
        observePinnedShortcuts()
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
                        isPinnedAgentsLoading = false,
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
        Log.d("DashboardVM", "updateSearchQuery: '$query'")
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
            combine(
                _searchQuery,
                agentRepository.agents,
                toolRepository.getTools(),
                _cachedBlocks,
            ) { query, agents, tools, blocks ->
                SearchSnapshot(query = query, agents = agents, tools = tools, blocks = blocks)
            }
                .debounce(300L)
                .distinctUntilChanged()
                .collect { snapshot ->
                    // Cancel any in-flight message search from the previous query.
                    messageSearchJob?.cancel()

                    if (snapshot.query.isBlank()) {
                        _uiState.value = _uiState.value.copy(
                            agentResults = persistentListOf(),
                            messageResults = persistentListOf(),
                            toolResults = persistentListOf(),
                            blockResults = persistentListOf(),
                            isSearching = false,
                        )
                        return@collect
                    }

                    // Local filtering is instant — apply immediately.
                    val q = snapshot.query.trim().lowercase()
                    val agents = snapshot.agents.filter { agent ->
                        agent.name.lowercase().contains(q) ||
                            (agent.description?.lowercase()?.contains(q) == true)
                    }
                    val tools = snapshot.tools.filter { tool ->
                        tool.name.lowercase().contains(q) ||
                            (tool.description?.lowercase()?.contains(q) == true)
                    }
                    val blocks = snapshot.blocks.filter { block ->
                        (block.label?.lowercase()?.contains(q) == true) ||
                            (block.description?.lowercase()?.contains(q) == true) ||
                            block.value.lowercase().contains(q)
                    }
                    Log.d(
                        "DashboardVM",
                        "Search '$q': ${agents.size} agents, ${tools.size} tools, ${blocks.size} blocks " +
                            "(from ${snapshot.agents.size} agents, ${snapshot.tools.size} tools, ${snapshot.blocks.size} blocks)",
                    )
                    _uiState.value = _uiState.value.copy(
                        agentResults = agents.toImmutableList(),
                        toolResults = tools.toImmutableList(),
                        blockResults = blocks.toImmutableList(),
                        messageResults = persistentListOf(),
                        isSearching = true,
                    )

                    // Remote message search runs in a separate job so it doesn't
                    // block the collect loop from processing the next query.
                    messageSearchJob = viewModelScope.launch {
                        try {
                            val results = messageRepository.searchMessages(
                                MessageSearchRequest(
                                    query = snapshot.query,
                                    searchMode = "fts",
                                    roles = listOf("user", "assistant"),
                                    limit = 20,
                                )
                            )
                            _uiState.value = _uiState.value.copy(
                                messageResults = results.map { it.toParsed() }.toImmutableList(),
                                isSearching = false,
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("DashboardVM", "Message search failed", e)
                            _uiState.value = _uiState.value.copy(isSearching = false)
                        }
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

    private fun observePinnedShortcuts() {
        viewModelScope.launch {
            settingsRepository.getPinnedShortcutOrder().collect { names ->
                val shortcuts = names.mapNotNull { name ->
                    try {
                        DashboardShortcut.valueOf(name)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
                _uiState.value = _uiState.value.copy(
                    pinnedShortcuts = shortcuts.toImmutableList(),
                    isPinnedShortcutsLoading = false,
                )
            }
        }
    }

    fun pinShortcut(shortcut: DashboardShortcut) {
        viewModelScope.launch {
            settingsRepository.addPinnedShortcut(shortcut.name)
        }
    }

    fun unpinShortcut(shortcut: DashboardShortcut) {
        viewModelScope.launch {
            settingsRepository.removePinnedShortcut(shortcut.name)
        }
    }

    fun reorderShortcuts(newOrder: List<DashboardShortcut>) {
        viewModelScope.launch {
            settingsRepository.setPinnedShortcutOrder(newOrder.map { it.name })
        }
    }

    fun loadProgressively() {
        viewModelScope.launch {
            try {
                val count = agentRepository.countAgents()
                _uiState.value = _uiState.value.copy(
                    agentCount = count,
                    isAgentCountLoading = false,
                    isConnected = true,
                )
                agentRepository.refreshAgents()
            } catch (e: Exception) {
                Log.w("DashboardVM", "Agent count failed", e)
                _uiState.value = _uiState.value.copy(
                    isAgentCountLoading = false,
                    error = e.message,
                )
            }
        }

        viewModelScope.launch {
            try {
                val count = allConversationsRepository.countConversations()
                _uiState.value = _uiState.value.copy(
                    conversationCount = count,
                    isConversationCountLoading = false,
                )
                allConversationsRepository.refresh()
            } catch (e: Exception) {
                Log.w("DashboardVM", "Conversation count failed", e)
                _uiState.value = _uiState.value.copy(isConversationCountLoading = false)
            }
        }

        viewModelScope.launch {
            try {
                val count = toolRepository.countTools()
                _uiState.value = _uiState.value.copy(
                    toolCount = count,
                    isToolCountLoading = false,
                )
                toolRepository.refreshTools()
            } catch (e: Exception) {
                Log.w("DashboardVM", "Tool count failed", e)
                _uiState.value = _uiState.value.copy(isToolCountLoading = false)
            }
        }

        viewModelScope.launch {
            try {
                val count = blockRepository.countBlocks()
                _uiState.value = _uiState.value.copy(
                    blockCount = count,
                    isBlockCountLoading = false,
                )
                // Also load blocks for search functionality
                val blocks = blockRepository.listAllBlocks()
                _cachedBlocks.value = blocks
            } catch (e: Exception) {
                Log.w("DashboardVM", "Block count failed", e)
                _uiState.value = _uiState.value.copy(isBlockCountLoading = false)
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
                // Throttle to N-wide parallelism so we don't saturate the HTTP
                // client with 100 GET /runs/*/steps requests — that blocks other
                // screens (chat hydrate, conversation list) from getting through.
                val steps = recentRuns
                    .chunked(DASHBOARD_STEPS_CONCURRENCY)
                    .flatMap { batch ->
                        batch.map { run -> async { runRepository.getRunSteps(run.id) } }
                            .awaitAll()
                    }
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

    companion object {
        /**
         * Max concurrent /runs/{id}/steps requests when building the 24h usage
         * summary. Higher = faster summary, but blocks other screens' HTTP
         * requests (chat hydrate, conversations refresh) behind ktor's
         * connection pool. 5 keeps the summary reasonably fast without
         * starving everything else.
         */
        private const val DASHBOARD_STEPS_CONCURRENCY = 5
    }
}
