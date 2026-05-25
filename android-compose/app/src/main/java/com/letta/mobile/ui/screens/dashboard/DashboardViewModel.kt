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
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.repository.api.IMessageRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.util.Telemetry
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PinnedAgent(val id: String, val name: String)

/**
 * Unified pinned-item type so shortcuts and pinned agents can share a
 * single drag-to-reorder grid on the Home/Admin tab. Each value carries
 * a stable qualified key used by both the LazyGrid item key and the
 * persistence layer (see [com.letta.mobile.data.repository.SettingsRepository]).
 */
sealed interface PinnedItem {
    val key: String

    data class Shortcut(val value: DashboardShortcut) : PinnedItem {
        override val key: String get() = shortcutKey(value.name)
    }

    data class Agent(val value: PinnedAgent) : PinnedItem {
        override val key: String get() = agentKey(value.id)
    }

    companion object {
        fun shortcutKey(name: String): String = "shortcut:$name"
        fun agentKey(id: String): String = "agent:$id"
        fun parseShortcutKey(key: String): String? =
            if (key.startsWith("shortcut:")) key.removePrefix("shortcut:") else null
        fun parseAgentKey(key: String): String? =
            if (key.startsWith("agent:")) key.removePrefix("agent:") else null
    }
}

@androidx.compose.runtime.Immutable
data class DashboardUiState(
    val serverUrl: String = "",
    val isConnected: Boolean = false,
    val agentCount: Int? = null,
    val isAgentCountLoading: Boolean = true,
    val conversationCount: Int? = null,
    val isConversationCountApproximate: Boolean = false,
    val isConversationCountLoading: Boolean = true,
    val toolCount: Int? = null,
    val isToolCountLoading: Boolean = true,
    val blockCount: Int? = null,
    val isBlockCountLoading: Boolean = true,
    val usageSummary: DashboardUsageSummary? = null,
    val isUsageLoading: Boolean = true,
    val favoriteAgentId: String? = null,
    val favoriteAgentName: String? = null,
    val pinnedItems: ImmutableList<PinnedItem> = persistentListOf(),
    val isPinnedItemsLoading: Boolean = true,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val isSearchActive: Boolean = false,
    val agentResults: ImmutableList<Agent> = persistentListOf(),
    val messageResults: ImmutableList<ParsedSearchMessage> = persistentListOf(),
    val toolResults: ImmutableList<Tool> = persistentListOf(),
    val blockResults: ImmutableList<Block> = persistentListOf(),
    val error: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val agentRepository: IAgentRepository,
    private val allConversationsRepository: IAllConversationsRepository,
    private val toolRepository: IToolRepository,
    private val blockRepository: IBlockRepository,
    private val settingsRepository: ISettingsRepository,
    private val messageRepository: IMessageRepository,
    private val runRepository: IRunRepository,
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
        setupSearch()
        // letta-mobile-ze5l: refetch dashboard data on backend switch.
        viewModelScope.launch {
            settingsRepository.activeConfigChanges.collect {
                _uiState.value = _uiState.value.copy(serverUrl = settingsRepository.activeConfig.value?.serverUrl.orEmpty())
                loadProgressively()
            }
        }
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
            // Drive the unified pinned grid off five sources:
            //
            //  - favoriteAgentId       : separate "favorite" tile (not in the grid).
            //  - pinnedItemsOrder      : authoritative qualified-key order.
            //  - agentRepository.agents: current backend's agent cache.
            //  - pinnedAgentNames      : persisted id → name cache; lets us
            //                            render tiles instantly on backend
            //                            switch without waiting for the
            //                            network refresh to settle.
            //  - isRefreshing          : gate orphan removal on a known-fresh
            //                            cache; while a refresh is in flight
            //                            we keep showing cached tiles so the
            //                            grid doesn't flash empty.
            combine(
                settingsRepository.favoriteAgentId,
                settingsRepository.getPinnedItemsOrder(),
                agentRepository.agents,
                settingsRepository.getPinnedAgentNames(),
                agentRepository.isRefreshing,
            ) { favId, items, agents, names, refreshing ->
                ResolutionSnapshot(favId, items, agents, names, refreshing)
            }
                .collect { snapshot ->
                    val favId = snapshot.favId
                    val favName = favId?.let { agentRepository.getCachedAgent(it)?.name }

                    // Write-through: any pinned agent currently in the
                    // active backend's cache has its name persisted so
                    // future backend switches can display it instantly.
                    syncPinnedAgentNamesIntoCache(snapshot.items, snapshot.agents, snapshot.names)

                    val currentAgentIds = snapshot.agents.map { it.id.value }.toSet()
                    val cacheIsAuthoritative = !snapshot.refreshing

                    val items = snapshot.items.mapNotNull { key ->
                        PinnedItem.parseShortcutKey(key)?.let { name ->
                            runCatching { PinnedItem.Shortcut(DashboardShortcut.valueOf(name)) }
                                .getOrNull()
                        }
                            ?: PinnedItem.parseAgentKey(key)?.let { id ->
                                resolveAgentItem(
                                    id = id,
                                    agentNames = snapshot.names,
                                    currentAgentIds = currentAgentIds,
                                    cacheIsAuthoritative = cacheIsAuthoritative,
                                )
                            }
                    }
                    _uiState.value = _uiState.value.copy(
                        favoriteAgentId = favId,
                        favoriteAgentName = favName,
                        pinnedItems = items.toImmutableList(),
                        isPinnedItemsLoading = false,
                    )
                    if (favId != null && favName == null) {
                        try {
                            val fetched = agentRepository.getAgent(favId).first()
                            _uiState.value = _uiState.value.copy(favoriteAgentName = fetched.name)
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            Telemetry.error("DashboardVM", "failed to fetch agent name for $favId", e)
                        }
                    }
                }
        }
    }

    private data class ResolutionSnapshot(
        val favId: String?,
        val items: List<String>,
        val agents: List<com.letta.mobile.data.model.Agent>,
        val names: Map<String, String>,
        val refreshing: Boolean,
    )

    /**
     * Resolve a pinned agent key into a [PinnedItem.Agent].
     *
     *  - If the agent is in the active backend's cache: use the live
     *    name (most up-to-date).
     *  - Else if the cache is still refreshing AND we have a persisted
     *    name: render from the persisted cache so the tile stays visible
     *    during the backend-switch window.
     *  - Else: drop. The agent isn't on this backend (orphan from a
     *    different server); storage keeps the key so switching back
     *    restores the tile.
     */
    private fun resolveAgentItem(
        id: String,
        agentNames: Map<String, String>,
        currentAgentIds: Set<String>,
        cacheIsAuthoritative: Boolean,
    ): PinnedItem.Agent? {
        val cached = agentRepository.getCachedAgent(id)
        if (cached != null) {
            return PinnedItem.Agent(PinnedAgent(cached.id.value, cached.name))
        }
        if (cacheIsAuthoritative) {
            // The agent cache is settled and this id isn't in it →
            // orphan on the active backend. Drop from the visible grid
            // but leave storage and the persisted name cache alone so
            // switching back to the original backend restores the tile.
            return null
        }
        val persistedName = agentNames[id] ?: return null
        return PinnedItem.Agent(PinnedAgent(id, persistedName))
    }

    private fun syncPinnedAgentNamesIntoCache(
        itemKeys: List<String>,
        agents: List<com.letta.mobile.data.model.Agent>,
        currentNames: Map<String, String>,
    ) {
        val pinnedIds = itemKeys.mapNotNull(PinnedItem::parseAgentKey).toSet()
        if (pinnedIds.isEmpty()) return
        val agentMap = agents.associate { it.id.value to it.name }
        viewModelScope.launch {
            for (id in pinnedIds) {
                val liveName = agentMap[id] ?: continue
                if (currentNames[id] != liveName) {
                    settingsRepository.upsertPinnedAgentName(id, liveName)
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
                        delay(REMOTE_MESSAGE_SEARCH_DEBOUNCE_MS)
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

    /**
     * Persist the new display order from the unified pinned grid.
     * `keys` is the post-drag list of qualified PinnedItem keys
     * ("shortcut:NAME" / "agent:ID").
     */
    fun reorderPinnedItems(keys: List<String>) {
        viewModelScope.launch {
            settingsRepository.setPinnedItemsOrder(keys)
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
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
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
                // Avoid the old startup path that fetched up to 10,000
                // conversations solely to compute an exact count. The
                // conversations widget only needs a lightweight dashboard hint,
                // so refresh the first page once and display it as an exact
                // count when there are no more pages, or as a lower bound
                // (for example, "50+") when pagination says more exist.
                allConversationsRepository.refresh()
                val countEstimate = allConversationsRepository.loadedCountEstimate()
                _uiState.value = _uiState.value.copy(
                    conversationCount = countEstimate?.count,
                    isConversationCountApproximate = countEstimate?.isApproximate == true,
                    isConversationCountLoading = false,
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
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
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
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
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
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
                //
                // letta-mobile-6mqg: catch per-run so a single failing /steps
                // call (e.g. a stale run id 404 after backend switch, or the
                // 'run not in background mode' reject from earlier Letta
                // servers) doesn't propagate through the structured-
                // concurrency channel and crash the app on Main.immediate.
                // Each failure contributes an empty step list to the
                // summary instead of killing the whole batch.
                val steps = recentRuns
                    .chunked(DASHBOARD_STEPS_CONCURRENCY)
                    .flatMap { batch ->
                        batch.map { run ->
                            async {
                                runCatching { runRepository.getRunSteps(run.id) }
                                    .getOrElse { error ->
                                        Log.w("DashboardVM", "getRunSteps(${run.id}) failed", error)
                                        emptyList()
                                    }
                            }
                        }.awaitAll()
                    }
                    .flatten()
                _uiState.value = _uiState.value.copy(
                    usageSummary = DashboardUsageCalculator.calculate(steps),
                    isUsageLoading = false,
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
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
        private const val REMOTE_MESSAGE_SEARCH_DEBOUNCE_MS = 180L
    }
}
