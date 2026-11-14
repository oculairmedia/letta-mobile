@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.foundation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.height
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LettaSearchFieldTest {
    @Test
    fun theFieldFillsItsWidthAndClearsWhatWasTyped() {
        FOUNDATION_WIDTHS.forEach { width ->
            runDesktopComposeUiTest(FOUNDATION_WINDOW_WIDTH, FOUNDATION_WINDOW_HEIGHT) {
                var query by mutableStateOf("")
                setFoundationContent(width) {
                    LettaSearchField(query = query, onQueryChange = { query = it }, modifier = Modifier.testTag(FIELD))
                }
                onAllNodesWithContentDescription(LETTA_SEARCH_CLEAR).assertCountEquals(0)
                onNodeWithTag(FIELD).performTextInput("agents")
                assertEquals("agents", query)
                onNodeWithContentDescription(LETTA_SEARCH_CLEAR).performClick()
                assertEquals("", query)
                val bounds = onNodeWithTag(FIELD).getUnclippedBoundsInRoot()
                assertTrue(bounds.height >= LettaControlTokens.minTouchTarget, "at $width the field is ${bounds.height} tall")
            }
        }
    }

    @Test
    fun theInlineFieldFillsItsWidthAndKeepsItsHeightWhileTyping() {
        FOUNDATION_WIDTHS.forEach { width ->
            runDesktopComposeUiTest(FOUNDATION_WINDOW_WIDTH, FOUNDATION_WINDOW_HEIGHT) {
                setFoundationContent(width) {
                    var query by remember { mutableStateOf("") }
                    LettaInlineSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        modifier = Modifier.testTag(ROW),
                        options = LettaSearchOptions(fieldModifier = Modifier.testTag(FIELD)),
                    )
                }
                onNodeWithTag(ROW).assertWidthIsEqualTo(width)
                val empty = onNodeWithTag(ROW).getUnclippedBoundsInRoot().height
                onNodeWithTag(FIELD).performTextInput("x")
                onNodeWithContentDescription(LETTA_SEARCH_CLEAR).performClick()
                onNodeWithTag(FIELD).performTextInput("y")
                assertEquals(empty, onNodeWithTag(ROW).getUnclippedBoundsInRoot().height, "the row does not jump when the clear button shows")
            }
        }
    }

    private companion object {
        const val FIELD = "field"
        const val ROW = "row"
    }
}
