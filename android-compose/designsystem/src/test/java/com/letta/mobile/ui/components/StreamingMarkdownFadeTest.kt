package com.letta.mobile.ui.components

import com.letta.mobile.ui.markdown.StreamingMarkdownBlockKind
import com.letta.mobile.ui.markdown.StreamingMarkdownDocumentBlock
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class StreamingMarkdownFadeTest {

    @Test
    fun `strict prefix extension calculates appended delta range correctly`() {
        val fadeState = StreamingAppendedDeltaFadeState()

        val updated1 = fadeState.update(
            activeBlockId = 1L,
            activeSource = "Hello ",
            isStreaming = true,
            isReducedMotion = false,
            isEligibleBlockKind = true,
        )
        assertTrue(updated1)
        assertEquals(0 until 6, fadeState.fadingDeltaRange)

        val updated2 = fadeState.update(
            activeBlockId = 1L,
            activeSource = "Hello world",
            isStreaming = true,
            isReducedMotion = false,
            isEligibleBlockKind = true,
        )
        assertTrue(updated2)
        assertEquals(6 until 11, fadeState.fadingDeltaRange)
    }

    @Test
    fun `replacement resets fade range and returns false`() {
        val fadeState = StreamingAppendedDeltaFadeState()

        fadeState.update(
            activeBlockId = 1L,
            activeSource = "Hello world",
            isStreaming = true,
            isReducedMotion = false,
            isEligibleBlockKind = true,
        )

        val replaced = fadeState.update(
            activeBlockId = 1L,
            activeSource = "Goodbye world",
            isStreaming = true,
            isReducedMotion = false,
            isEligibleBlockKind = true,
        )
        assertFalse(replaced)
        assertNull(fadeState.fadingDeltaRange)
    }

    @Test
    fun `ineligible block kinds do not fade`() {
        val codeBlock = StreamingMarkdownDocumentBlock(
            id = 1L,
            kind = StreamingMarkdownBlockKind.CodeFence,
            source = "```kotlin\nval x = 1\n```",
            startOffset = 0,
            closed = true,
        )
        val tableBlock = StreamingMarkdownDocumentBlock(
            id = 2L,
            kind = StreamingMarkdownBlockKind.Table,
            source = "| A | B |",
            startOffset = 0,
            closed = false,
        )
        val mathBlock = StreamingMarkdownDocumentBlock(
            id = 3L,
            kind = StreamingMarkdownBlockKind.DisplayMath,
            source = "\$\$x^2\$\$",
            startOffset = 0,
            closed = true,
        )
        val paragraphBlock = StreamingMarkdownDocumentBlock(
            id = 4L,
            kind = StreamingMarkdownBlockKind.Paragraph,
            source = "Paragraph",
            startOffset = 0,
            closed = true,
        )

        assertFalse(codeBlock.supportsAppendedDeltaFade())
        assertFalse(tableBlock.supportsAppendedDeltaFade())
        assertFalse(mathBlock.supportsAppendedDeltaFade())
        assertTrue(paragraphBlock.supportsAppendedDeltaFade())
    }

    @Test
    fun `reduced motion and non-streaming disable appended fade`() {
        val fadeState = StreamingAppendedDeltaFadeState()

        val reduced = fadeState.update(
            activeBlockId = 1L,
            activeSource = "Hello ",
            isStreaming = true,
            isReducedMotion = true,
            isEligibleBlockKind = true,
        )
        assertFalse(reduced)
        assertNull(fadeState.fadingDeltaRange)

        val nonStreaming = fadeState.update(
            activeBlockId = 1L,
            activeSource = "Hello ",
            isStreaming = false,
            isReducedMotion = false,
            isEligibleBlockKind = true,
        )
        assertFalse(nonStreaming)
        assertNull(fadeState.fadingDeltaRange)
    }

    @Test
    fun `unicode surrogate boundary checks prevent splitting surrogate pairs`() {
        val text = "Hello 😀 world"
        // Index 7 lands between the high surrogate \uD83D and low surrogate \uDE00 of 😀
        assertFalse(text.isUnicodeSafeBoundary(7))
        assertTrue(text.isUnicodeSafeBoundary(6))
        assertTrue(text.isUnicodeSafeBoundary(8))

        assertEquals(6, findSafeUnicodeBoundary(text, 7))
        assertEquals(6, findSafeUnicodeBoundary(text, 6))
        assertEquals(8, findSafeUnicodeBoundary(text, 8))
    }

    @Test
    fun `buildFadingAnnotatedString applies span style to delta range only`() {
        val text = "Hello world"
        val annotated = buildFadingAnnotatedString(
            text = text,
            textColor = Color.Black,
            deltaStart = 6,
            deltaEnd = 11,
            animAlpha = 0.5f,
        )

        assertEquals("Hello world", annotated.text)
        assertEquals(1, annotated.spanStyles.size)
        val span = annotated.spanStyles.single()
        assertEquals(6, span.start)
        assertEquals(11, span.end)
        assertEquals(Color.Black.copy(alpha = 0.5f), span.item.color)
    }

    @Test
    fun `buildFadingAnnotatedString produces plain string when alpha is 1`() {
        val text = "Hello world"
        val annotated = buildFadingAnnotatedString(
            text = text,
            textColor = Color.Black,
            deltaStart = 6,
            deltaEnd = 11,
            animAlpha = 1.0f,
        )

        assertEquals("Hello world", annotated.text)
        assertTrue(annotated.spanStyles.isEmpty())
    }
}
