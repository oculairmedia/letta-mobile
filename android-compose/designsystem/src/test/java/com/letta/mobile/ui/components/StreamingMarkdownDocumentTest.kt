package com.letta.mobile.ui.components

import com.letta.mobile.ui.markdown.StreamingMarkdownBlockKind
import com.letta.mobile.ui.markdown.StreamingMarkdownDocument
import com.letta.mobile.ui.markdown.StreamingMarkdownDocumentBlock
import com.letta.mobile.ui.markdown.StreamingMarkdownDocumentState
import com.letta.mobile.ui.markdown.allowsInlineCursor
import com.letta.mobile.ui.markdown.renderMarkdownSource
import com.letta.mobile.ui.markdown.supportsPlainTextHeightPrediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class StreamingMarkdownDocumentTest {

    @Test
    fun `one write and character writes produce same final document`() {
        val markdown = """
            # Heading

            Paragraph with **bold** and `code`.

            ```kotlin
            val x = 1
            ```

            - one
            - two

            | A | B |
            | - | - |
            | 1 | 2 |
        """.trimIndent()

        val oneWrite = StreamingMarkdownDocumentState().write(markdown).shape()

        val charState = StreamingMarkdownDocumentState()
        var charDocument = StreamingMarkdownDocument(emptyList())
        for (char in markdown) {
            charDocument = charState.write(char.toString())
        }

        assertEquals(oneWrite, charDocument.shape())
    }

    @Test
    fun `unchanged completed block keeps id and object identity while next block streams`() {
        val state = StreamingMarkdownDocumentState()
        val first = state.write("Intro paragraph.\n\n")
        val intro = first.blocks.single()

        val withFence = state.write("```kotlin\n")

        assertEquals(intro.id, withFence.blocks[0].id)
        assertSame(intro, withFence.blocks[0])
        assertEquals(StreamingMarkdownBlockKind.CodeFence, withFence.blocks[1].kind)
    }

    @Test
    fun `append reparses active tail only when a paragraph becomes a table`() {
        val state = StreamingMarkdownDocumentState()
        val before = state.write("Intro paragraph.\n\n| A | B |\n")
        val intro = before.blocks.first()

        val after = state.write("| - | - |\n| 1 | 2 |")

        assertSame(intro, after.blocks.first())
        assertEquals(StreamingMarkdownBlockKind.Table, after.blocks[1].kind)
        assertEquals("| A | B |\n| - | - |\n| 1 | 2 |", after.blocks[1].source)
    }

    @Test
    fun `append after a closed block keeps completed tail identity`() {
        val state = StreamingMarkdownDocumentState()
        val before = state.write("First.\n\nSecond.\n\n")
        val first = before.blocks[0]
        val second = before.blocks[1]

        val after = state.write("Third.")

        assertSame(first, after.blocks[0])
        assertSame(second, after.blocks[1])
        assertEquals(StreamingMarkdownBlockKind.Paragraph, after.blocks[2].kind)
        assertEquals("Third.", after.blocks[2].source)
    }

    @Test
    fun `open code fence becomes a code block immediately`() {
        val doc = StreamingMarkdownDocumentState().write("```kot")

        assertEquals(1, doc.blocks.size)
        assertEquals(StreamingMarkdownBlockKind.CodeFence, doc.blocks.single().kind)
        assertEquals("```kot\n```", doc.blocks.single().renderMarkdownSource())
    }

    @Test
    fun `open display math becomes a math block immediately`() {
        val doc = StreamingMarkdownDocumentState().write("\$\$x^2")

        assertEquals(1, doc.blocks.size)
        assertEquals(StreamingMarkdownBlockKind.DisplayMath, doc.blocks.single().kind)
        assertEquals("\$\$x^2\$\$", doc.blocks.single().renderMarkdownSource())
    }

    @Test
    fun `active block keeps id as it grows`() {
        val state = StreamingMarkdownDocumentState()
        val first = state.write("Hello")
        val id = first.blocks.single().id

        val second = state.write(" world")

        assertEquals(id, second.blocks.single().id)
        assertEquals("Hello world", second.blocks.single().source)
    }

    @Test
    fun `non append update resets identity`() {
        val state = StreamingMarkdownDocumentState()
        val first = state.update("Original")

        val second = state.update("Replacement")

        assertNotEquals(first.blocks.single().id, second.blocks.single().id)
    }

    @Test
    fun `stable height token excludes active block while streaming`() {
        val doc = StreamingMarkdownDocumentState()
            .write("First.\n\nSecond")

        assertEquals(doc.blocks.first().key, doc.stableHeightToken(isStreaming = true))
        assertEquals(
            doc.blocks.joinToString(separator = "|") { it.key },
            doc.stableHeightToken(isStreaming = false),
        )
    }

    @Test
    fun `stable height token can include active paragraph line count`() {
        val doc = StreamingMarkdownDocumentState()
            .write("First.\n\nSecond paragraph")

        assertEquals(
            "${doc.blocks.first().key}|${doc.blocks.last().key}:lines=3",
            doc.stableHeightToken(isStreaming = true, activeLineCount = 3),
        )
    }

    @Test
    fun `plain text height prediction only applies to simple paragraphs`() {
        val state = StreamingMarkdownDocumentState()

        assertEquals(
            true,
            state.update("Plain streaming prose.").blocks.single().supportsPlainTextHeightPrediction(),
        )
        assertEquals(
            false,
            state.update("Here is **bold** text").blocks.single().supportsPlainTextHeightPrediction(),
        )
        assertEquals(
            false,
            state.update("```kotlin\nval x = 1").blocks.single().supportsPlainTextHeightPrediction(),
        )
    }

    @Test
    fun `table is parsed as a stable table block once separator arrives`() {
        val doc = StreamingMarkdownDocumentState()
            .write("| A | B |\n| - | - |\n| 1 | 2 |")

        assertEquals(1, doc.blocks.size)
        assertEquals(StreamingMarkdownBlockKind.Table, doc.blocks.single().kind)
    }

    @Test
    fun `table skeleton can render before first data row arrives`() {
        val doc = StreamingMarkdownDocumentState()
            .write("| A | B |\n| - | - |\n")
        val parsedTable = parseMarkdownTable(doc.blocks.single().source)

        requireNotNull(parsedTable)
        assertEquals(listOf("A", "B"), parsedTable.header)
        assertEquals(emptyList<ParsedTableRow>(), parsedTable.rows)
    }

    @Test
    fun `incremental parsing preserves every streamed prefix source`() {
        val markdown = """
            Sounds — we’ll keep the current tuning:

            - `Feather`
            - `2ms`
            - `96ms` debounce

            reveal-synced

            That’s a solid middle ground: still perceptible, but better than the earlier punchy/metronome feel.
        """.trimIndent()
        val state = StreamingMarkdownDocumentState()

        markdown.forEachIndexed { index, _ ->
            val prefix = markdown.substring(0, index + 1)
            val document = state.update(prefix)
            assertEquals(prefix, document.blocks.joinToString(separator = "") { it.source })
        }
    }

    @Test
    fun `structural blocks do not accept inline cursor glyphs`() {
        val state = StreamingMarkdownDocumentState()

        assertEquals(false, state.update("```kotlin\nval x = 1").blocks.single().allowsInlineCursor)
        assertEquals(false, state.update("\$\$x^2").blocks.single().allowsInlineCursor)
        assertEquals(false, state.update("| A | B |\n| - | - |\n").blocks.single().allowsInlineCursor)
        assertEquals(true, state.update("Plain text").blocks.single().allowsInlineCursor)
    }

    private fun StreamingMarkdownDocument.shape(): List<Pair<StreamingMarkdownBlockKind, String>> {
        return blocks.map { it.kind to it.source }
    }
}
