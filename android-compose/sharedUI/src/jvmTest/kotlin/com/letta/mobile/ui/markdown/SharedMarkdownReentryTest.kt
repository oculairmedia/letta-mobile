@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A settled row that scrolls away leaves composition; when it scrolls back it must not replay an
 * entrance. Its markdown starts from the parse it already had, so on its first frame back the text
 * is there at its final height (a re-parse started from the empty loading box and grew the text in
 * from the top, on every scroll back). A text never shown before still parses, and grows in, as a
 * newly arrived row does.
 */
class SharedMarkdownReentryTest {
    @Test
    fun aSettledTextBackInCompositionIsAtItsFinalHeightOnItsFirstFrame() = runComposeUiTest {
        var shown by mutableStateOf(true)
        val parses = SettledMarkdownParses()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettledMarkdownParses provides parses) {
                    Column(Modifier.width(WIDTH.dp)) {
                        if (shown) SharedMarkdownText(text = SETTLED, modifier = Modifier.testTag(TAG))
                    }
                }
            }
        }
        waitUntil(timeoutMillis = PARSE_TIMEOUT_MILLIS) {
            onAllNodes(hasText(FIRST_LINE, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        waitForIdle()
        val settled = onNodeWithTag(TAG).getUnclippedBoundsInRoot()
        assertTrue(MarkdownSource(SETTLED) in parses, "the timeline keeps the settled parse")

        shown = false
        waitForIdle()
        mainClock.autoAdvance = false
        shown = true
        mainClock.advanceTimeByFrame()
        assertTrue(
            onAllNodes(hasText(FIRST_LINE, substring = true)).fetchSemanticsNodes().isNotEmpty(),
            "the text is there on its first frame back, not the loading box",
        )
        val back = onNodeWithTag(TAG).getUnclippedBoundsInRoot()
        assertEquals(settled.bottom - settled.top, back.bottom - back.top, "its first frame back is its final height")
    }

    @Test
    fun aTextNeverShownBeforeStillParses() = runComposeUiTest {
        val parses = SettledMarkdownParses()
        assertTrue(MarkdownSource(NEW) !in parses, "a new text has no kept parse to start from")
        setContent {
            MaterialTheme { CompositionLocalProvider(LocalSettledMarkdownParses provides parses) { SharedMarkdownText(text = NEW) } }
        }
        waitUntil(timeoutMillis = PARSE_TIMEOUT_MILLIS) {
            MarkdownSource(NEW) in parses && onAllNodes(hasText(NEW, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun readingAnOlderParsePromotesItPastCapacityEviction() {
        val parses = SettledMarkdownParses(capacity = 2)
        val first = MarkdownSource("first")
        val second = MarkdownSource("second")
        val third = MarkdownSource("third")
        parses.keepDocument(first, StreamingMarkdownDocument(emptyList()))
        parses.keepDocument(second, StreamingMarkdownDocument(emptyList()))
        // Accessing `first` marks it most-recently-used, so inserting `third` evicts `second`.
        checkNotNull(parses.document(first))
        parses.keepDocument(third, StreamingMarkdownDocument(emptyList()))
        assertTrue(parses.document(first) != null, "recently read entry survives eviction")
        assertTrue(parses.document(second) == null, "least-recently-used entry is evicted")
        assertTrue(parses.document(third) != null, "newly inserted entry is kept")
    }

    private companion object {
        const val TAG = "reentry-markdown"
        const val FIRST_LINE = "Scrolled away and back"
        const val SETTLED = "$FIRST_LINE, this reply\n\n- keeps its list\n- and its **bold**\n\nwhen it returns."
        const val NEW = "A reply that has just arrived, never shown before in this process."
        const val WIDTH = 400
        const val PARSE_TIMEOUT_MILLIS = 20_000L
    }
}
