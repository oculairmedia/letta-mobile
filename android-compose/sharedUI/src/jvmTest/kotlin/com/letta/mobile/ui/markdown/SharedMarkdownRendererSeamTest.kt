@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-bglj6.1.16: a provided [SharedRichMarkdownRenderer] takes over the paint — the
 * host's rich renderer draws every non-blank body, with the streaming flag it needs to own the
 * reveal — while a missing provider keeps the shared default (covered by
 * [SharedMarkdownRecompositionTest]).
 */
class SharedMarkdownRendererSeamTest {
    @Test
    fun aProvidedRendererPaintsEveryBodyWithItsStreamingFlag() = runComposeUiTest {
        val painted = mutableListOf<Pair<String, Boolean>>()
        val rich = SharedRichMarkdownRenderer { text, _, isStreaming, _ ->
            painted += text to isStreaming
        }
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSharedRichMarkdownRenderer provides rich) {
                    SharedMarkdownText(text = SETTLED)
                    SharedMarkdownText(text = STREAMING, isStreaming = true)
                }
            }
        }
        waitForIdle()
        assertEquals(
            listOf(SETTLED to false, STREAMING to true),
            painted,
            "the rich renderer must receive both bodies with their streaming flags",
        )
    }

    @Test
    fun aProvidedRendererNeverSeesABlankBody() = runComposeUiTest {
        val painted = mutableListOf<String>()
        val rich = SharedRichMarkdownRenderer { text, _, _, _ -> painted += text }
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSharedRichMarkdownRenderer provides rich) {
                    SharedMarkdownText(text = "")
                    SharedMarkdownText(text = BLANK)
                }
            }
        }
        waitForIdle()
        assertEquals(emptyList(), painted, "blank bodies stay with the default renderer's early return")
    }

    private companion object {
        const val SETTLED = "Settled prose."
        const val STREAMING = "Still landing"
        const val BLANK = "   "
    }
}