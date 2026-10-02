package com.letta.mobile.desktop.canvas

import com.letta.mobile.data.canvas.compose.CanvasCascadeBlocks
import com.letta.mobile.data.canvas.compose.CanvasComposeMarkdown
import com.letta.mobile.data.canvas.compose.ComposeCardField
import com.letta.mobile.data.canvas.compose.ComposeChecklistItem
import com.letta.mobile.data.canvas.compose.MdBlock
import com.letta.mobile.data.canvas.compose.MdParse
import com.letta.mobile.data.canvas.compose.MdSpan
import com.letta.mobile.data.canvas.compose.MdSpanStyle
import com.letta.mobile.data.canvas.compose.MdText
import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.core.BlockAttributes
import io.github.linreal.cascade.editor.core.BlockContent
import io.github.linreal.cascade.editor.core.BlockId
import io.github.linreal.cascade.editor.core.BlockType
import io.github.linreal.cascade.editor.core.SpanStyle
import io.github.linreal.cascade.editor.core.TextSpan
import io.github.linreal.cascade.editor.serialization.DocumentSchema
import io.github.linreal.cascade.editor.serialization.loadFromJson
import io.github.linreal.cascade.editor.serialization.toJson
import io.github.linreal.cascade.editor.state.BlockSpanStates
import io.github.linreal.cascade.editor.state.BlockTextStates
import io.github.linreal.cascade.editor.state.EditorStateHolder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The oracle for canvas_compose's block writer (letta-mobile-bglj6.8): the real cascade-editor
 * 1.9.2, which renders and edits every note, is the authority on the document format, and
 * [CanvasCascadeBlocks] (sharedLogic, no editor dependency) must write exactly what it reads.
 *
 * Each case goes three ways: the library's decoder reads the writer's JSON into the blocks this
 * test builds independently from the library's own types, with no warnings; the library's encoder
 * writes those blocks as the same JSON (key order aside); and an editor state loaded from the
 * writer's JSON, as CanvasBlockEditor loads a note, saves it back unchanged. A key or type id that
 * drifts from the library fails here, which is why the writer's constants can be trusted.
 */
class CanvasCascadeBlocksOracleTest {

    @Test
    fun everyConstructOfTheSubsetIsTheBlockTheLibraryReads() {
        val markdown = listOf(
            "# One",
            "## Two",
            "### Three",
            "",
            "A paragraph with **bold**, *italic*, `code`, [a link](https://example.com/x?y=1) and ~~gone~~.",
            "",
            "- bullet",
            "  - child bullet",
            "* star bullet",
            "",
            "1. first",
            "2. second",
            "   1. child one",
            "   2. child two",
            "3. third",
            "",
            "- [ ] open task",
            "- [x] done task",
            "  - [X] done child",
            "",
            "> a quote with *emphasis*",
            "",
            "```kotlin",
            "val x = 1 // **not bold**",
            "  indented",
            "```",
            "",
            "---",
            "",
            "Last ***both*** words.",
        ).joinToString("\n")

        val blocks = parsed(markdown)
        assertOracle(blocks, CanvasCascadeBlocks.document(blocks))
    }

    @Test
    fun theLibraryTypesAreTheOnesThePlanNames() {
        val blocks = parsed("## Title\n\n1. one\n2. two\n   - [x] child\n\n> said\n\n```\ncode\n```\n\n---")
        val decoded = DocumentSchema.decodeFromStringWithReport(CanvasCascadeBlocks.document(blocks)).blocks

        assertEquals(BlockType.Heading(2), decoded[0].type)
        assertEquals(listOf(BlockType.NumberedList(1), BlockType.NumberedList(2)), decoded.subList(1, 3).map { it.type })
        assertEquals(BlockType.Todo(true), decoded[3].type)
        assertEquals(1, decoded[3].attributes.indentationLevel)
        assertEquals(BlockType.Quote, decoded[4].type)
        assertEquals(BlockType.Code, decoded[5].type)
        assertEquals("code", assertIs<BlockContent.Text>(decoded[5].content).text)
        assertEquals(BlockType.Divider, decoded[6].type)
        assertEquals(listOf("b1", "b2", "b3", "b4", "b5", "b6", "b7"), decoded.map { it.id.value })
    }

    @Test
    fun spansAreTheLibraryStylesOverTheSameCharacters() {
        val blocks = parsed("A **b** *i* `c` [l](https://x.test) ~~s~~")
        val content = assertIs<BlockContent.Text>(DocumentSchema.decodeFromStringWithReport(CanvasCascadeBlocks.document(blocks)).blocks.single().content)

        assertEquals("A b i c l s", content.text)
        assertEquals(
            listOf(
                TextSpan(2, 3, SpanStyle.Bold),
                TextSpan(4, 5, SpanStyle.Italic),
                TextSpan(6, 7, SpanStyle.InlineCode),
                TextSpan(8, 9, SpanStyle.Link("https://x.test")),
                TextSpan(10, 11, SpanStyle.StrikeThrough),
            ),
            content.spans,
        )
    }

    @Test
    fun aChecklistIsTheLibrarysTodos() {
        val json = CanvasCascadeBlocks.checklist(
            listOf(ComposeChecklistItem("Milk"), ComposeChecklistItem("Eggs", checked = true), ComposeChecklistItem("*Bread*", checked = false)),
        )
        assertOracle(
            listOf(
                MdBlock.TodoItem(false, MdText("Milk")),
                MdBlock.TodoItem(true, MdText("Eggs")),
                MdBlock.TodoItem(false, MdText("*Bread*")),
            ),
            json,
        )
    }

    @Test
    fun aCardIsAHeadingBoldLabelledFieldsAndItsBody() {
        val body = parsed("Bring *water*.")
        val json = CanvasCascadeBlocks.card("Walk", listOf(ComposeCardField("When", "Sat 09:00"), ComposeCardField("Where", "Park")), body)
        val expected = listOf(
            MdBlock.Heading(2, MdText("Walk")),
            MdBlock.Paragraph(MdText("When: Sat 09:00", listOf(MdSpan(MdSpanStyle.BOLD, 0, 5)))),
            MdBlock.Paragraph(MdText("Where: Park", listOf(MdSpan(MdSpanStyle.BOLD, 0, 6)))),
        ) + body
        assertOracle(expected, json)
    }

    @Test
    fun anEmptyListItemAndAnEmptyCodeBlockAreStillBlocks() {
        val blocks = parsed("-\n- [ ]\n\n```\n```")
        assertOracle(blocks, CanvasCascadeBlocks.document(blocks))
    }

    private fun parsed(markdown: String): List<MdBlock> =
        assertIs<MdParse.Parsed>(CanvasComposeMarkdown.parse(markdown, "/items/0/markdown"), "markdown refused").blocks

    /** [json] is what the library reads as [expected], writes for [expected], and keeps through an editor. */
    private fun assertOracle(expected: List<MdBlock>, json: String) {
        val libraryBlocks = expected.mapIndexed { i, block -> libraryBlock(i + 1, block) }

        val report = DocumentSchema.decodeFromStringWithReport(json)
        assertEquals(emptyList(), report.warnings, "the library warned reading $json")
        assertEquals(libraryBlocks, report.blocks)

        assertEquals(canonical(DocumentSchema.encodeToString(libraryBlocks)), canonical(json), "the library writes these blocks differently")

        val holder = EditorStateHolder()
        val textStates = BlockTextStates()
        val spanStates = BlockSpanStates()
        val loaded = holder.loadFromJson(json, textStates, spanStates)
        assertTrue(loaded.warnings.isEmpty(), "the editor warned loading $json: ${loaded.warnings}")
        assertEquals(canonical(json), canonical(holder.toJson(textStates, spanStates)), "an editor saves the note differently")
    }

    /** The library's own block for [block], built from its types, never from the writer's constants. */
    private fun libraryBlock(ordinal: Int, block: MdBlock): Block {
        val id = BlockId("b$ordinal")
        return when (block) {
            is MdBlock.Paragraph -> Block(id, BlockType.Paragraph, text(block.content))
            is MdBlock.Heading -> Block(id, BlockType.Heading(block.level), text(block.content))
            is MdBlock.BulletItem -> Block(id, BlockType.BulletList, text(block.content), BlockAttributes(block.indent))
            is MdBlock.NumberedItem -> Block(id, BlockType.NumberedList(block.number), text(block.content), BlockAttributes(block.indent))
            is MdBlock.TodoItem -> Block(id, BlockType.Todo(block.checked), text(block.content), BlockAttributes(block.indent))
            is MdBlock.Quote -> Block(id, BlockType.Quote, text(block.content))
            is MdBlock.Code -> Block(id, BlockType.Code, BlockContent.Text(block.text, emptyList()))
            MdBlock.Divider -> Block(id, BlockType.Divider, BlockContent.Empty)
        }
    }

    private fun text(text: MdText): BlockContent.Text = BlockContent.Text(
        text.text,
        text.spans.map { span ->
            val style = when (span.style) {
                MdSpanStyle.BOLD -> SpanStyle.Bold
                MdSpanStyle.ITALIC -> SpanStyle.Italic
                MdSpanStyle.INLINE_CODE -> SpanStyle.InlineCode
                MdSpanStyle.LINK -> SpanStyle.Link(span.url!!)
                MdSpanStyle.STRIKETHROUGH -> SpanStyle.StrikeThrough
            }
            TextSpan(span.start, span.end, style)
        },
    )

    /** Keys sorted at every level, compact: two encodings of one value print the same. */
    private fun canonical(json: String): String = canonical(Json.parseToJsonElement(json))

    private fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { (key, value) -> JsonPrimitive(key).toString() + ":" + canonical(value) }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> element.toString()
    }
}
