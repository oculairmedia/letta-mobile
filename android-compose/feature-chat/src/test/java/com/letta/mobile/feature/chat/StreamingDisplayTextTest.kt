package com.letta.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [streamingDisplayText] and [shouldShowStreamingCursor]
 * — the streaming-tail decorator + cursor-eligibility check applied while
 * `isStreaming = true`.
 *
 * Background: see letta-mobile-flk2 for the markdown-stability clamp.
 *
 * letta-mobile-d9zy.5 (retry): cursor injection moved out of
 * [streamingDisplayText] into the renderer so the cursor can fade
 * independently. The tests below pin both halves of the new contract:
 *
 *  1. [streamingDisplayText] returns clean text — no cursor glyph
 *     appended. Inside an open ``` fence it returns the raw text
 *     unchanged so whitespace stays meaningful for code blocks.
 *  2. [shouldShowStreamingCursor] returns false for empty input and
 *     inside open ``` fences, true otherwise. The renderer pairs this
 *     with [streamingDisplayText] to decide whether to emit a cursor
 *     span on the active tail.
 */
class StreamingDisplayTextTest {

    @Test
    fun emptyInput_returnsEmpty() {
        assertEquals("", streamingDisplayText(""))
    }

    @Test
    fun textEndingInWhitespace_returnsAsIs() {
        assertEquals("Hello world ", streamingDisplayText("Hello world "))
    }

    @Test
    fun textEndingMidWord_returnsAsIs() {
        // letta-mobile-flk2: held-tail clamp removed; smoother now
        // delivers chars at a smoothed rate so mid-word visibility is
        // no longer bursty enough to warrant the holdback.
        assertEquals("Hello wor", streamingDisplayText("Hello wor"))
    }

    @Test
    fun trailingPunctuation_returnsAsIs() {
        assertEquals("Hello, world.", streamingDisplayText("Hello, world."))
    }

    @Test
    fun singleVeryLongTokenWithNoBoundary_returnsAsIs() {
        val raw = "a".repeat(50)
        assertEquals(raw, streamingDisplayText(raw))
    }

    @Test
    fun longTrailingFragment_returnsAsIs() {
        val tail = "ab".repeat(50)
        val raw = "Hi $tail"
        assertEquals(raw, streamingDisplayText(raw))
    }

    @Test
    fun shortTrailingFragment_returnsAsIs() {
        // letta-mobile-flk2: previously held back as "Hello ".
        assertEquals("Hello supercalifrag", streamingDisplayText("Hello supercalifrag"))
    }

    @Test
    fun newline_returnsAsIs() {
        assertEquals(
            "Line one\nLineTwoMidWor",
            streamingDisplayText("Line one\nLineTwoMidWor"),
        )
    }

    @Test
    fun multilineMidWord_returnsAsIs() {
        assertEquals(
            "Line one\nLine tw",
            streamingDisplayText("Line one\nLine tw"),
        )
    }

    @Test
    fun realWireFrameFromWsstreamTrace_returnsAsIs() {
        // letta-mobile-flk2: smoother chose WHEN to paint each char, so
        // painting the trailing "O" mid-word is no longer a bursty flash.
        val raw = "- **Lightweight threads**: thousands can run concurrently on a small thread pool — suspension is cheap, no O"
        assertEquals(raw, streamingDisplayText(raw))
    }

    @Test
    fun openCodeFence_returnsRawUnchanged() {
        // Inside an open ``` fence — leave content alone (whitespace is
        // meaningful in code blocks).
        val raw = "Here:\n```kotlin\nfun foo() {\n    val x = 1"
        assertEquals(raw, streamingDisplayText(raw))
    }

    @Test
    fun closedCodeFence_returnsClampedText() {
        // After a closing ``` the fence count is even — clamp applies again.
        val raw = "Done:\n```\nfoo\n```\nNext wo"
        assertEquals(raw, streamingDisplayText(raw))
    }

    @Test
    fun shouldShowStreamingCursor_emptyInput_isFalse() {
        assertFalse(shouldShowStreamingCursor(""))
    }

    @Test
    fun shouldShowStreamingCursor_plainText_isTrue() {
        assertTrue(shouldShowStreamingCursor("Hello world"))
    }

    @Test
    fun shouldShowStreamingCursor_insideOpenCodeFence_isFalse() {
        // Open ``` fence — cursor glyph would render as literal content
        // inside the rendered code block, which looks broken.
        val raw = "Here:\n```kotlin\nfun foo() {\n    val x = 1"
        assertFalse(shouldShowStreamingCursor(raw))
    }

    @Test
    fun shouldShowStreamingCursor_afterClosedCodeFence_isTrue() {
        // Even fence count — we are back in prose; cursor is appropriate again.
        val raw = "Done:\n```\nfoo\n```\nNext wo"
        assertTrue(shouldShowStreamingCursor(raw))
    }
}
