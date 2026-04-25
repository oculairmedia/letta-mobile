package com.letta.mobile.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [streamingDisplayText] — the word-boundary clamp +
 * streaming-cursor injection used by [TextMessageRenderer] etc. while
 * `isStreaming = true`.
 *
 * Background: see letta-mobile-6p4o.1. Wire-level traces from
 * `:cli wsstream` showed lettabot deltas split mid-token, producing
 * brief on-screen flashes like "...no O" before the next chunk lands.
 * This util holds back the trailing partial-word until a boundary
 * arrives, and decorates the visible tail with a cursor glyph (▎).
 */
class StreamingDisplayTextTest {

    private val cursor = "\u258E" // must match STREAMING_CURSOR

    @Test
    fun emptyInput_returnsCursorOnly() {
        assertEquals(cursor, streamingDisplayText(""))
    }

    @Test
    fun textEndingInWhitespace_rendersFullPlusCursor() {
        // The whole text already terminates at a boundary — emit it all.
        val out = streamingDisplayText("Hello world ")
        assertEquals("Hello world $cursor", out)
    }

    @Test
    fun textEndingMidWord_holdsBackTrailingFragment() {
        // "Hello wor" — held back to "Hello " + cursor (no flash of "wor").
        val out = streamingDisplayText("Hello wor")
        assertEquals("Hello $cursor", out)
    }

    @Test
    fun trailingPunctuation_isABoundary() {
        // Period is a boundary char. "Hello, world." ends at a boundary
        // already, so the whole string is visible + cursor at the end.
        val out = streamingDisplayText("Hello, world.")
        assertEquals("Hello, world.$cursor", out)
    }

    @Test
    fun singleVeryLongTokenWithNoBoundary_emitsFullToAvoidStall() {
        // No whitespace anywhere — the held-back tail would be the entire
        // text. Spec says we emit it all rather than stall the UI forever.
        val raw = "a".repeat(50)
        assertEquals(raw + cursor, streamingDisplayText(raw))
    }

    @Test
    fun longTrailingFragmentExceedingMaxHeld_emitsAnyway() {
        // 100 chars of "ab" with a leading "Hi " — last whitespace is
        // index 2; trailing fragment is 100 chars, which exceeds the
        // 80-char held-tail cap. Spec: emit it all.
        val tail = "ab".repeat(50)
        val raw = "Hi $tail"
        assertEquals(raw + cursor, streamingDisplayText(raw))
    }

    @Test
    fun shortTrailingFragmentUnderCap_isHeldBack() {
        // "Hello supercalifrag" — last boundary at index 5 (space),
        // tail is "supercalifrag" = 13 chars, well under the 80 cap,
        // so we hold it back.
        val out = streamingDisplayText("Hello supercalifrag")
        assertEquals("Hello $cursor", out)
    }

    @Test
    fun newlineCountsAsBoundary() {
        // "Line one\nLineTwoMidWor" — first word on line 2 is unterminated,
        // and there is no space inside it, so the only boundary visible
        // before the partial token is the newline at index 8.
        val out = streamingDisplayText("Line one\nLineTwoMidWor")
        assertEquals("Line one\n$cursor", out)
    }

    @Test
    fun lastBoundaryWins_evenIfAnEarlierOneIsNewline() {
        // "Line one\nLine tw" — there's a space at index 13 (after "Line"),
        // which is a *later* boundary than the newline. Spec: clamp to the
        // last boundary, not the first. So "Line tw" → "Line " is held back
        // visible portion = "Line one\nLine ".
        val out = streamingDisplayText("Line one\nLine tw")
        assertEquals("Line one\nLine $cursor", out)
    }

    @Test
    fun realWireFrameFromWsstreamTrace_clampsCorrectly() {
        // From the actual wsstream trace, frame 3's full assembled text
        // ended at "...suspension is cheap, no O". Clamp should hold "O"
        // back since it's a single trailing letter after a comma+space.
        val raw = "- **Lightweight threads**: thousands can run concurrently on a small thread pool — suspension is cheap, no O"
        val out = streamingDisplayText(raw)
        // Should NOT include the trailing "O"
        assertFalse("trailing 'O' should be held back, got: $out", out.endsWith("O$cursor"))
        // Should end at the comma-space boundary or a later space.
        assertTrue("expected cursor at end, got: $out", out.endsWith(cursor))
        // Verify the visible portion has no fragment after last boundary.
        val visible = out.removeSuffix(cursor)
        val lastChar = visible.last()
        assertTrue(
            "expected visible to end at whitespace/punct, got '$lastChar' from: $visible",
            lastChar.isWhitespace() || lastChar in setOf('.', ',', ';', ':', '!', '?', ')', '\'', '"', '`', ']', '}', '>')
        )
    }

    @Test
    fun openCodeFence_skipsClampAndCursor() {
        // Open ``` fence — content after the fence opener should be
        // rendered as-is (whitespace inside code is meaningful and a
        // ▎ would render literally inside the code block).
        val raw = "Here:\n```kotlin\nfun foo() {\n    val x = 1"
        val out = streamingDisplayText(raw)
        assertEquals(raw, out)
    }

    @Test
    fun closedCodeFence_clampsNormally() {
        // After a closing ``` the fence count is even — clamp resumes.
        val raw = "Done:\n```\nfoo\n```\nNext wo"
        val out = streamingDisplayText(raw)
        // "wo" should be held back.
        assertFalse(out.endsWith("wo$cursor"))
        assertTrue(out.endsWith(cursor))
    }
}
