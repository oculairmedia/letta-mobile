package com.letta.mobile.ui.components

import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightedCodeTest {
    private val builder = Highlights.Builder().theme(SyntaxThemes.atom(darkMode = true))

    @Test
    fun streamingAppendKeepsEveryPreviousSpan() {
        val before = "fun a() {\n    val x = 1\n"
        val spans = computeCodeSpans(before, "kotlin", builder)
        assertTrue("the highlighter styles kotlin keywords", spans.isNotEmpty())

        val after = before + "    val y = 2\n}"
        val carried = carrySpans(before, spans, after)

        assertEquals(spans, carried)
    }

    @Test
    fun aRewriteKeepsOnlyTheUnchangedPrefix() {
        val before = "val a = 1\nval b = 2\n"
        val spans = computeCodeSpans(before, "kotlin", builder)
        val after = "val a = 1\nvar c = 3\n"

        val carried = carrySpans(before, spans, after)

        val prefix = "val a = 1\n".length
        assertTrue(carried.isNotEmpty())
        assertTrue(carried.all { it.end <= prefix })
    }

    @Test
    fun annotateDropsSpansPastTheEndOfTheText() {
        val spans = listOf(CodeSpan(0, 3, 0xFF0000, bold = false), CodeSpan(2, 40, null, bold = true))
        val annotated = annotate("val", spans)
        assertEquals("val", annotated.text)
        assertEquals(1, annotated.spanStyles.size)
    }

    @Test
    fun cacheReturnsAFinishedHighlightForTheSameCodeAndTheme() {
        HighlightedCodeCache.clearForTest()
        val key = HighlightCacheKey("print(1)", "python", true)
        assertEquals(null, HighlightedCodeCache.get(key))
        val spans = computeCodeSpans(key.code, key.language, builder)
        HighlightedCodeCache.put(key, spans)
        assertEquals(spans, HighlightedCodeCache.get(key))
        assertEquals(null, HighlightedCodeCache.get(key.copy(darkTheme = false)))
    }
}
