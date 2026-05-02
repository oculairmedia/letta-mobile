package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Tests for [findLastSafeBoundary] — the boundary detector that
 * [StreamingMarkdownText] uses to split incoming streaming content into
 * a fully-rendered markdown prefix and an in-progress plain-text tail.
 *
 * Coverage matrix mirrors the one captured on letta-mobile-c8of.5.
 */
@Tag("unit")
class StreamingMarkdownBoundaryTest {

    @Test
    fun `empty input returns 0`() {
        assertEquals(0, findLastSafeBoundary(""))
    }

    @Test
    fun `single character returns 0`() {
        assertEquals(0, findLastSafeBoundary("H"))
    }

    @Test
    fun `single paragraph with no break returns 0`() {
        assertEquals(0, findLastSafeBoundary("Hello world this is one paragraph"))
    }

    @Test
    fun `single trailing newline does not commit a boundary`() {
        assertEquals(0, findLastSafeBoundary("Hello world\n"))
    }

    @Test
    fun `paragraph break commits a boundary immediately after the second newline`() {
        // "Hello\n\nWorld" — boundary should be at index 7, the W
        val text = "Hello\n\nWorld"
        assertEquals(7, findLastSafeBoundary(text))
        assertEquals("Hello\n\n", text.substring(0, 7))
        assertEquals("World", text.substring(7))
    }

    @Test
    fun `multiple paragraph breaks return the last one`() {
        // "Hello\n\nWorld\n\nFoo" — boundary should be at "Foo"
        val text = "Hello\n\nWorld\n\nFoo"
        val b = findLastSafeBoundary(text)
        assertEquals("Foo", text.substring(b))
    }

    @Test
    fun `trailing paragraph break (text ends with double newline) commits boundary at end`() {
        // "Hello\n\nWorld\n\n" — boundary at end (tail is empty)
        val text = "Hello\n\nWorld\n\n"
        val b = findLastSafeBoundary(text)
        assertEquals(text.length, b)
        assertEquals("", text.substring(b))
    }

    @Test
    fun `extra blank lines collapse into the boundary`() {
        // "Hello\n\n\n\nWorld" — boundary should still be just before "World"
        val text = "Hello\n\n\n\nWorld"
        val b = findLastSafeBoundary(text)
        assertEquals("World", text.substring(b))
    }

    @Test
    fun `unclosed code fence prevents boundary commit after the fence opens`() {
        // "Intro\n\n```kotlin\nfun foo" — only safe boundary is after "Intro\n\n",
        // NOT after the (would-be) paragraph break inside the fence
        val text = "Intro\n\n```kotlin\nfun foo"
        val b = findLastSafeBoundary(text)
        assertEquals("```kotlin\nfun foo", text.substring(b))
    }

    @Test
    fun `closed code fence allows boundary after the fence`() {
        // "Intro\n\n```kotlin\nfun foo()\n```\n\nOutro" — boundary just before "Outro"
        val text = "Intro\n\n```kotlin\nfun foo()\n```\n\nOutro"
        val b = findLastSafeBoundary(text)
        assertEquals("Outro", text.substring(b))
    }

    @Test
    fun `paragraph break INSIDE an open fence is NOT a safe boundary`() {
        // Inside a fence, a \n\n could be code formatting (e.g. a blank line
        // in a Python file). Splitting here would treat the prose after the
        // fence-eventually-closes as if it were a paragraph in the prefix.
        val text = "Intro\n\n```\nline 1\n\nline 2"
        val b = findLastSafeBoundary(text)
        // The only safe boundary is after the initial "Intro\n\n"
        assertEquals("```\nline 1\n\nline 2", text.substring(b))
    }

    @Test
    fun `multiple closed fences in sequence each allow boundaries`() {
        val text = "A\n\n```\nfoo\n```\n\nB\n\n```\nbar\n```\n\nC"
        val b = findLastSafeBoundary(text)
        assertEquals("C", text.substring(b))
    }

    @Test
    fun `closed display math allows boundary after`() {
        val text = "Look:\n\n\$\$x^2 + y^2 = z^2\$\$\n\nOk"
        val b = findLastSafeBoundary(text)
        assertEquals("Ok", text.substring(b))
    }

    @Test
    fun `unclosed display math prevents boundary after the math opens`() {
        val text = "Look:\n\n\$\$x^2 + y^2"
        val b = findLastSafeBoundary(text)
        assertEquals("\$\$x^2 + y^2", text.substring(b))
    }

    @Test
    fun `paragraph break BEFORE an open fence is still a safe boundary`() {
        val text = "Intro\n\n```\nfoo"
        val b = findLastSafeBoundary(text)
        // Boundary at index 7 (after "Intro\n\n"), tail starts with the fence
        assertEquals(7, b)
        assertEquals("```\nfoo", text.substring(b))
    }

    @Test
    fun `inline emphasis open mid-tail does not affect boundary`() {
        // "**hello" mid-tail is fine — the boundary detector ignores
        // inline constructs. The previous paragraph still commits.
        val text = "Done.\n\n**hello world wit"
        val b = findLastSafeBoundary(text)
        assertEquals("**hello world wit", text.substring(b))
    }

    // ─── letta-mobile-c8of.6 — table-close detector ─────────────────────

    @Test
    fun `complete table followed by prose commits boundary right before the prose`() {
        // After the table row \"| 3 | 4 |\\n\" the next line is prose with
        // no pipe — table has structurally ended, boundary lives at the
        // start of the prose line.
        val text = "Intro\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |\nNext paragraph."
        val b = findLastSafeBoundary(text)
        assertEquals("Next paragraph.", text.substring(b))
    }

    @Test
    fun `table followed by paragraph break still commits at paragraph break`() {
        // Both the table-close AND the paragraph break would fire here;
        // because we walk linearly and the paragraph-break boundary
        // lands later in the text, it wins. Tail is the paragraph after.
        val text = "| a | b |\n| --- | --- |\n| 1 | 2 |\n\nAfter table"
        val b = findLastSafeBoundary(text)
        assertEquals("After table", text.substring(b))
    }

    @Test
    fun `in-progress table commits at start of the partial in-flight row`() {
        // [letta-mobile-c8of.6] PROGRESSIVE rendering: as rows complete,
        // boundary advances row-by-row. The partial in-flight row "| 1 | 2 "
        // (no terminating \n yet) sits in the tail; everything before
        // (intro + header + separator) goes to the prefix. mikepenz will
        // render the prefix as a header-only table during the brief
        // moment between separator landing and the first row terminating.
        val text = "Intro paragraph.\n\n| a | b |\n| --- | --- |\n| 1 | 2 "
        val b = findLastSafeBoundary(text)
        // Boundary advances past the separator line — the partial first
        // data row stays in the tail.
        assertEquals("| 1 | 2 ", text.substring(b))
    }

    @Test
    fun `progressive table renders row by row as each newline lands`() {
        // The whole point of c8of.6: as each row \n arrives, that row
        // joins the prefix. Caller renders the prefix as MarkdownText
        // (a real table grid), so the user sees rows append.
        val full = "| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |\n| 5 | 6 |\n"
        // After the FIRST data row's terminating \n, boundary moves to
        // the start of "| 3 | 4 |" line.
        val afterRow1 = "| a | b |\n| --- | --- |\n| 1 | 2 |\n"
        assertEquals(afterRow1.length, findLastSafeBoundary(afterRow1))
        // After three data rows, boundary at end of last completed row.
        assertEquals(full.length, findLastSafeBoundary(full))
    }

    @Test
    fun `prose containing literal pipes does NOT trigger table boundary`() {
        // "yes | no" prose has pipes but no separator row — should NOT
        // match the table-close detector.
        val text = "Choose: yes | no | maybe\nSecond line of prose"
        val b = findLastSafeBoundary(text)
        // No safe boundary — there's no \n\n and no table-close pattern.
        assertEquals(0, b)
    }

    @Test
    fun `two-row run without separator does NOT match (rows alone are not enough)`() {
        // Prose with pipes spanning two lines but no `| --- |` separator
        // row — must not trigger.
        val text = "| not | a |\n| table | row |\nProse follows here."
        val b = findLastSafeBoundary(text)
        // No table-close, no \n\n → no boundary.
        assertEquals(0, b)
    }

    @Test
    fun `table with alignment colons in separator still matches`() {
        val text = "| left | right | center |\n| :--- | ---: | :---: |\n| 1 | 2 | 3 |\nDone."
        val b = findLastSafeBoundary(text)
        assertEquals("Done.", text.substring(b))
    }

    @Test
    fun `table with no leading or trailing pipes still matches`() {
        // GFM allows borderless tables.
        val text = "a | b\n--- | ---\n1 | 2\nAfter."
        val b = findLastSafeBoundary(text)
        assertEquals("After.", text.substring(b))
    }

    @Test
    fun `table inside a code fence does NOT trigger`() {
        // Pipes inside an open fence are code, not table syntax. The
        // boundary tracker must ignore line-level signals while a fence
        // is open.
        val text = "Intro\n\n```\n| a | b |\n| --- | --- |\n| 1 | 2 |\nplain"
        val b = findLastSafeBoundary(text)
        // Only safe boundary is after the intro paragraph.
        assertEquals("```\n| a | b |\n| --- | --- |\n| 1 | 2 |\nplain", text.substring(b))
    }

    @Test
    fun `progressive table stream - rows commit one-by-one as they complete`() {
        // [letta-mobile-c8of.6] core acceptance test: each row \n
        // advances the boundary, so the prefix grows one row at a time
        // and renders progressively as a real table grid. The user sees
        // rows appear, not raw `| a | b |` syntax.
        val chunks = listOf(
            "| a | b |\n",                                                    // -> 0 (header alone, no separator yet)
            "| a | b |\n| --- | --- |\n",                                     // -> 0 (separator alone, no data row yet)
            "| a | b |\n| --- | --- |\n| 1 | 2 |\n",                          // -> 34 (1st data row committed)
            "| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |\n",                // -> 44 (2nd data row committed)
            "| a | b |\n| --- | --- |\n| 1 | 2 |\nAfter",                     // -> 34 (table closed by prose)
        )
        val expected = listOf(0, 0, 34, 44, 34)
        for (i in chunks.indices) {
            assertEquals(
                "chunk ${i}: '${chunks[i].replace("\n", "\\n")}'",
                expected[i],
                findLastSafeBoundary(chunks[i]),
            )
        }
        // Sanity-check the substring at the boundary for the final chunk.
        val last = chunks.last()
        assertEquals("After", last.substring(34))
    }

    @Test
    fun `single dashed line is treated as thematic break NOT a one-cell table`() {
        // "---\n" is an HR in markdown; we must NOT mistake it for a
        // single-cell table separator.
        val text = "para\n---\nmore"
        val b = findLastSafeBoundary(text)
        assertEquals(0, b)
    }

    // ─── original progressive-stream simulation ─────────────────────────

    @Test
    fun `progressive stream simulation - boundary advances as paragraphs land`() {
        // Simulate chunked arrival; verify boundary moves forward only when
        // a new paragraph break arrives.
        val chunks = listOf(
            "Hello",                  // -> 0
            "Hello world",            // -> 0
            "Hello world.\n",         // -> 0 (single newline insufficient)
            "Hello world.\n\n",       // -> 14 (boundary at end, tail empty)
            "Hello world.\n\nNext",   // -> 14 (tail = "Next")
            "Hello world.\n\nNext paragraph here.\n\nThird", // -> 36, tail = "Third"
        )
        val expected = listOf(0, 0, 0, 14, 14, 36)
        for (i in chunks.indices) {
            assertEquals(
                "chunk ${i}: '${chunks[i].replace("\n", "\\n")}'",
                expected[i],
                findLastSafeBoundary(chunks[i]),
            )
        }
    }

    // ─── letta-mobile-flk.3: tailHasOpenBlockFence ─────────────────────
    //
    // Backstop tests for the fence-detector that gates whether the
    // streaming tail can be rendered through MarkdownText (false → safe
    // to render markdown live) or must fall back to plain Text (true →
    // unclosed fence would absorb the rest of the bubble).

    @Test
    fun `tailHasOpenBlockFence returns false for empty tail`() {
        assertEquals(false, tailHasOpenBlockFence(""))
    }

    @Test
    fun `tailHasOpenBlockFence returns false for plain prose`() {
        assertEquals(false, tailHasOpenBlockFence("just some streaming prose"))
    }

    @Test
    fun `tailHasOpenBlockFence returns false for inline emphasis only`() {
        // **bold and *italic* and `code` are inline — not block fences.
        assertEquals(false, tailHasOpenBlockFence("**bold** *ital* `code`"))
    }

    @Test
    fun `tailHasOpenBlockFence returns false for partial inline emphasis`() {
        // Half-typed bold marker is fine — mikepenz tolerates it.
        assertEquals(false, tailHasOpenBlockFence("**hel"))
    }

    @Test
    fun `tailHasOpenBlockFence returns true for unclosed code fence`() {
        assertEquals(true, tailHasOpenBlockFence("```kotlin\nfun foo()"))
    }

    @Test
    fun `tailHasOpenBlockFence returns false for closed code fence`() {
        assertEquals(false, tailHasOpenBlockFence("```kotlin\nfun foo() = 1\n```"))
    }

    @Test
    fun `tailHasOpenBlockFence returns true for unclosed display math`() {
        assertEquals(true, tailHasOpenBlockFence("\$\$x = "))
    }

    @Test
    fun `tailHasOpenBlockFence returns false for closed display math`() {
        assertEquals(false, tailHasOpenBlockFence("\$\$x = 1\$\$"))
    }

    @Test
    fun `tailHasOpenBlockFence returns false for inline math single dollars`() {
        // Single `$` is inline math — not a block fence.
        assertEquals(false, tailHasOpenBlockFence("price is \$5"))
    }

    @Test
    fun `tailHasOpenBlockFence returns true for one of two fences open`() {
        // First fence closes, second opens but never closes.
        assertEquals(true, tailHasOpenBlockFence("```a\n```\n```b\nopen"))
    }
}
