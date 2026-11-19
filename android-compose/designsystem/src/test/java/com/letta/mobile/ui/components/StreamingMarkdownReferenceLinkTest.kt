package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rich renderer paints a message block by block, and a reference definition sits in a block of
 * its own. A reference-style link still opens its definition's destination, the definition paints
 * nothing, and the settled row, unmounted and composed again, opens the same destination.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["androidx.loader.content"])
class StreamingMarkdownReferenceLinkTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aSettledReferenceLinkOpensTheSameDestinationAfterTheRowRemounts() {
        var shown by mutableStateOf(true)
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        composeRule.setContent {
            LettaTheme {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    Column(Modifier.width(WIDTH.dp)) {
                        if (shown) StreamingMarkdownText(text = REPLY, isStreaming = false)
                    }
                }
            }
        }
        activateLink()
        assertEquals(listOf(DESTINATION), opened)

        shown = false
        composeRule.waitForIdle()
        shown = true
        activateLink()
        assertEquals(listOf(DESTINATION, DESTINATION), opened)
    }

    private fun activateLink() {
        composeRule.waitUntil(timeoutMillis = PARSE_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasText(LINK_TEXT, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        assertTrue(
            "the definition paints no text",
            composeRule.onAllNodes(hasText(DESTINATION, substring = true)).fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onAllNodes(hasText(LINK_TEXT, substring = true)).onFirst().performTouchInput {
            click(Offset(TAP_X, centerY))
        }
        composeRule.waitForIdle()
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
