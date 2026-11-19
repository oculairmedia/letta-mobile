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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reference-style link resolves through its definition elsewhere in the text. A settled row that
 * leaves composition and comes back starts from the page's kept parse instead of parsing again; the
 * link it paints from that parse must still open the definition's destination, exactly as it did the
 * first time the row composed.
 */
class SettledMarkdownReferenceLinkTest {
    @Test
    fun aReferenceLinkOpensTheSameDestinationAfterTheRowRemountsFromTheKeptParse() = runComposeUiTest {
        var shown by mutableStateOf(true)
        val parses = SettledMarkdownParses()
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalSettledMarkdownParses provides parses,
                    LocalUriHandler provides uriHandler,
                ) {
                    Column(Modifier.width(WIDTH.dp)) {
                        if (shown) SharedMarkdownText(text = REPLY)
                    }
                }
            }
        }
        awaitLinkText()
        waitUntil(timeoutMillis = PARSE_TIMEOUT_MILLIS) { MarkdownSource(REPLY) in parses }
        activateLink()
        assertEquals(listOf(DESTINATION), opened, "the first composition opens the definition's destination")

        shown = false
        waitForIdle()
        shown = true
        awaitLinkText()
        activateLink()
        assertEquals(
            listOf(DESTINATION, DESTINATION),
            opened,
            "the row back from the kept parse opens the same destination",
        )
    }

    private fun ComposeUiTest.awaitLinkText() {
        waitUntil(timeoutMillis = PARSE_TIMEOUT_MILLIS) {
            onAllNodes(hasText(LINK_TEXT, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        waitForIdle()
        assertTrue(onAllNodes(hasText("[1]:", substring = true)).fetchSemanticsNodes().isEmpty(), "the definition is not painted")
    }

    /** Taps the start of the paragraph, which is the link's first word. */
    private fun ComposeUiTest.activateLink() {
        onAllNodes(hasText(LINK_TEXT, substring = true)).onFirst().performTouchInput {
            click(Offset(TAP_X, centerY))
        }
        waitForIdle()
    }

    private companion object {
        const val LINK_TEXT = "documentation"
        const val DESTINATION = "https://example.com/docs"
        const val REPLY = "[$LINK_TEXT][1]\n\n[1]: $DESTINATION"
        const val WIDTH = 400
        const val TAP_X = 8f
        const val PARSE_TIMEOUT_MILLIS = 20_000L
    }
}
