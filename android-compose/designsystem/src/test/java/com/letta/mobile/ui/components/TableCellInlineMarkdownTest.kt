package com.letta.mobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class TableCellInlineMarkdownTest {

    @Test
    fun `table cell parser renders lightweight inline markdown spans`() {
        val parsed = buildTableCellAnnotatedString(
            "**Bold** _italic_ `code` ~~gone~~ [docs](https://example.com)",
            inlineCodeBackground = Color.Yellow,
        )

        assertEquals("Bold italic code gone docs", parsed.text)
        assertEquals(FontWeight.Bold, parsed.spanFor("Bold").fontWeight)
        assertEquals(FontStyle.Italic, parsed.spanFor("italic").fontStyle)
        assertEquals(Color.Yellow, parsed.spanFor("code").background)
        assertEquals(TextDecoration.LineThrough, parsed.spanFor("gone").textDecoration)
        assertEquals(TextDecoration.Underline, parsed.spanFor("docs").textDecoration)
    }

    @Test
    fun `table cell parser leaves common literal marker characters alone`() {
        val parsed = buildTableCellAnnotatedString("user_email 1*2 [3] path~name")

        assertEquals("user_email 1*2 [3] path~name", parsed.text)
        assertTrue(parsed.spanStyles.isEmpty())
    }

    @Test
    fun `table cell parser keeps escaped inline markers literal`() {
        val parsed = buildTableCellAnnotatedString("\\*literal\\* and \\`code\\`")

        assertEquals("*literal* and `code`", parsed.text)
        assertTrue(parsed.spanStyles.isEmpty())
    }

    @Test
    fun `table cell parser does not drop characters for consecutive and adjacent tags`() {
        val parsed1 = buildTableCellAnnotatedString("before **bold** `code` after")
        assertEquals("before bold code after", parsed1.text)

        val parsed2 = buildTableCellAnnotatedString("**bold**`code`")
        assertEquals("boldcode", parsed2.text)

        val parsed3 = buildTableCellAnnotatedString("text **bold**and`code`")
        assertEquals("text boldandcode", parsed3.text)

        val parsed4 = buildTableCellAnnotatedString("**bold**_italic_`code`~~gone~~[docs](https://example.com)")
        assertEquals("bolditaliccodegonedocs", parsed4.text)

        val parsed5 = buildTableCellAnnotatedString("one **two** three `four` five")
        assertEquals("one two three four five", parsed5.text)
    }

    private fun AnnotatedString.spanFor(text: String): SpanStyle {
        val start = this.text.indexOf(text)
        val end = start + text.length
        require(start >= 0) { "Missing text segment: $text" }
        return spanStyles.single { it.start == start && it.end == end }.item
    }
}
