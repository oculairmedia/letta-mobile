package com.letta.mobile.ui.shell.header

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.home.FleetSortKey
import com.letta.mobile.data.home.HomePageActions
import com.letta.mobile.data.home.HomeShellSearch
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.ui.search.LettaSearchConfig
import com.letta.mobile.ui.search.LettaSearchOpenRequest
import com.letta.mobile.ui.search.LettaSearchRow
import com.letta.mobile.ui.search.LettaSearchSection
import com.letta.mobile.ui.search.LettaSearchToggle
import com.letta.mobile.ui.shell.pages.home.HomeSearchPlaceholder

/**
 * The header's one search (hard cut P3): the live [query], where typing goes ([onQuery]), and what
 * the pane under the field shows. [config] decides whether that pane has results at all: on Home it
 * does not, because the page itself is the result surface.
 */
@Immutable
class SearchSlot(
    val query: String,
    val onQuery: (String) -> Unit,
    val config: LettaSearchConfig,
    val sections: List<LettaSearchSection> = emptyList(),
    val onRowSelected: (LettaSearchRow) -> Unit = {},
    val onDismiss: () -> Unit = {},
    /** Opens the pane from outside the field (Android chat's `/search` command); null: only the field opens it. */
    val openRequest: LettaSearchOpenRequest? = null,
)

/**
 * The header search of a page that filters itself (a block library, a model list, an archive): each
 * keystroke narrows the page ([onQuery]) and nothing opens over it. A phone header becomes the search
 * bar in place, and leaving the bar clears the filter (letta-mobile-ckmub.3.25).
 */
fun pageFilterSearchSlot(query: String, onQuery: (String) -> Unit, placeholder: String): SearchSlot =
    SearchSlot(query = query, onQuery = onQuery, config = LettaSearchConfig(placeholder = placeholder, autoFocus = false, showResults = false))

/** Where the shell search is used: on Home (it searches the page) or inside a chat (the agent filter starts on). */
@Immutable
data class ShellSearchContext(val onHome: Boolean = false, val inChat: Boolean = false)

/**
 * The unified search's own filters: the scope tab and "Filter to this agent". The filter follows the
 * page until the person sets it (letta-mobile-ckmub.3.9): on inside a chat, where "this agent" is the
 * one being talked to, and off anywhere else, where it would hide every other agent's conversations.
 */
@Stable
class ShellSearchFilters {
    var scopeId: String by mutableStateOf(UnifiedShellSearch.ALL)
    private var chosenFilterToAgent: Boolean? by mutableStateOf(null)

    fun filterToAgent(context: ShellSearchContext): Boolean = chosenFilterToAgent ?: context.inChat

    fun setFilterToAgent(value: Boolean) {
        chosenFilterToAgent = value
    }
}

/**
 * The shell's search slot, the same on every platform (re-hosts desktop's header bridge,
 * letta-mobile-xuhmy). While [ShellSearchContext.onHome] the field searches the Home view in place:
 * each keystroke is [home]'s query and the pane shows the field alone. Anywhere else it is the
 * unified search over [sources] (titles, names, canvases and, through [UnifiedSearchSources.messageSearch],
 * message text), with scopes and the agent filter, and a picked row goes to [onOpen].
 *
 * [home] null means the host has no Home page to search; the slot is then always the unified search.
 */
@Composable
fun rememberShellSearchSlot(
    home: HomePageActions?,
    context: ShellSearchContext,
    sources: UnifiedSearchSources,
    onOpen: (UnifiedSearchHit) -> Unit,
): SearchSlot {
    val filters = remember { ShellSearchFilters() }
    val search = remember(home) { HomeShellSearch(home ?: NoHomePage) }
    val searchesHome = context.onHome && home != null
    LaunchedEffect(search, searchesHome) { search.setOnHome(searchesHome) }
    val state by search.state.collectAsState()
    val filterToAgent = filters.filterToAgent(context)
    // On Home the page answers the query: nothing to search here (and no message search to run).
    val query = UnifiedSearchQuery(state.query.takeIf { state.showsShellResults }.orEmpty(), filterToAgent, filters.scopeId)
    val sections = rememberUnifiedSearchSections(query, sources)
    return SearchSlot(
        query = state.query,
        onQuery = search::updateQuery,
        config = if (state.showsShellResults) unifiedSearchConfig(filters, filterToAgent) else HomeSearchConfig,
        sections = if (state.showsShellResults) sections else emptyList(),
        onRowSelected = { row ->
            search.resultOpened()
            UnifiedShellSearch.hitOf(row)?.let(onOpen)
        },
        onDismiss = search::dismiss,
    )
}

private fun unifiedSearchConfig(filters: ShellSearchFilters, filterToAgent: Boolean) = LettaSearchConfig(
    placeholder = "Search agents, conversations, messages, canvases",
    // The header field is always present; stealing focus on every recomposition would fight the
    // composer for the caret. The pane's own field still focuses when it opens.
    autoFocus = false,
    scopes = UnifiedShellSearch.scopes,
    selectedScopeId = filters.scopeId,
    onScopeSelected = { filters.scopeId = it },
    toggle = LettaSearchToggle(
        label = "Filter to this agent",
        checked = filterToAgent,
        onCheckedChange = filters::setFilterToAgent,
    ),
)

/** On Home the field searches the page: Home's own wording, no scopes, filter or rows. */
private val HomeSearchConfig = LettaSearchConfig(
    placeholder = HomeSearchPlaceholder,
    autoFocus = false,
    showResults = false,
)

/** The Home target of a host that has no Home page: never searched, since the slot is never "on Home". */
private object NoHomePage : HomePageActions {
    override fun refresh() = Unit

    override fun updateSearchQuery(query: String) = Unit

    override fun clearSearch() = Unit

    override fun selectSort(key: FleetSortKey) = Unit

    override fun reorderPins(keys: List<String>) = Unit

    override fun setShortcutPinned(shortcut: HomeShortcut, pinned: Boolean) = Unit

    override fun setAgentPinned(agentId: String, pinned: Boolean) = Unit
}
