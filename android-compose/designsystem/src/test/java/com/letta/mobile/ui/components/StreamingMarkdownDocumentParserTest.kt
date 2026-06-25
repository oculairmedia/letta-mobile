package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownDocumentParserTest {

    @Test
    fun parse_emptyText_returnsEmptyList() {
        val blocks = StreamingMarkdownDocumentParser.parse("")
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun parse_paragraph() {
        val text = "Hello world\nThis is a test"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(1, blocks.size)
        
        val block = blocks[0]
        assertEquals(StreamingMarkdownBlockKind.Paragraph, block.kind)
        assertEquals(text, block.source)
        assertFalse(block.closed)
    }

    @Test
    fun parse_heading() {
        val text = "# Heading 1\n## Heading 2\n### Heading 3"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(3, blocks.size)
        
        assertEquals(StreamingMarkdownBlockKind.Heading, blocks[0].kind)
        assertEquals("# Heading 1\n", blocks[0].source)
        
        assertEquals(StreamingMarkdownBlockKind.Heading, blocks[1].kind)
        assertEquals("## Heading 2\n", blocks[1].source)
        
        assertEquals(StreamingMarkdownBlockKind.Heading, blocks[2].kind)
        assertEquals("### Heading 3", blocks[2].source)
    }

    @Test
    fun parse_bulletList() {
        val text = "- Item 1\n- Item 2\n  - Subitem 1\n- Item 3"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(1, blocks.size)
        
        val block = blocks[0]
        assertEquals(StreamingMarkdownBlockKind.BulletList, block.kind)
        assertEquals(text, block.source)
        assertTrue(block.closed)
    }

    @Test
    fun parse_bulletList_withBlankLinesAndContinuation() {
        val text = "- Item 1\n\n- Item 2\n\n  Continuation"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(2, blocks.size)
        assertEquals(StreamingMarkdownBlockKind.BulletList, blocks[0].kind)
        assertEquals("- Item 1\n\n- Item 2\n\n", blocks[0].source)
        
        assertEquals(StreamingMarkdownBlockKind.Paragraph, blocks[1].kind)
        assertEquals("  Continuation", blocks[1].source)
    }

    @Test
    fun parse_orderedList() {
        val text = "1. Item 1\n2. Item 2\n3. Item 3"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(1, blocks.size)
        
        val block = blocks[0]
        assertEquals(StreamingMarkdownBlockKind.OrderedList, block.kind)
        assertEquals(text, block.source)
        assertTrue(block.closed)
    }

    @Test
    fun parse_blockquote() {
        val text = "> Quote 1\n> Quote 2"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(1, blocks.size)
        
        val block = blocks[0]
        assertEquals(StreamingMarkdownBlockKind.Blockquote, block.kind)
        assertEquals(text, block.source)
        assertTrue(block.closed)
    }

    @Test
    fun parse_horizontalRule() {
        val text = "---\n***\n___"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(3, blocks.size)
        
        assertEquals(StreamingMarkdownBlockKind.HorizontalRule, blocks[0].kind)
        assertEquals("---\n", blocks[0].source)
        
        assertEquals(StreamingMarkdownBlockKind.HorizontalRule, blocks[1].kind)
        assertEquals("***\n", blocks[1].source)
        
        assertEquals(StreamingMarkdownBlockKind.HorizontalRule, blocks[2].kind)
        assertEquals("___", blocks[2].source)
    }

    @Test
    fun parse_codeFence_closed() {
        val text = "```kotlin\nval x = 1\n```\nNext paragraph"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(2, blocks.size)
        
        val codeBlock = blocks[0]
        assertEquals(StreamingMarkdownBlockKind.CodeFence, codeBlock.kind)
        assertEquals("```kotlin\nval x = 1\n```\n", codeBlock.source)
        assertTrue(codeBlock.closed)
        
        val paragraphBlock = blocks[1]
        assertEquals(StreamingMarkdownBlockKind.Paragraph, paragraphBlock.kind)
        assertEquals("Next paragraph", paragraphBlock.source)
    }

    @Test
    fun parse_codeFence_unclosed() {
        val text = "```kotlin\nval x = 1\n"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(1, blocks.size)
        
        val block = blocks[0]
        assertEquals(StreamingMarkdownBlockKind.CodeFence, block.kind)
        assertEquals(text, block.source)
        assertFalse(block.closed)
    }

    @Test
    fun parse_displayMath_closed_doubleDollar() {
        val text = "$$\nx^2\n$$\nNext"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(2, blocks.size)
        
        val mathBlock = blocks[0]
        assertEquals(StreamingMarkdownBlockKind.DisplayMath, mathBlock.kind)
        assertEquals("$$\nx^2\n$$\n", mathBlock.source)
        assertTrue(mathBlock.closed)
    }

    @Test
    fun parse_displayMath_unclosed_doubleDollar() {
        val text = "$$\nx^2\n"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(1, blocks.size)
        
        val mathBlock = blocks[0]
        assertEquals(StreamingMarkdownBlockKind.DisplayMath, mathBlock.kind)
        assertEquals(text, mathBlock.source)
        assertFalse(mathBlock.closed)
    }

    @Test
    fun parse_displayMath_closed_brackets() {
        val text = "\\[\nx^2\n\\]\nNext"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(2, blocks.size)
        
        val mathBlock = blocks[0]
        assertEquals(StreamingMarkdownBlockKind.DisplayMath, mathBlock.kind)
        assertEquals("\\[\nx^2\n\\]\n", mathBlock.source)
        assertTrue(mathBlock.closed)
    }

    @Test
    fun parse_displayMath_unclosed_brackets() {
        val text = "\\[\nx^2\n"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(1, blocks.size)
        
        val mathBlock = blocks[0]
        assertEquals(StreamingMarkdownBlockKind.DisplayMath, mathBlock.kind)
        assertEquals(text, mathBlock.source)
        assertFalse(mathBlock.closed)
    }

    @Test
    fun parse_displayMath_inlineClose_doubleDollar() {
        val text = "\$\$x^2\$\$\nNext"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(2, blocks.size)
        
        val mathBlock = blocks[0]
        assertEquals(StreamingMarkdownBlockKind.DisplayMath, mathBlock.kind)
        assertEquals("\$\$x^2\$\$\n", mathBlock.source)
        assertTrue(mathBlock.closed)
    }
    
    @Test
    fun parse_displayMath_inlineClose_brackets() {
        val text = "\\[x^2\\]\nNext"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(2, blocks.size)
        
        val mathBlock = blocks[0]
        assertEquals(StreamingMarkdownBlockKind.DisplayMath, mathBlock.kind)
        assertEquals("\\[x^2\\]\n", mathBlock.source)
        assertTrue(mathBlock.closed)
    }

    @Test
    fun parse_table_closed() {
        val text = "| Header 1 | Header 2 |\n| -------- | -------- |\n| Cell 1 | Cell 2 |"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(1, blocks.size)
        
        val block = blocks[0]
        assertEquals(StreamingMarkdownBlockKind.Table, block.kind)
        assertEquals(text, block.source)
        assertTrue(block.closed)
    }

    @Test
    fun parse_table_unclosed() {
        val text = "| Header 1 | Header 2 |\n"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(1, blocks.size)
        val block = blocks[0]
        assertEquals(StreamingMarkdownBlockKind.Paragraph, block.kind)
        assertEquals(text, block.source)
    }

    @Test
    fun parse_paragraph_interruptedByBlock() {
        val text = "Paragraph line 1\n# Interruption"
        val blocks = StreamingMarkdownDocumentParser.parse(text)
        assertEquals(2, blocks.size)
        
        assertEquals(StreamingMarkdownBlockKind.Paragraph, blocks[0].kind)
        assertEquals("Paragraph line 1\n", blocks[0].source)
        
        assertEquals(StreamingMarkdownBlockKind.Heading, blocks[1].kind)
        assertEquals("# Interruption", blocks[1].source)
    }

    @Test
    fun parse_startOffset() {
        val text = "Skipped\n# Heading"
        val blocks = StreamingMarkdownDocumentParser.parse(text, startOffset = 8)
        assertEquals(1, blocks.size)
        
        val block = blocks[0]
        assertEquals(StreamingMarkdownBlockKind.Heading, block.kind)
        assertEquals("# Heading", block.source)
    }
}
