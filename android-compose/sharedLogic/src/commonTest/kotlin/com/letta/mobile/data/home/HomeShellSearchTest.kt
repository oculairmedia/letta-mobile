package com.letta.mobile.data.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeShellSearchTest {
    private val home = RecordingHomeActions()
    private val search = HomeShellSearch(home)

    @Test
    fun offHomeTheShellSearchesAloneAndShowsItsOwnResults() {
        search.updateQuery("scout")

        assertEquals("scout", search.state.value.query)
        assertTrue(search.state.value.showsShellResults)
        assertEquals(emptyList(), home.queries)
    }

    @Test
    fun onHomeEveryQueryChangeIsTheHomeQueryAndTheShellShowsNoResults() {
        search.setOnHome(true)
        search.updateQuery("sc")
        search.updateQuery("scout")

        assertFalse(search.state.value.showsShellResults)
        assertEquals(listOf("", "sc", "scout"), home.queries)
    }

    @Test
    fun clearingTheQueryOnHomeClearsTheHomeSearch() {
        search.setOnHome(true)
        search.updateQuery("scout")
        search.updateQuery("")

        assertEquals("", home.queries.last())
    }

    @Test
    fun dismissingOnHomeKeepsTheFilterButOffHomeDropsTheQuery() {
        search.setOnHome(true)
        search.updateQuery("scout")
        search.dismiss()
        assertEquals("scout", search.state.value.query)
        assertEquals("scout", home.queries.last())

        search.setOnHome(false)
        search.updateQuery("plan")
        search.dismiss()
        assertEquals("", search.state.value.query)
    }

    @Test
    fun leavingHomeResetsBothQueries() {
        search.setOnHome(true)
        search.updateQuery("scout")

        search.setOnHome(false)

        assertEquals(HomeShellSearchState(), search.state.value)
        assertEquals("", home.queries.last())
    }

    @Test
    fun enteringHomeDropsAStaleShellQuery() {
        search.updateQuery("plan")

        search.setOnHome(true)

        assertEquals(HomeShellSearchState(onHome = true), search.state.value)
        assertEquals(listOf(""), home.queries)
    }

    @Test
    fun openingAResultClearsTheQuery() {
        search.updateQuery("plan")
        search.resultOpened()

        assertEquals("", search.state.value.query)
    }

    @Test
    fun repeatingTheSamePageOrQueryIsANoOp() {
        search.setOnHome(true)
        search.updateQuery("scout")
        search.setOnHome(true)
        search.updateQuery("scout")

        assertEquals(listOf("", "scout"), home.queries)
    }

    /** Records the Home queries; clearSearch is recorded as the empty query, as the controller treats it. */
    private class RecordingHomeActions : HomePageActions {
        val queries = mutableListOf<String>()

        override fun refresh() = Unit

        override fun updateSearchQuery(query: String) {
            queries += query
        }

        override fun clearSearch() {
            queries += ""
        }

        override fun selectSort(key: FleetSortKey) = Unit

        override fun reorderPins(keys: List<String>) = Unit

        override fun setShortcutPinned(shortcut: HomeShortcut, pinned: Boolean) = Unit

        override fun setAgentPinned(agentId: String, pinned: Boolean) = Unit
    }
}
