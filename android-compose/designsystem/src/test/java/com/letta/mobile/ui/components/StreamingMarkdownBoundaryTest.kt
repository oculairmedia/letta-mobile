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
}
