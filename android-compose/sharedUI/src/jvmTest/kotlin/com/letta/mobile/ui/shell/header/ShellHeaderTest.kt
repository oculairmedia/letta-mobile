@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.header

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.home.FleetSortKey
import com.letta.mobile.data.home.HomePageActions
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaPaneTags
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.search.SearchFieldTestTag
import com.letta.mobile.ui.search.SearchTriggerTestTag
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared shell header (letta-mobile-ckmub.3.5): one search slot, which on Home searches the Home
 * view in place (the query goes to [HomePageActions], no results pane) and elsewhere opens the unified
 * search with its scopes, agent filter and results.
 */
class ShellHeaderTest {
    private val home = RecordingHome()
    private val opened = mutableListOf<UnifiedSearchHit>()
    private val sources = UnifiedSearchSources(agents = listOf(Agent(id = AgentId("a-1"), name = "Planner")))

    private fun ComposeUiTest.header(onHome: Boolean) {
        setContent {
            MaterialTheme {
                ProvideLettaHost(LettaHost.Pointer) {
                    ShellHeader(
                        title = HeaderTitle.Text("Letta"),
                        controls = HeaderControls(nav = HeaderNav.Back(onClick = {})),
                        search = rememberShellSearchSlot(home, onHome, sources) { opened += it },
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.typeInSearch(text: String) {
        onNodeWithTag(SearchTriggerTestTag).performClick()
        waitForIdle()
        onNodeWithTag(SearchFieldTestTag).performTextInput(text)
        waitForIdle()
    }

    @Test
    fun theHeaderHostsExactlyOneSearch() = runComposeUiTest {
        header(onHome = false)
        onAllNodesWithTag(ShellHeaderTags.SEARCH).assertCountEquals(1)
        onAllNodesWithTag(SearchTriggerTestTag).assertCountEquals(1)
        onNodeWithTag(ShellHeaderTags.TITLE).assertExists()
        onNodeWithTag(ShellHeaderTags.NAV).assertExists()
    }

    @Test
    fun onHomeTheSearchFiltersTheHomeViewInPlace() = runComposeUiTest {
        header(onHome = true)
        typeInSearch("plan")
        assertEquals("plan", home.queries.last())
        onNodeWithText("Filter to this agent").assertDoesNotExist()
        onNodeWithText("Planner").assertDoesNotExist()
        assertTrue(opened.isEmpty())
    }

    @Test
    fun offHomeTheSearchShowsTheUnifiedResultsPane() = runComposeUiTest {
        header(onHome = false)
        typeInSearch("plan")
        assertTrue(home.queries.isEmpty(), "off Home the Home page's query is untouched")
        onNodeWithText("Filter to this agent").assertExists()
        onNodeWithText("Planner").performClick()
        waitForIdle()
        assertEquals(listOf(UnifiedSearchHit(UnifiedSearchKind.Agents, "a-1")), opened)
    }

    /** letta-mobile-06whj: the results pane takes the header field's place; it never adds a second input. */
    @Test
    fun theOpenSearchIsOneInputAnchoredToTheHeaderField() = runComposeUiTest {
        header(onHome = false)
        typeInSearch("plan")
        onAllNodes(hasSetTextAction()).assertCountEquals(1)
        onAllNodesWithText("plan").assertCountEquals(1)
        val field = onNodeWithTag(ShellHeaderTags.SEARCH).fetchSemanticsNode().boundsInWindow
        val pane = onNodeWithTag(LettaPaneTags.POPOVER).fetchSemanticsNode().boundsInWindow
        assertTrue(abs(pane.left - field.left) <= 1f, "pane left ${pane.left} vs header field left ${field.left}")
        assertTrue(abs(pane.width - field.width) <= 1f, "pane width ${pane.width} vs header field width ${field.width}")
        assertTrue(abs(pane.top - field.top) <= 1f, "pane top ${pane.top} vs header field top ${field.top}")
        onNodeWithText("Filter to this agent").assertExists()
    }

    private class RecordingHome : HomePageActions {
        val queries = mutableListOf<String>()

        override fun refresh() = Unit

        override fun updateSearchQuery(query: String) {
            queries += query
        }

        override fun clearSearch() = Unit

        override fun selectSort(key: FleetSortKey) = Unit

        override fun reorderPins(keys: List<String>) = Unit

        override fun setShortcutPinned(shortcut: HomeShortcut, pinned: Boolean) = Unit

        override fun setAgentPinned(agentId: String, pinned: Boolean) = Unit
    }
}
