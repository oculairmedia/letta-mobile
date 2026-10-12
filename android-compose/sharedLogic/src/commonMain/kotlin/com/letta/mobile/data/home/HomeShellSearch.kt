package com.letta.mobile.data.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The shell search field's query, and whether Home is the page it currently searches. */
data class HomeShellSearchState(
    val query: String = "",
    val onHome: Boolean = false,
) {
    /**
     * Whether the shell draws its own results under the field. On Home the page's results are the
     * answer, so the shell shows the field alone.
     */
    val showsShellResults: Boolean get() = !onHome
}

/**
 * A shell-level search field (desktop's header) that searches the Home view while Home is the
 * active page (letta-mobile-xuhmy). Home has its own field for hosts without a shell search; a host
 * that has one hides the page's field and routes its query here instead, so there is one field.
 *
 * - On Home, every query change is the Home page's query, and closing the field keeps it: the
 *   query is the view's filter, not a pending pick from a dropdown.
 * - Off Home, the shell runs its own search; closing the field or opening a result clears it.
 * - Entering or leaving Home resets both queries, so a filter never outlives the view it filtered.
 */
class HomeShellSearch(private val home: HomePageActions) {
    private val stateFlow = MutableStateFlow(HomeShellSearchState())
    val state: StateFlow<HomeShellSearchState> = stateFlow.asStateFlow()

    fun updateQuery(query: String) {
        val current = stateFlow.value
        if (query == current.query) return
        stateFlow.value = current.copy(query = query)
        if (current.onHome) home.updateSearchQuery(query)
    }

    /** The field closed without a pick. On Home the filter stays; elsewhere the query is dropped. */
    fun dismiss() {
        if (!stateFlow.value.onHome) clearQuery()
    }

    /** A shell result was opened; the query has done its job. */
    fun resultOpened() {
        clearQuery()
    }

    fun setOnHome(onHome: Boolean) {
        if (onHome == stateFlow.value.onHome) return
        stateFlow.update { HomeShellSearchState(onHome = onHome) }
        home.clearSearch()
    }

    private fun clearQuery() {
        updateQuery("")
    }
}
