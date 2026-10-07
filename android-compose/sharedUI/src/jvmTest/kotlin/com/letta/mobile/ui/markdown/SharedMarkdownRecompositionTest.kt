@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.State
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1: a recomposition that keeps the text keeps the rendered markdown. The
 * renderer's defaults built a new parser per composition, so any recomposition of a row (a pinch
 * changing the font scale, a theme colour, a parent re-reading state) re-parsed the text and
 * dropped it to the empty loading box until the parse finished off the UI thread: the text
 * blinked, and a held selection, with its Copy / Select all toolbar, went with it.
 */
class SharedMarkdownRecompositionTest {
    @Test
    fun aRecompositionKeepsTheParse() = runComposeUiTest {
        var tick by mutableIntStateOf(0)
        lateinit var state: MarkdownState
        setContent {
            // Read here, so each tick recomposes the caller of the state.
            check(tick >= 0)
            state = rememberSharedMarkdownState(FIRST_LINE, retainState = false)
        }
        waitUntil(timeoutMillis = PARSE_TIMEOUT_MILLIS) { state.state.value is State.Success }
        val parsed = state.state.value
        repeat(RECOMPOSITIONS) {
            tick++
            waitForIdle()
            assertSame(parsed, state.state.value, "recomposition $it parsed the same text again")
        }
    }

    @Test
    fun recomposingWithTheSameTextNeverBlanksIt() = runComposeUiTest {
        var color by mutableStateOf(Color.Black)
        setContent {
            MaterialTheme { SharedMarkdownText(text = longText, textColor = color) }
        }
        val rendered = hasText(FIRST_LINE, substring = true)
        // The first parse runs off the UI thread: wait for the text.
        waitUntil(timeoutMillis = PARSE_TIMEOUT_MILLIS) { onAllNodes(rendered).fetchSemanticsNodes().isNotEmpty() }
        repeat(RECOMPOSITIONS) { i ->
            color = if (i % 2 == 0) Color.DarkGray else Color.Black
            waitForIdle()
            // A re-parse would show the empty loading box now, while it parses.
            assertTrue(onAllNodes(rendered).fetchSemanticsNodes().isNotEmpty(), "recomposition $i blanked the text")
        }
    }

    private companion object {
        const val FIRST_LINE = "Here's an L-shaped plan"
        const val RECOMPOSITIONS = 4
        const val PARAGRAPHS = 600
        const val PARSE_TIMEOUT_MILLIS = 20_000L

        /** Long enough that a re-parse is still running when the recomposer goes idle. */
        val longText: String = buildString {
            append(FIRST_LINE).append(" with an island.")
            repeat(PARAGRAPHS) { append("\n\nParagraph ").append(it).append(" with **bold**, `code` and a [link](https://example.com).") }
        }
    }
}
