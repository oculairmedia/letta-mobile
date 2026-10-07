package com.letta.mobile.data.home

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.ParsedSearchMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * The port the shared Home page reads backend data from: the searchable catalog, the backend-wide
 * stat widgets and (optionally) the remote message search. [SessionHomePageSource] is the
 * session-graph implementation; hosts add only what their graph cannot reach.
 */
interface HomePageSource : AutoCloseable {
    val catalog: StateFlow<HomeSearchCatalog>
    val stats: StateFlow<HomeStats>

    /** False when this host has no message search; the page then searches its catalog only. */
    val supportsMessageSearch: Boolean get() = false

    fun start()

    fun refresh()

    suspend fun searchMessages(query: String): List<ParsedSearchMessage> = emptyList()
}

/** Where the pinned grid's order lives: qualified [HomePinnedItem] keys, first tile first. */
interface HomePinStore {
    val pinnedKeys: StateFlow<List<String>>

    /**
     * Last-known names of pinned agents, so their tiles render at once after a backend switch while
     * the new agent list loads. Stores without such a cache keep the default (none).
     */
    val persistedAgentNames: StateFlow<Map<String, String>> get() = NoPersistedNames

    fun setOrder(keys: List<String>)

    fun setPinned(key: String, pinned: Boolean)
}

/** The default [HomePinStore.persistedAgentNames]: no cache. */
private val NoPersistedNames: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap<String, String>()).asStateFlow()

/** What the shared Home page can ask of its controller. Navigation stays with the host. */
interface HomePageActions {
    fun refresh()

    fun updateSearchQuery(query: String)

    fun clearSearch()

    fun selectSort(key: FleetSortKey)

    /** [keys] is the grid's full order after a drag. */
    fun reorderPins(keys: List<String>)

    fun setShortcutPinned(shortcut: HomeShortcut, pinned: Boolean)

    fun setAgentPinned(agentId: String, pinned: Boolean)
}

/** Platform differences that change the page's data, not just its look. */
@Immutable
data class HomePageConfig(
    /** Shortcuts this host can open; pins outside the set stay stored but are not drawn. */
    val availableShortcuts: Set<HomeShortcut> = HomeShortcut.entries.toSet(),
)

/**
 * Everything the Home page draws: the host-fed [fleet] and [favorite], the backend [stats] and
 * [catalog], the pins and the search. [pinnedItems] and [sortedAgents] are derived by
 * [HomePageReducer] on every change of their inputs.
 */
@Immutable
data class HomePageState(
    val fleet: FleetOverview = FleetOverview(),
    val sort: FleetSort = FleetSort(),
    val stats: HomeStats = HomeStats(),
    val catalog: HomeSearchCatalog = HomeSearchCatalog(),
    val pinKeys: List<String> = emptyList(),
    val pinsLoaded: Boolean = false,
    val persistedAgentNames: Map<String, String> = emptyMap(),
    val availableShortcuts: Set<HomeShortcut> = HomeShortcut.entries.toSet(),
    val favorite: HomeAgentRef? = null,
    val search: HomeSearchState = HomeSearchState(),
    val pinnedItems: List<HomePinnedItem> = emptyList(),
    val sortedAgents: List<FleetAgentStat> = emptyList(),
) {
    /** Shortcuts the user could still pin, in their natural order. */
    val unpinnedShortcuts: List<HomeShortcut>
        get() = HomeShortcut.entries.filter { it in availableShortcuts && HomePinnedItem.shortcutKey(it) !in pinKeys }

    fun isAgentPinned(agentId: String): Boolean = HomePinnedItem.agentKey(agentId) in pinKeys

    /** The figure a shortcut tile shows under its icon: a live count where one exists. */
    fun shortcutInfo(shortcut: HomeShortcut): String? = when (shortcut) {
        HomeShortcut.CONVERSATIONS -> fleet.summary.conversationsLabel
        HomeShortcut.AGENTS -> fleet.summary.agentsLabel
        HomeShortcut.TOOLS -> stats.toolCount?.let(::formatGroupedNumber) ?: loadingDash()
        HomeShortcut.BLOCKS -> stats.blockCount?.let(::formatGroupedNumber) ?: loadingDash()
        HomeShortcut.USAGE -> stats.usage?.let { "${formatGroupedNumber(it.totalTokens)} tokens" }
            ?: loadingDash() ?: shortcut.description
        HomeShortcut.FAVORITE_AGENT -> favorite?.name ?: shortcut.description
        else -> shortcut.description
    }

    private fun loadingDash(): String? = LOADING_DASH.takeIf { stats.loading }

    private companion object {
        const val LOADING_DASH = "—"
    }
}

/** Pure transitions of [HomePageState]. */
object HomePageReducer {
    fun withFleet(state: HomePageState, fleet: FleetOverview): HomePageState = derive(state.copy(fleet = fleet))

    fun withSort(state: HomePageState, key: FleetSortKey): HomePageState = derive(state.copy(sort = state.sort.toggled(key)))

    fun withStats(state: HomePageState, stats: HomeStats): HomePageState = state.copy(stats = stats)

    fun withFavorite(state: HomePageState, favorite: HomeAgentRef?): HomePageState = state.copy(favorite = favorite)

    fun withPins(state: HomePageState, keys: List<String>): HomePageState =
        derive(state.copy(pinKeys = keys, pinsLoaded = true))

    fun withPersistedAgentNames(state: HomePageState, names: Map<String, String>): HomePageState =
        derive(state.copy(persistedAgentNames = names))

    /** A new catalog re-runs the local search; message hits for the same query are kept. */
    fun withCatalog(state: HomePageState, catalog: HomeSearchCatalog): HomePageState {
        val local = searchHomeCatalog(catalog, state.search.query)
        val search = local.copy(messages = state.search.messages, searchingMessages = state.search.searchingMessages)
        return derive(state.copy(catalog = catalog, search = search))
    }

    /** A new query shows its local matches at once; [awaitMessages] marks the remote search pending. */
    fun withQuery(state: HomePageState, query: String, awaitMessages: Boolean): HomePageState {
        if (query == state.search.query) return state
        val local = searchHomeCatalog(state.catalog, query)
        return state.copy(search = local.copy(searchingMessages = awaitMessages && local.isActive))
    }

    /** Message hits for a query the user has since changed are dropped. */
    fun withMessages(state: HomePageState, query: String, messages: List<ParsedSearchMessage>): HomePageState =
        if (query != state.search.query) state else state.copy(search = state.search.copy(messages = messages, searchingMessages = false))

    private fun derive(state: HomePageState): HomePageState {
        val names = state.fleet.agents.associate { it.agentId to it.name } + state.catalog.agents.associate { it.id.value to it.name }
        return state.copy(
            pinnedItems = resolvePinnedItems(
                keys = state.pinKeys,
                names = PinnedAgentNames(live = names, settled = state.catalog.agentsSettled, persisted = state.persistedAgentNames),
                availableShortcuts = state.availableShortcuts,
            ),
            sortedAgents = sortFleet(state.fleet.agents, state.sort),
        )
    }
}

/**
 * Platform-neutral controller behind the shared Home page (desktop and Android): follows the
 * [source] and [pins], holds the search and fleet sort, and takes the host-derived fleet and
 * favorite agent through [updateFleet] and [updateFavorite].
 */
class HomePageController(
    private val source: HomePageSource,
    private val pins: HomePinStore,
    private val scope: CoroutineScope,
    config: HomePageConfig = HomePageConfig(),
) : HomePageActions, AutoCloseable {
    private val stateFlow = MutableStateFlow(HomePageState(availableShortcuts = config.availableShortcuts))
    val state: StateFlow<HomePageState> = stateFlow.asStateFlow()

    private var followJobs: List<Job> = emptyList()
    private var messageSearchJob: Job? = null

    fun start() {
        source.start()
        if (followJobs.isNotEmpty()) return
        followJobs = listOf(
            scope.launch { source.catalog.collect { catalog -> stateFlow.update { HomePageReducer.withCatalog(it, catalog) } } },
            scope.launch { source.stats.collect { stats -> stateFlow.update { HomePageReducer.withStats(it, stats) } } },
            scope.launch { pins.pinnedKeys.collect { keys -> stateFlow.update { HomePageReducer.withPins(it, keys) } } },
            scope.launch { pins.persistedAgentNames.collect { names -> stateFlow.update { HomePageReducer.withPersistedAgentNames(it, names) } } },
        )
    }

    fun updateFleet(fleet: FleetOverview) {
        stateFlow.update { HomePageReducer.withFleet(it, fleet) }
    }

    fun updateFavorite(favorite: HomeAgentRef?) {
        stateFlow.update { HomePageReducer.withFavorite(it, favorite) }
    }

    override fun refresh() {
        source.refresh()
    }

    override fun updateSearchQuery(query: String) {
        val awaitMessages = source.supportsMessageSearch
        stateFlow.update { HomePageReducer.withQuery(it, query, awaitMessages) }
        messageSearchJob?.cancel()
        messageSearchJob = if (awaitMessages && query.isNotBlank()) launchMessageSearch(query) else null
    }

    override fun clearSearch() {
        updateSearchQuery("")
    }

    override fun selectSort(key: FleetSortKey) {
        stateFlow.update { HomePageReducer.withSort(it, key) }
    }

    override fun reorderPins(keys: List<String>) {
        pins.setOrder(keys)
    }

    override fun setShortcutPinned(shortcut: HomeShortcut, pinned: Boolean) {
        pins.setPinned(HomePinnedItem.shortcutKey(shortcut), pinned)
    }

    override fun setAgentPinned(agentId: String, pinned: Boolean) {
        pins.setPinned(HomePinnedItem.agentKey(agentId), pinned)
    }

    override fun close() {
        followJobs.forEach { it.cancel() }
        followJobs = emptyList()
        messageSearchJob?.cancel()
        source.close()
    }

    /** Debounced so typing does not fire a request per keystroke; a newer query cancels this one. */
    private fun launchMessageSearch(query: String): Job = scope.launch {
        delay(MESSAGE_SEARCH_DEBOUNCE_MS.milliseconds)
        // A failed search shows no message hits rather than an error: local matches are still useful.
        val result = runCatching { source.searchMessages(query) }
        (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        stateFlow.update { HomePageReducer.withMessages(it, query, result.getOrDefault(emptyList())) }
    }

    private companion object {
        const val MESSAGE_SEARCH_DEBOUNCE_MS = 180L
    }
}
