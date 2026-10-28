package com.letta.mobile.data.home

import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.search.MessageSearchQuery
import com.letta.mobile.data.search.searchBackend
import com.letta.mobile.data.session.HomeAggregateSession
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import com.letta.mobile.data.session.ifSupported
import com.letta.mobile.data.session.isUnsupportedHomeStatistic
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.runtime.RunId
import com.letta.mobile.util.Telemetry
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Searches the backend's messages for Home; hosts without a message repository pass none. */
fun interface HomeMessageSearch {
    suspend fun search(query: String): List<ParsedSearchMessage>

    companion object {
        /** Messages Home asks for per query. */
        const val RESULT_LIMIT = 20

        /** Home's message search over Letta's [search]: full-text, over what the user and the agents said. */
        fun backedBy(search: suspend (MessageSearchRequest) -> List<ParsedSearchMessage>): HomeMessageSearch =
            HomeMessageSearch { query -> MessageSearchQuery(text = query).searchBackend(RESULT_LIMIT, search) }
    }
}

/**
 * [HomePageSource] over the session graph, shared by every host: the agent, tool and (where the
 * backend has a global block store) block catalogs, the tool / block counts and the last day's token
 * usage. Catalog subscriptions remain live while Home is hidden; aggregates wait for page-entry
 * demand. Completed figures are reused for 60 seconds. A new connection or capability epoch
 * forgets unsupported figures; [refresh] bypasses the TTL and coalesces overlapping requests.
 * Routes outside the session capabilities are never asked for.
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
    private var statsGraph: Graph? = null
    private var aggregatesDemanded = false
    private val session = HomeAggregateSession()
    private var aggregateCache = HomeAggregateCache(clock)

    override val catalog: StateFlow<HomeSearchCatalog> = catalogFlow.asStateFlow()
    override val stats: StateFlow<HomeStats> = statsFlow.asStateFlow()
    override val supportsMessageSearch: Boolean get() = messageSearch != null

    override fun start() {
        if (followJob != null) return
        followJob = scope.launch {
            sessionGraphProvider.currentGraph.collectLatest { graph ->
                coroutineScope {
                    launch { followCatalog(graph) }
                    graph.channelTransport.state.collect { reloadStats(graph, automatic = true) }
                }
            }
        }
    }

    override fun refresh() {
        aggregatesDemanded = true
        reloadStats(sessionGraphProvider.current, force = true)
    }

    override fun loadIfStale() {
        aggregatesDemanded = true
        reloadStats(sessionGraphProvider.current)
    }

    override suspend fun searchMessages(query: String): List<ParsedSearchMessage> =
        messageSearch?.search(query).orEmpty()

    override fun close() {
        followJob?.cancel()
        statsJob?.cancel()
        followJob = null
        statsJob = null
        statsGraph = null
        aggregatesDemanded = false
    }

    /** A catalog the session does not serve is empty, never asked for. */
    private suspend fun followCatalog(graph: Graph) {
        val agents = graph.ifSupported(SessionRoute.AGENTS) { agentRepository }
        val roster = agents?.agents ?: MutableStateFlow(emptyList())
        val settling = agents?.isRefreshing ?: MutableStateFlow(false)
        val toolCatalog = graph.ifSupported(SessionRoute.TOOLS) { toolRepository.getTools() } ?: MutableStateFlow(emptyList())
        combine(roster, settling, toolCatalog, blocks) { agentList, refreshing, tools, blockList ->
            HomeSearchCatalog(agents = agentList, tools = tools, blocks = blockList, agentsSettled = !refreshing)
        }.collect { catalogFlow.value = it }
    }

    private fun reloadStats(graph: Graph, force: Boolean = false, automatic: Boolean = false) {
        if (graph !== sessionGraphProvider.current) return
        resetForNewSession(graph)
        // A replaced graph closes its repositories. Only completed results can survive that swap.
        if (statsGraph !== graph) {
            statsJob?.cancel()
            statsJob = null
            statsGraph = graph
        }
        if (!shouldLoadStats(force, automatic)) return
        val cache = aggregateCache
        statsJob = scope.launch { loadStats(graph, cache) }
    }

    private fun shouldLoadStats(force: Boolean, automatic: Boolean): Boolean {
        if (!aggregatesDemanded) return false
        if (automatic && !session.isConnected) return false
        if (statsJob?.isActive == true) return false
        return force || !aggregateCache.isFresh()
    }

    private fun resetForNewSession(graph: Graph) {
        if (!session.update(graph)) return
        statsJob?.cancel()
        statsJob = null
        aggregateCache = HomeAggregateCache(clock)
        blocks.value = emptyList()
        statsFlow.value = HomeStats()
    }

    /**
     * Each figure lands as soon as it is known; [HomeStats.loading] clears once all have settled. A
     * figure that fails (say a route the backend has no admin_rpc path for under iroh://) stays null,
     * so its tile drops out; [HomeStats.error] is raised only when every figure failed.
     */
    private suspend fun loadStats(graph: Graph, cache: HomeAggregateCache) {
        statsFlow.value = HomeStats(loading = true)
        // letta-mobile-by4vm.13: a figure the session does not serve is not asked for; its tile drops out.
        val tools = graph.ifSupported(SessionRoute.TOOLS) { toolRepository }?.takeIf { cache.supports(SessionRoute.TOOLS) }
        val blockRepository = (graph.ifSupported(SessionRoute.BLOCKS) { blockRepository } as? IBlockRepository)
            ?.takeIf { cache.supports(SessionRoute.BLOCKS) }
        val runs = graph.ifSupported(SessionRoute.RUNS) { runRepository }?.takeIf { cache.supports(SessionRoute.RUNS) }
        val failures = coroutineScope {
            listOfNotNull(
                tools?.let { repo -> async {
                    record(cache, SessionRoute.TOOLS, attempt { repo.countTools() }) { stats, count -> stats.copy(toolCount = count) }
                } },
                blockRepository?.let { repo -> async {
                    record(cache, SessionRoute.BLOCKS, attempt { loadBlocks(repo) }) { stats, count -> stats.copy(blockCount = count) }
                } },
                runs?.let { repo -> async {
                    record(cache, SessionRoute.RUNS, attempt { loadUsage(repo) }) { stats, usage -> stats.copy(usage = usage) }
                } },
            ).awaitAll()
        }
        val error = failures.takeIf { results -> results.all { it != null } }?.firstNotNullOfOrNull { it?.message }
        currentCoroutineContext().ensureActive()
        cache.complete()
        statsFlow.update { it.copy(loading = false, error = error) }
    }

    private suspend fun loadBlocks(repository: IBlockRepository): Int {
        val count = repository.countBlocks()
        currentCoroutineContext().ensureActive()
        val loaded = repository.listAllBlocks()
        currentCoroutineContext().ensureActive()
        blocks.value = loaded
        return count
    }

    /** Steps of every run started in the usage window, fetched a few runs at a time. */
    private suspend fun loadUsage(runs: IRunRepository): HomeUsageSummary {
        val windowEnd = clock.now()
        val window = (windowEnd - USAGE_WINDOW_HOURS.hours)..windowEnd
        val recent = runs.getRecentRuns(limit = USAGE_RUN_LIMIT)
            .filter { run -> run.createdAt?.let(::parseConversationInstant)?.let { it in window } == true }
        // Probe step support once before fan-out: one unsupported endpoint must not cost 100 attempts.
        currentCoroutineContext().ensureActive()
        val first = recent.firstOrNull()?.let { loadSteps(runs, RunId(it.id)) }.orEmpty()
        val steps = recent.drop(1).chunked(USAGE_STEPS_CONCURRENCY).flatMap { batch ->
            coroutineScope {
                batch.map { run -> async { loadSteps(runs, RunId(run.id)) } }.awaitAll()
            }
        }
        return HomeUsageCalculator.calculate(first + steps.flatten())
    }

    /** Transient per-run failures retain the existing partial-usage behavior; absence stops fan-out. */
    private suspend fun loadSteps(runs: IRunRepository, runId: RunId): List<Step> {
        val result = attempt { runs.getRunSteps(runId) }
        result.exceptionOrNull()?.takeIf { it.isUnsupportedHomeStatistic() }?.let { throw it }
        return result.getOrDefault(emptyList())
    }

    /** Applies a figure that loaded; returns the failure of one that did not. */
    private fun <T> record(
        cache: HomeAggregateCache,
        figure: SessionRoute,
        result: Result<T>,
        apply: (HomeStats, T) -> HomeStats,
    ): Throwable? {
        result.onSuccess { value -> statsFlow.update { apply(it, value) } }
        result.onFailure {
            cache.remember(figure, it)
            Telemetry.event(
                TAG, "stat.failed", "figure" to figure.name, "failureType" to it::class.simpleName,
                level = Telemetry.Level.ERROR,
            )
        }
        return result.exceptionOrNull()
    }
}

private const val TAG = "SessionHomePageSource"
private const val USAGE_RUN_LIMIT = 100
// More concurrent step reads would starve requests from other screens.
private const val USAGE_STEPS_CONCURRENCY = 5

/** [runCatching] that never swallows cancellation. */
private suspend fun <T> attempt(block: suspend () -> T): Result<T> {
    val result = runCatching { block() }
    (result.exceptionOrNull() as? CancellationException)?.let { throw it }
    currentCoroutineContext().ensureActive()
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
