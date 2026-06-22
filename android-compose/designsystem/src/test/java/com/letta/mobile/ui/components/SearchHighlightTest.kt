package com.letta.mobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class SearchHighlightTest {

    @Test
    fun `highlights all case-insensitive matches`() {
        val result = highlightSearchMatches(
            text = "Needle and needle",
            query = "needle",
            highlightColor = Color.Yellow,
            matchTextColor = Color.Black,
        )

        assertEquals("Needle and needle", result.text)
        assertEquals(2, result.spanStyles.size)
        assertEquals(0, result.spanStyles[0].start)
        assertEquals(6, result.spanStyles[0].end)
        assertEquals(11, result.spanStyles[1].start)
        assertEquals(17, result.spanStyles[1].end)
        assertEquals(Color.Yellow, result.spanStyles[0].item.background)
        assertEquals(Color.Black, result.spanStyles[0].item.color)
        assertEquals(FontWeight.Bold, result.spanStyles[0].item.fontWeight)
    }

    @Test
    fun `blank query returns unstyled text`() {
        val result = highlightSearchMatches(
            text = "No highlighting",
            query = "   ",
            highlightColor = Color.Yellow,
        )

        assertEquals("No highlighting", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `snippet keeps first exact match in view`() {
        val source = "prefix ".repeat(40) + "needle" + " suffix".repeat(40)

        val snippet = searchResultSnippet(source, "needle", contextChars = 16)

        assertTrue(snippet.startsWith("..."))
        assertTrue(snippet.contains("needle"))
        assertTrue(snippet.endsWith("..."))
        assertTrue(snippet.length < source.length)
    }

    @Test
    fun `preserves original casing while highlighting`() {
        val result = highlightSearchMatches(
            text = "Search For THIS text",
            query = "for this",
            highlightColor = Color.Cyan,
            matchTextColor = Color.Blue,
        )

        assertEquals("Search For THIS text", result.text)
        assertEquals(1, result.spanStyles.size)
        assertEquals(7, result.spanStyles[0].start)
        assertEquals(15, result.spanStyles[0].end) // "For THIS" length is 8
        assertEquals(Color.Cyan, result.spanStyles[0].item.background)
        assertEquals(Color.Blue, result.spanStyles[0].item.color)
    }

}