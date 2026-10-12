@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * The header's anchored field (letta-mobile-xuhmy): with [LettaSearchConfig.showResults] off it
 * opens the field alone, because the page behind it (desktop Home) is the result surface.
 */
class LettaSearchAnchoredFieldTest {
    private val row = LettaSearchRow(id = "agent:a-1", label = "Planner")
    private val toggle = LettaSearchToggle(label = TOGGLE, checked = true, onCheckedChange = {})

    private fun ComposeUiTest.open(showResults: Boolean) {
        setContent {
            MaterialTheme {
                LettaSearchAnchoredField(
                    query = "",
                    onQueryChange = {},
                    sections = listOf(LettaSearchSection(title = "Agents", rows = listOf(row))),
                    onRowSelected = {},
                    onDismiss = {},
                    config = LettaSearchConfig(placeholder = PLACEHOLDER, autoFocus = false, toggle = toggle, showResults = showResults),
                )
            }
        }
        onNodeWithText(PLACEHOLDER).performClick()
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

    private companion object {
        const val PLACEHOLDER = "Search the page"
        const val TOGGLE = "Filter to this agent"
    }
}
