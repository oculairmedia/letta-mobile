package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.render.MAX_HELD_TAIL_CHARS
import com.letta.mobile.feature.chat.render.clampToStableMarkdown
import com.letta.mobile.feature.chat.render.clampToWordBoundary
import com.letta.mobile.feature.chat.render.MarkdownOpenerScanLine
import com.letta.mobile.feature.chat.render.findUnmatchedOpenerInLine
import com.letta.mobile.feature.chat.render.hasOpenDisplayMathFence
import com.letta.mobile.feature.chat.render.insideOpenCodeFence
import com.letta.mobile.feature.chat.render.isStreamingBoundary
import com.letta.mobile.feature.chat.render.streamingDisplayText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Tests for the prefix-stable streaming markdown pipeline.
 *
 * ## Prefix-Stability Contract
 *
 * 1. **No text dropped.** Every char survives — incomplete markup renders as
 *    plain text until its closer arrives.
 * 2. **Earlier prefixes stable.** Committed markdown never flickers when new
 *    chunks arrive. Only the trailing incomplete construct is "unstable".
 * 3. **Incomplete tail passes through.** Open `**`, `*`, `_`, `__`, `` ` ``,
 *    `~~`, `[text]`(no url yet), `$$`, ` ``` ` cause full raw tail to pass
 *    through unclamped.  Renderer repairs markup synthetically.
 * 4. **Fence transitions clean.** Inside open ` ``` ` or `$$` fence, raw
 *    content passes through; once closed, prose still renders as-received.
 * 5. **Plain prose renders as-received.** The cursor communicates partialness
 *    without hiding already-arrived characters.
 *
 * References: llm-typewriter (prefix-stability), letta-mobile-flk2,
 * letta-mobile-6p4o, letta-mobile-c8of.6.
 */
@Tag("unit")
class StreamingDisplayTextTest {

    // ═══ streamingDisplayText pipeline ═══

    @Test
    fun `empty returns empty`() {
        assertEquals("", streamingDisplayText(""))
    }

    @Test
    fun `plain prose renders trailing partial word`() {
        assertEquals("Hello wor", streamingDisplayText("Hello wor"))
    }

    @Test
    fun `prose ending at boundary char returns full`() {
        assertEquals("Hello.", streamingDisplayText("Hello."))
        assertEquals("Hello, world.", streamingDisplayText("Hello, world."))
    }

    // ── Incomplete tail markup: passes through, no drop ──

    @Test
    fun `open bold star-star tail passes through`() {
        assertEquals("Hello **wor", streamingDisplayText("Hello **wor"))
    }

    @Test
    fun `open bold underscore-underscore tail passes through`() {
        assertEquals("Hello __wor", streamingDisplayText("Hello __wor"))
    }

    @Test
    fun `open italic star tail passes through`() {
        assertEquals("Hello *wor", streamingDisplayText("Hello *wor"))
    }

    @Test
    fun `open italic underscore tail passes through`() {
        assertEquals("Hello _wor", streamingDisplayText("Hello _wor"))
    }

    @Test
    fun `open bold-italic star-star-star tail passes through`() {
        assertEquals("Hello ***wor", streamingDisplayText("Hello ***wor"))
    }

    @Test
    fun `open inline code backtick tail passes through`() {
        assertEquals("Run `ls /tmp/so", streamingDisplayText("Run `ls /tmp/so"))
    }

    @Test
    fun `open strikethrough tilde-tilde tail passes through`() {
        assertEquals("This is ~~wr", streamingDisplayText("This is ~~wr"))
    }

    @Test
    fun `open link bracket tail passes through`() {
        assertEquals("See [the docs", streamingDisplayText("See [the docs"))
    }

    @Test
    fun `link text closed bracket but no paren yet passes through`() {
        assertEquals("See [the docs] f", streamingDisplayText("See [the docs] f"))
    }

    @Test
    fun `link with open paren but no close passes through`() {
        assertEquals("See [docs](https://ex", streamingDisplayText("See [docs](https://ex"))
    }

    // Closed markup and plain prose render every received character. The
    // cursor indicates that the stream may still be mid-token.

    @Test
    fun `closed bold renders fully even without trailing boundary`() {
        assertEquals("**bold text**", streamingDisplayText("**bold text**"))
    }

    @Test
    fun `closed bold with trailing period renders fully`() {
        assertEquals("**bold text**.", streamingDisplayText("**bold text**."))
    }

    @Test
    fun `closed italic renders fully even without trailing boundary`() {
        assertEquals("*italic text*", streamingDisplayText("*italic text*"))
    }

    @Test
    fun `closed inline code renders full text`() {
        assertEquals("Run `ls` now", streamingDisplayText("Run `ls` now"))
    }

    @Test
    fun `closed strikethrough without trailing boundary returns full`() {
        // ~~done~~ has no boundary chars (no spaces/punctuation), so returns full
        assertEquals("~~done~~", streamingDisplayText("~~done~~"))
    }

    @Test
    fun `closed link with trailing boundary renders fully`() {
        // ) is a boundary char, so full link renders
        assertEquals("See [docs](https://x.com)", streamingDisplayText("See [docs](https://x.com)"))
    }

    @Test
    fun `mixed balanced and unbalanced — passes through`() {
        assertEquals("**done** and *st", streamingDisplayText("**done** and *st"))
    }

    @Test
    fun `prior paragraph stable when current line has open markup`() {
        assertEquals(
            "Prior paragraph.\n\nNow **incomplete",
            streamingDisplayText("Prior paragraph.\n\nNow **incomplete"),
        )
    }

    // ── Fenced code block transitions ──

    @Test
    fun `inside open code fence raw passes through unchanged`() {
        val text = "Intro\n\n```kotlin\nfun foo() {"
        assertEquals(text, streamingDisplayText(text))
    }

    @Test
    fun `open fence mid-line code not clamped`() {
        val text = "```kotlin\nval x = "
        assertEquals(text, streamingDisplayText(text))
    }

    @Test
    fun `closed code fence preserves trailing prose`() {
        val input = "```kt\nval x = 1\n```\n\nNow tex"
        assertEquals(input, streamingDisplayText(input))
    }

    @Test
    fun `progressive code fence open content close`() {
        assertEquals("Intro\n\n```kt", streamingDisplayText("Intro\n\n```kt"))
        val step2 = streamingDisplayText("Intro\n\n```kt\nval x = 1\nval y")
        assertEquals("Intro\n\n```kt\nval x = 1\nval y", step2)
        val step3 = streamingDisplayText("Intro\n\n```kt\nval x = 1\n```\n\nNext paragrap")
        assertTrue(step3.contains("```kt\nval x = 1\n```"))
        assertTrue(step3.contains("Next paragrap"))
    }

    // ── Display math fence transitions ──

    @Test
    fun `inside open display math raw passes through`() {
        val text = "Here:\n\n\$\$x^2 + "
        assertEquals(text, streamingDisplayText(text))
    }

    @Test
    fun `closed display math preserves trailing prose`() {
        val input = "Here:\n\n\$\$x^2+y^2=z^2\$\$\n\nConclusion rea"
        assertEquals(input, streamingDisplayText(input))
    }

    @Test
    fun `progressive display math open content close`() {
        assertEquals("Look:\n\n\$\$x", streamingDisplayText("Look:\n\n\$\$x"))
        val step2 = streamingDisplayText("Look:\n\n\$\$x^2+y^2")
        assertEquals("Look:\n\n\$\$x^2+y^2", step2)
        val step3 = streamingDisplayText("Look:\n\n\$\$x^2+y^2=z^2\$\$\n\nNow tex")
        assertTrue(step3.contains("\$\$x^2+y^2=z^2\$\$"))
    }

    // ═══ clampToWordBoundary ═══

    @Test
    fun `clampToWordBoundary empty`() {
        assertEquals("", clampToWordBoundary(""))
    }

    @Test
    fun `clampToWordBoundary ends at boundary`() {
        assertEquals("Hello.", clampToWordBoundary("Hello."))
        assertEquals("Hello ", clampToWordBoundary("Hello "))
    }

    @Test
    fun `clampToWordBoundary clips partial word`() {
        assertEquals("Hello ", clampToWordBoundary("Hello wo"))
    }

    @Test
    fun `clampToWordBoundary no boundary holds all`() {
        assertEquals("abcdef", clampToWordBoundary("abcdef"))
    }

    @Test
    fun `clampToWordBoundary long tail beyond max emits anyway`() {
        val long = "a".repeat(MAX_HELD_TAIL_CHARS + 5)
        val input = "Hello $long"
        assertEquals(input, clampToWordBoundary(input))
    }

    @Test
    fun `clampToWordBoundary tail at max is held`() {
        val tail = "a".repeat(MAX_HELD_TAIL_CHARS)
        assertEquals("Hello ", clampToWordBoundary("Hello $tail"))
    }

    // ═══ clampToStableMarkdown ═══

    @Test
    fun `clampToStableMarkdown balanced returns full`() {
        assertEquals("Hello world", clampToStableMarkdown("Hello world"))
    }

    @Test
    fun `clampToStableMarkdown unmatched star-star clips before`() {
        assertEquals("Hello ", clampToStableMarkdown("Hello **wor"))
    }

    @Test
    fun `clampToStableMarkdown unmatched star clips before`() {
        assertEquals("Hello ", clampToStableMarkdown("Hello *wor"))
    }

    @Test
    fun `clampToStableMarkdown unmatched underscore clips before`() {
        assertEquals("Hello ", clampToStableMarkdown("Hello _wor"))
    }

    @Test
    fun `clampToStableMarkdown unmatched double-underscore clips before`() {
        assertEquals("Hello ", clampToStableMarkdown("Hello __wor"))
    }

    @Test
    fun `clampToStableMarkdown unmatched backtick clips before`() {
        assertEquals("Run ", clampToStableMarkdown("Run `ls /tmp/so"))
    }

    @Test
    fun `clampToStableMarkdown unmatched strikethrough clips before`() {
        assertEquals("This is ", clampToStableMarkdown("This is ~~wr"))
    }

    @Test
    fun `clampToStableMarkdown unmatched bracket clips before`() {
        assertEquals("See ", clampToStableMarkdown("See [the docs"))
    }

    @Test
    fun `clampToStableMarkdown bracket closed no paren clips before bracket`() {
        assertEquals("See ", clampToStableMarkdown("See [the docs] f"))
    }

    @Test
    fun `clampToStableMarkdown bracket with paren no close-paren clips before bracket`() {
        assertEquals("See ", clampToStableMarkdown("See [docs](https://ex"))
    }

    @Test
    fun `clampToStableMarkdown balanced bold returns full`() {
        assertEquals("**bold** here", clampToStableMarkdown("**bold** here"))
    }

    @Test
    fun `clampToStableMarkdown balanced backtick returns full`() {
        assertEquals("Run `ls` now", clampToStableMarkdown("Run `ls` now"))
    }

    @Test
    fun `clampToStableMarkdown balanced link returns full`() {
        assertEquals("See [d](https://x.com)", clampToStableMarkdown("See [d](https://x.com)"))
    }

    @Test
    fun `clampToStableMarkdown balanced strikethrough returns full`() {
        assertEquals("~~done~~ here", clampToStableMarkdown("~~done~~ here"))
    }

    @Test
    fun `clampToStableMarkdown multiple unmatched earliest wins`() {
        assertEquals("a ", clampToStableMarkdown("a *b `c"))
    }

    @Test
    fun `clampToStableMarkdown mixed underscore and star`() {
        assertEquals("__bold__ and ", clampToStableMarkdown("__bold__ and *italic text "))
    }

    @Test
    fun `clampToStableMarkdown text ending at newline returns full`() {
        assertEquals("Hello\n", clampToStableMarkdown("Hello\n"))
    }

    @Test
    fun `clampToStableMarkdown multi-line only last line scanned`() {
        val result = clampToStableMarkdown("**done** here\n**open")
        assertEquals("**done** here\n", result)
    }

    // ═══ findUnmatchedOpenerInLine ═══

    @Test
    fun `findUnmatchedOpenerInLine balanced returns -1`() {
        assertEquals(-1, openerScanIndex("**bold**"))
        assertEquals(-1, openerScanIndex("*italic*"))
        assertEquals(-1, openerScanIndex("`code`"))
        assertEquals(-1, openerScanIndex("~~strike~~"))
    }

    @Test
    fun `findUnmatchedOpenerInLine unmatched double-star`() {
        assertEquals(6, openerScanIndex("Hello **world"))
    }

    @Test
    fun `findUnmatchedOpenerInLine unmatched single-star`() {
        assertEquals(6, openerScanIndex("Hello *world"))
    }

    @Test
    fun `findUnmatchedOpenerInLine unmatched backtick`() {
        assertEquals(4, openerScanIndex("Run `command"))
    }

    @Test
    fun `findUnmatchedOpenerInLine unmatched tilde-tilde`() {
        assertEquals(5, openerScanIndex("This ~~text"))
    }

    @Test
    fun `findUnmatchedOpenerInLine unmatched bracket`() {
        assertEquals(4, openerScanIndex("See [link"))
    }

    @Test
    fun `findUnmatchedOpenerInLine unmatched underscore`() {
        assertEquals(6, openerScanIndex("Hello _world"))
    }

    @Test
    fun `findUnmatchedOpenerInLine multiple unmatched returns earliest`() {
        assertEquals(2, openerScanIndex("a *b `c"))
    }

    // ═══ insideOpenCodeFence ═══

    @Test
    fun `insideOpenCodeFence no fences returns false`() {
        assertFalse(insideOpenCodeFence("plain text"))
    }

    @Test
    fun `insideOpenCodeFence one closed pair returns false`() {
        assertFalse(insideOpenCodeFence("```\ncode\n```"))
    }

    @Test
    fun `insideOpenCodeFence one open fence returns true`() {
        assertTrue(insideOpenCodeFence("```kotlin\nval x = 1"))
    }

    @Test
    fun `insideOpenCodeFence three fences one pair plus open returns true`() {
        assertTrue(insideOpenCodeFence("```\nblock1\n```\n```\nblock2"))
    }

    @Test
    fun `insideOpenCodeFence two full pairs returns false`() {
        assertFalse(insideOpenCodeFence("```a\n```\n```b\n```"))
    }

    @Test
    fun `insideOpenCodeFence empty returns false`() {
        assertFalse(insideOpenCodeFence(""))
    }

    // ═══ hasOpenDisplayMathFence ═══

    @Test
    fun `hasOpenDisplayMathFence no math returns false`() {
        assertFalse(hasOpenDisplayMathFence("plain text"))
    }

    @Test
    fun `hasOpenDisplayMathFence closed math returns false`() {
        assertFalse(hasOpenDisplayMathFence("\$\$x = 1\$\$"))
    }

    @Test
    fun `hasOpenDisplayMathFence open math returns true`() {
        assertTrue(hasOpenDisplayMathFence("\$\$x = "))
    }

    @Test
    fun `hasOpenDisplayMathFence empty returns false`() {
        assertFalse(hasOpenDisplayMathFence(""))
    }

    // ═══ isStreamingBoundary ═══

    @Test
    fun `isStreamingBoundary whitespace`() {
        assertTrue(' '.isStreamingBoundary())
        assertTrue('\n'.isStreamingBoundary())
    }

    @Test
    fun `isStreamingBoundary punctuation`() {
        assertTrue('.'.isStreamingBoundary())
        assertTrue(','.isStreamingBoundary())
        assertTrue('!'.isStreamingBoundary())
        assertTrue('?'.isStreamingBoundary())
        assertTrue(':'.isStreamingBoundary())
        assertTrue(')'.isStreamingBoundary())
        assertTrue(']'.isStreamingBoundary())
    }

    @Test
    fun `isStreamingBoundary letters not boundary`() {
        assertFalse('a'.isStreamingBoundary())
        assertFalse('Z'.isStreamingBoundary())
        assertFalse('0'.isStreamingBoundary())
    }

    // ═══ Prefix-stability contract tests ═══

    /** CONTRACT 1: No text is ever dropped. */
    @Test
    fun `contract-1 no text dropped open bold tail passes through`() {
        val input = "Hello **wor"
        val output = streamingDisplayText(input)
        assertEquals(input, output)
        assertEquals(11, output.length)
    }

    /** CONTRACT 2: Earlier prefixes stable across progressive arrival. */
    @Test
    fun `contract-2 progressive arrival keeps earlier prefixes stable`() {
        assertEquals("Hello ", streamingDisplayText("Hello "))
        val step2 = streamingDisplayText("Hello **wor")
        assertEquals("Hello **wor", step2)
        assertTrue(step2.startsWith("Hello "))
        val step3 = streamingDisplayText("Hello **world** and more text that is")
        assertTrue(step3.startsWith("Hello **world** and more text that "))
    }

    /** CONTRACT 3: Every incomplete markup construct passes through. */
    @Test
    fun `contract-3 all incomplete markup types pass through`() {
        val cases = listOf(
            "Hello **wor", "Hello *wor", "Hello _wor", "Hello __wor",
            "Run `com", "This ~~wr", "See [the", "Hello ***wor",
        )
        for (input in cases) {
            assertEquals("case: '$input'", input, streamingDisplayText(input))
        }
    }

    /** CONTRACT 4: Fence transitions are clean. */
    @Test
    fun `contract-4 fence transitions clean`() {
        assertEquals("```kt\nval x", streamingDisplayText("```kt\nval x"))
        val closed = streamingDisplayText("```kt\nval x = 1\n```\n\nProse her")
        assertEquals("```kt\nval x = 1\n```\n\nProse her", closed)
        assertEquals("\$\$x^2", streamingDisplayText("\$\$x^2"))
        val closedMath = streamingDisplayText("\$\$x=1\$\$\n\nFinal wor")
        assertEquals("\$\$x=1\$\$\n\nFinal wor", closedMath)
    }

    /** CONTRACT 5: Plain prose renders every received character. */
    @Test
    fun `contract-5 plain prose preserves partial words and acronyms`() {
        assertEquals("Hello wor", streamingDisplayText("Hello wor"))
        assertEquals("#639 A2UI", streamingDisplayText("#639 A2UI"))
        assertEquals("Jules queue", streamingDisplayText("Jules queue"))
    }
}

private fun openerScanIndex(line: String): Int =
    findUnmatchedOpenerInLine(MarkdownOpenerScanLine(line))
