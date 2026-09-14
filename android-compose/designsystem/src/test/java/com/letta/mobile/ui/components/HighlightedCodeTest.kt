package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.stream.Collectors

class HighlightedCodeTest {
    private fun kotlin(code: String) = HighlightRequest(code, "kotlin", darkTheme = true)

    @Test
    fun streamingAppendKeepsEveryPreviousSpan() {
        val before = "fun a() {\n    val x = 1\n"
        val spans = computeCodeSpans(kotlin(before))
        assertTrue("the highlighter styles kotlin keywords", spans.isNotEmpty())

        val after = before + "    val y = 2\n}"
        val carried = carrySpans(HighlightedRevision(before, spans), after)

        assertEquals(spans, carried)
    }

    @Test
    fun aRewriteKeepsOnlyTheUnchangedPrefix() {
        val before = "val a = 1\nval b = 2\n"
        val spans = computeCodeSpans(kotlin(before))
        val after = "val a = 1\nvar c = 3\n"

        val carried = carrySpans(HighlightedRevision(before, spans), after)

        val prefix = "val a = 1\n".length
        assertTrue(carried.isNotEmpty())
        assertTrue(carried.all { it.end <= prefix })
    }

    @Test
    fun annotateDropsSpansPastTheEndOfTheText() {
        val spans = listOf(CodeSpan(0, 3, 0xFF0000, bold = false), CodeSpan(2, 40, null, bold = true))
        val annotated = annotate(HighlightedRevision("val", spans))
        assertEquals("val", annotated.text)
        assertEquals(1, annotated.spanStyles.size)
    }

    @Test
    fun cacheReturnsAFinishedHighlightForTheSameCodeAndTheme() {
        HighlightedCodeCache.clearForTest()
        val key = HighlightRequest("print(1)", "python", true)
        assertEquals(null, HighlightedCodeCache.get(key))
        val spans = computeCodeSpans(key)
        HighlightedCodeCache.put(key, spans)
        assertEquals(spans, HighlightedCodeCache.get(key))
        assertEquals(null, HighlightedCodeCache.get(key.copy(darkTheme = false)))
    }

    @Test
    fun concurrentComputesOfDifferentFencesDoNotCrossTalk() {
        val requests = (0 until 16).map { i ->
            if (i % 2 == 0) kotlin("val v$i = $i\n") else HighlightRequest("def f$i(): return $i\n", "python", false)
        }
        val expected = requests.map { computeCodeSpans(it) }
        val actual = requests.parallelStream().map { computeCodeSpans(it) }.collect(Collectors.toList())
        assertEquals(expected, actual)
    }
}
