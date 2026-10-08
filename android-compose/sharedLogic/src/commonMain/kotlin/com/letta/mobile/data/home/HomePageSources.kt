package com.letta.mobile.data.home

import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** Searches the backend's messages for Home; hosts without a message repository pass none. */
fun interface HomeMessageSearch {
    suspend fun search(query: String): List<ParsedSearchMessage>
}

/**
 * [HomePageSource] over the session graph, shared by every host: the agent, tool and (where the
 * backend has a global block store) block catalogs, the tool / block counts and the last day's token
 * usage. A graph switch (backend change) reloads everything. A local runtime has no server-side
 * tools, blocks or runs, so it reports zeros without asking.
 */
class SessionHomePageSource<Graph : SessionRepositoryGraph>(
    private val sessionGraphProvider: SessionRepositoryGraphProvider<Graph>,
    private val scope: CoroutineScope,
    private val messageSearch: HomeMessageSearch? = null,
    private val clock: Clock = Clock.System,
) : HomePageSource {
    private val catalogFlow = MutableStateFlow(HomeSearchCatalog())
    private val statsFlow = MutableStateFlow(HomeStats())
    private val blocks = MutableStateFlow<List<Block>>(emptyList())
    private var followJob: Job? = null
    private var statsJob: Job? = null

    override val catalog: StateFlow<HomeSearchCatalog> = catalogFlow.asStateFlow()
    override val stats: StateFlow<HomeStats> = statsFlow.asStateFlow()
    override val supportsMessageSearch: Boolean get() = messageSearch != null

    override fun start() {
        if (followJob != null) return
        followJob = scope.launch {
            sessionGraphProvider.currentGraph.collectLatest { graph ->
                reloadStats(graph)
                followCatalog(graph)
            }
        }
    }

    override fun refresh() {
        reloadStats(sessionGraphProvider.current)
    }

    override suspend fun searchMessages(query: String): List<ParsedSearchMessage> =
        messageSearch?.search(query).orEmpty()

    override fun close() {
        followJob?.cancel()
        statsJob?.cancel()
        followJob = null
        statsJob = null
    }

    private suspend fun followCatalog(graph: Graph) {
        val agents = graph.agentRepository
        combine(agents.agents, agents.isRefreshing, graph.toolRepository.getTools(), blocks) { agentList, refreshing, tools, blockList ->
            HomeSearchCatalog(agents = agentList, tools = tools, blocks = blockList, agentsSettled = !refreshing)
        }.collect { catalogFlow.value = it }
    }

    private fun reloadStats(graph: Graph) {
        statsJob?.cancel()
        blocks.value = emptyList()
        statsJob = scope.launch { loadStats(graph) }
    }

    /**
     * Each figure lands as soon as it is known; [HomeStats.loading] clears once all have settled. A
     * figure that fails (say a route the backend has no admin_rpc path for under iroh://) stays null,
     * so its tile drops out; [HomeStats.error] is raised only when every figure failed.
     */
    private suspend fun loadStats(graph: Graph) {
        if (graph.localRuntimeBackend != null) {
            statsFlow.value = HomeStats(loading = false, toolCount = 0, blockCount = 0, usage = HomeUsageCalculator.calculate(emptyList()))
            return
        }
        statsFlow.value = HomeStats(loading = true)
        val blockRepository = graph.blockRepository as? IBlockRepository
        val failures = coroutineScope {
            listOfNotNull(
                async { record(attempt { graph.toolRepository.countTools() }) { stats, count -> stats.copy(toolCount = count) } },
                blockRepository?.let { async { record(attempt { loadBlocks(it) }) { stats, count -> stats.copy(blockCount = count) } } },
                async { record(attempt { loadUsage(graph.runRepository) }) { stats, usage -> stats.copy(usage = usage) } },
            ).awaitAll()
        }
        val error = failures.takeIf { results -> results.all { it != null } }?.firstNotNullOfOrNull { it?.message }
        statsFlow.update { it.copy(loading = false, error = error) }
    }

    private suspend fun loadBlocks(repository: IBlockRepository): Int {
        val count = repository.countBlocks()
        blocks.value = repository.listAllBlocks()
        return count
    }

    /** Steps of every run started in the usage window, fetched a few runs at a time. */
    private suspend fun loadUsage(runs: IRunRepository): HomeUsageSummary {
        val windowEnd = clock.now()
        val window = (windowEnd - USAGE_WINDOW_HOURS.hours)..windowEnd
        val recent = runs.getRecentRuns(limit = USAGE_RUN_LIMIT)
            .filter { run -> run.createdAt?.let(::parseConversationInstant)?.let { it in window } == true }
        val steps = recent.chunked(USAGE_STEPS_CONCURRENCY).flatMap { batch ->
            coroutineScope {
                batch.map { run -> async { attempt { runs.getRunSteps(run.id) }.getOrDefault(emptyList()) } }.awaitAll()
            }
        }
        return HomeUsageCalculator.calculate(steps.flatten())
    }

    /** Applies a figure that loaded; returns the failure of one that did not. */
    private fun <T> record(result: Result<T>, apply: (HomeStats, T) -> HomeStats): Throwable? {
        result.onSuccess { value -> statsFlow.update { apply(it, value) } }
        result.onFailure { Telemetry.error(TAG, "stat.failed", it) }
        return result.exceptionOrNull()
    }

    private companion object {
        const val TAG = "SessionHomePageSource"

        /** Runs sampled for the usage summary. */
        const val USAGE_RUN_LIMIT = 100

        /** Concurrent /runs/{id}/steps requests; more starves the other screens' requests. */
        const val USAGE_STEPS_CONCURRENCY = 5
    }
}

/** [runCatching] that never swallows cancellation. */
private suspend fun <T> attempt(block: suspend () -> T): Result<T> {
    val result = runCatching { block() }
    (result.exceptionOrNull() as? CancellationException)?.let { throw it }
    return result
}

/**
 * [HomePinStore] persisted in the host's [SecureSettingsStore] as newline-separated keys. Until the
 * user changes anything the grid shows [defaults].
 */
class SettingsStoreHomePinStore(
    private val store: SecureSettingsStore,
    defaults: List<String>,
    private val storageKey: String = DEFAULT_STORAGE_KEY,
) : HomePinStore {
    private val keys = MutableStateFlow(store.getString(storageKey)?.let(::decode) ?: defaults)
    override val pinnedKeys: StateFlow<List<String>> = keys.asStateFlow()

    override fun setOrder(keys: List<String>) {
        save(keys.distinct())
    }

    override fun setPinned(key: String, pinned: Boolean) {
        val current = keys.value
        when {
            pinned && key !in current -> save(current + key)
            !pinned && key in current -> save(current - key)
        }
    }

    private fun save(next: List<String>) {
        keys.value = next
        store.putString(storageKey, next.joinToString(SEPARATOR))
    }

    private fun decode(raw: String): List<String> = raw.split(SEPARATOR).filter { it.isNotBlank() }

    companion object {
        const val DEFAULT_STORAGE_KEY = "home.pinnedItems"
        private const val SEPARATOR = "\n"
    }
}
