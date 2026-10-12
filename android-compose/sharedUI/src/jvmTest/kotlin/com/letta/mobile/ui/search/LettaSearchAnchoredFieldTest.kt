@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.host.LettaPaneTags
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The header's anchored field (letta-mobile-xuhmy, letta-mobile-06whj): the pane it opens covers the
 * field-shaped trigger, so the window shows ONE input with the scopes, filter and results directly
 * beneath it. With [LettaSearchConfig.showResults] off it opens the field alone, because the page
 * behind it (desktop Home) is the result surface.
 */
class LettaSearchAnchoredFieldTest {
    private val row = LettaSearchRow(id = "agent:a-1", label = "Planner")
    private val toggle = LettaSearchToggle(label = TOGGLE, checked = true, onCheckedChange = {})

    private fun ComposeUiTest.open(showResults: Boolean, initialQuery: String = "") {
        setContent {
            MaterialTheme {
                var query by remember { mutableStateOf(initialQuery) }
                Box(Modifier.padding(start = FIELD_INSET, top = FIELD_INSET)) {
                    LettaSearchAnchoredField(
                        source = LettaSearchSource(
                            query = query,
                            onQueryChange = { query = it },
                            sections = listOf(LettaSearchSection(title = "Agents", rows = listOf(row))),
                            onRowSelected = {},
                        ),
                        modifier = Modifier.width(FIELD_WIDTH),
                        config = LettaSearchConfig(placeholder = PLACEHOLDER, autoFocus = false, toggle = toggle, showResults = showResults),
                    )
                }
            }
        }
        onNodeWithTag(SearchTriggerTestTag).performClick()
        waitForIdle()
    }

    @Test
    fun theFieldOpensWithItsResultsByDefault() = runComposeUiTest {
        open(showResults = true)
        onNodeWithText(TOGGLE).assertExists()
        onNodeWithText("Planner").assertExists()
    }

    @Test
    fun withoutResultsTheFieldOpensAlone() = runComposeUiTest {
        open(showResults = false)
        onNodeWithText(TOGGLE).assertDoesNotExist()
        onNodeWithText("Planner").assertDoesNotExist()
    }

    @Test
    fun anOpenPaneShowsExactlyOneInputAndTheQueryOnce() = runComposeUiTest {
        open(showResults = true, initialQuery = "mer")
        onAllNodes(hasSetTextAction()).assertCountEquals(1)
        onAllNodesWithText("mer").assertCountEquals(1)
    }

    @Test
    fun thePaneCoversTheFieldAtItsLeftEdgeAndWidth() = runComposeUiTest {
        open(showResults = true)
        val trigger = onNodeWithTag(SearchTriggerTestTag).fetchSemanticsNode().boundsInWindow
        val pane = onNodeWithTag(LettaPaneTags.POPOVER).fetchSemanticsNode().boundsInWindow
        assertTrue(abs(pane.left - trigger.left) <= 1f, "pane left ${pane.left} vs field left ${trigger.left}")
        assertTrue(abs(pane.top - trigger.top) <= 1f, "pane top ${pane.top} vs field top ${trigger.top}")
        assertTrue(abs(pane.width - trigger.width) <= 1f, "pane width ${pane.width} vs field width ${trigger.width}")
        // The pane's own field sits where the trigger was: its input is inside the trigger's band.
        val input = onNodeWithTag(SearchFieldTestTag).fetchSemanticsNode().boundsInWindow
        assertTrue(input.top < trigger.bottom, "the input ${input.top} starts within the field's band (${trigger.bottom})")
    }

    @Test
    fun escapeClosesThePane() = runComposeUiTest {
        open(showResults = true)
        onNodeWithTag(SearchFieldTestTag).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithTag(SearchPanelTestTag).assertDoesNotExist()
        onNodeWithText(PLACEHOLDER).assertExists()
    }

    private companion object {
        const val PLACEHOLDER = "Search the page"
        const val TOGGLE = "Filter to this agent"
        val FIELD_WIDTH = 260.dp
        val FIELD_INSET = 120.dp
    }
}
