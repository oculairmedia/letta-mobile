package com.letta.mobile.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [streamingDisplayText] — the streaming-cursor
 * decorator used while `isStreaming = true`.
 *
 * Background: see letta-mobile-flk2. The original word-boundary
 * holdback (letta-mobile-6p4o.1) was a defense against bursty WS
 * frames — a single frame would carry 5–40 chars and the renderer
 * would paint the whole frame atomically, making mid-word splits
 * visible to the user. With the [ClientModeStreamSmoother] now
 * delivering chars at a smoothed 90–180 cps, the renderer never sees
 * bursts large enough for that to matter, and the held-tail clamp's
 * leapfrogging cursor became its own visible flicker source.
 *
 * The current contract appends the cursor to markdown-stable content,
 * with two carve-outs:
 *  - empty input returns empty to avoid a pre-stream cursor flash
 *  - open code fences return raw text so the cursor doesn't render as
 *    literal code content
 */
class StreamingDisplayTextTest {

    private val cursor = "\u258E" // must match STREAMING_CURSOR

    @Test
    fun emptyInput_returnsEmpty() {
        assertEquals("", streamingDisplayText(""))
    }

    @Test
    fun textEndingInWhitespace_rendersFullPlusCursor() {
        val out = streamingDisplayText("Hello world ")
        assertEquals("Hello world $cursor", out)
    }

    @Test
    fun textEndingMidWord_emitsFullPlusCursor() {
        // letta-mobile-flk2: held-tail clamp removed; smoother now
        // delivers chars at a smoothed rate so mid-word visibility is
        // no longer bursty enough to warrant the holdback.
        val out = streamingDisplayText("Hello wor")
        assertEquals("Hello wor$cursor", out)
    }

    @Test
    fun trailingPunctuation_emitsFullPlusCursor() {
        val out = streamingDisplayText("Hello, world.")
        assertEquals("Hello, world.$cursor", out)
    }

    @Test
    fun singleVeryLongTokenWithNoBoundary_emitsFull() {
        val raw = "a".repeat(50)
        assertEquals(raw + cursor, streamingDisplayText(raw))
    }

    @Test
    fun longTrailingFragment_emitsFull() {
        val tail = "ab".repeat(50)
        val raw = "Hi $tail"
        assertEquals(raw + cursor, streamingDisplayText(raw))
    }

    @Test
    fun shortTrailingFragment_emitsFullPlusCursor() {
        // letta-mobile-flk2: previously held back as "Hello $cursor".
        val out = streamingDisplayText("Hello supercalifrag")
        assertEquals("Hello supercalifrag$cursor", out)
    }

    @Test
    fun newline_emitsFullPlusCursor() {
        val out = streamingDisplayText("Line one\nLineTwoMidWor")
        assertEquals("Line one\nLineTwoMidWor$cursor", out)
    }

    @Test
    fun multilineMidWord_emitsFullPlusCursor() {
        val out = streamingDisplayText("Line one\nLine tw")
        assertEquals("Line one\nLine tw$cursor", out)
    }

    @Test
    fun realWireFrameFromWsstreamTrace_emitsFullPlusCursor() {
        // letta-mobile-flk2: the smoother chose WHEN to paint each
        // char, so painting the trailing "O" mid-word is no longer a
        // bursty flash — it's the next char of a smoothly-advancing
        // stream. No clamp needed.
        val raw = "- **Lightweight threads**: thousands can run concurrently on a small thread pool — suspension is cheap, no O"
        assertEquals(raw + cursor, streamingDisplayText(raw))
    }

    @Test
    fun openCodeFence_skipsCursor() {
        // Open ``` fence — content after the fence opener should be
        // rendered as-is (a ▎ would render literally inside the code
        // block, which looks broken).
        val raw = "Here:\n```kotlin\nfun foo() {\n    val x = 1"
        val out = streamingDisplayText(raw)
        assertEquals(raw, out)
    }

    @Test
    fun closedCodeFence_appendsCursor() {
        // After a closing ``` the fence count is even — cursor is OK.
        val raw = "Done:\n```\nfoo\n```\nNext wo"
        val out = streamingDisplayText(raw)
        assertEquals(raw + cursor, out)
    }
}
