package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasDocumentText
import com.letta.mobile.data.canvas.CanvasSceneCheck
import com.letta.mobile.data.canvas.CanvasSceneLimits
import com.letta.mobile.data.canvas.CanvasSceneStateChecks
import com.letta.mobile.data.canvas.CanvasSceneValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The block writer (letta-mobile-bglj6.8) on its own terms: ids, nesting, the exact JSON for the
 * plan's fixture notes, and that the board's own checks accept every document it writes. That the
 * library reads the same JSON is desktop's CanvasCascadeBlocksOracleTest.
 */
class CanvasCascadeBlocksTest {
    private fun parsed(markdown: String): List<MdBlock> =
        assertIs<MdParse.Parsed>(CanvasComposeMarkdown.parse(markdown, "/items/0/markdown")).blocks

    private fun blocksOf(json: String): JsonArray = ComposeJson.parse(json).jsonObject.getValue("blocks").jsonArray

    private val everything = listOf(
        "# Title",
        "",
        "Para with **bold** and [a link](https://x.test).",
        "",
        "- top",
        "  - child",
        "1. first",
        "- [x] done",
        "",
        "> quoted",
        "",
        "```",
        "c1",
        "c2",
        "```",
        "",
        "---",
    ).joinToString("\n")

    @Test
    fun blocksAreNumberedInDocumentOrder() {
        val ids = blocksOf(CanvasCascadeBlocks.document(parsed(everything))).map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals((1..ids.size).map { "b$it" }, ids)
    }

    @Test
    fun aChildItemIsAnIndentedBlockNotANestedOne() {
        val blocks = blocksOf(CanvasCascadeBlocks.document(parsed("- top\n  - child"))).map { it.jsonObject }
        assertFalse("attributes" in blocks[0])
        assertEquals("""{"indentationLevel":1}""", blocks[1].getValue("attributes").toString())
        assertTrue(blocks.none { "children" in it })
    }

    @Test
    fun theFixtureNotesAreWrittenExactly() {
        assertEquals(
            """{"version":2,"blocks":[""" +
                """{"id":"b1","type":{"typeId":"heading_2"},"content":{"kind":"text","version":1,"text":"Saturday","spans":[]}},""" +
                """{"id":"b2","type":{"typeId":"bullet_list"},"content":{"kind":"text","version":1,"text":"Pasta","spans":[]}},""" +
                """{"id":"b3","type":{"typeId":"heading_2"},"content":{"kind":"text","version":1,"text":"Sunday","spans":[]}},""" +
                """{"id":"b4","type":{"typeId":"bullet_list"},"content":{"kind":"text","version":1,"text":"Roast","spans":[]}},""" +
                """{"id":"b5","type":{"typeId":"todo","checked":false},"content":{"kind":"text","version":1,"text":"Buy a chicken","spans":[]}}]}""",
            CanvasCascadeBlocks.document(parsed("## Saturday\n- Pasta\n\n## Sunday\n- Roast\n- [ ] Buy a chicken")),
        )
        assertEquals(
            """{"version":2,"blocks":[{"id":"b1","type":{"typeId":"paragraph"},"content":{"kind":"text","version":1,""" +
                """"text":"Finish Dune before Sunday.","spans":[{"start":7,"end":11,"style":{"type":"italic"}}]}}]}""",
            CanvasCascadeBlocks.document(parsed("Finish *Dune* before Sunday.")),
        )
    }

    @Test
    fun aChecklistIsOneTodoPerEntryWithItsTextAsWritten() {
        assertEquals(
            """{"version":2,"blocks":[""" +
                """{"id":"b1","type":{"typeId":"todo","checked":false},"content":{"kind":"text","version":1,"text":"Milk","spans":[]}},""" +
                """{"id":"b2","type":{"typeId":"todo","checked":true},"content":{"kind":"text","version":1,"text":"Eggs","spans":[]}},""" +
                """{"id":"b3","type":{"typeId":"todo","checked":false},"content":{"kind":"text","version":1,"text":"**Bread**","spans":[]}}]}""",
            CanvasCascadeBlocks.checklist(
                listOf(ComposeChecklistItem("Milk"), ComposeChecklistItem("Eggs", checked = true), ComposeChecklistItem("**Bread**", checked = false)),
            ),
        )
    }

    @Test
    fun aCardIsItsTitleBoldLabelledFieldsThenItsBody() {
        assertEquals(
            """{"version":2,"blocks":[""" +
                """{"id":"b1","type":{"typeId":"heading_2"},"content":{"kind":"text","version":1,"text":"Walk","spans":[]}},""" +
                """{"id":"b2","type":{"typeId":"paragraph"},"content":{"kind":"text","version":1,"text":"When: Sat 09:00",""" +
                """"spans":[{"start":0,"end":5,"style":{"type":"bold"}}]}},""" +
                """{"id":"b3","type":{"typeId":"paragraph"},"content":{"kind":"text","version":1,"text":"Where: Park",""" +
                """"spans":[{"start":0,"end":6,"style":{"type":"bold"}}]}},""" +
                """{"id":"b4","type":{"typeId":"bullet_list"},"content":{"kind":"text","version":1,"text":"water","spans":[]}}]}""",
            CanvasCascadeBlocks.card(
                "Walk",
                listOf(ComposeCardField("When", "Sat 09:00"), ComposeCardField("Where", "Park")),
                parsed("- water"),
            ),
        )
        assertEquals(1, blocksOf(CanvasCascadeBlocks.card("Only a title", emptyList(), emptyList())).size)
    }

    @Test
    fun linksCarryTheirUrlAndCodeAndDividersCarryNoSpans() {
        val blocks = blocksOf(CanvasCascadeBlocks.document(parsed("[a](https://x.test/?q=\"1\")\n\n```\n**x**\n```\n\n---"))).map { it.jsonObject }
        assertEquals(
            """[{"start":0,"end":1,"style":{"type":"link","url":"https://x.test/?q=\"1\""}}]""",
            blocks[0].getValue("content").jsonObject.getValue("spans").toString(),
        )
        assertEquals("""{"kind":"text","version":1,"text":"**x**","spans":[]}""", blocks[1].getValue("content").toString())
        assertEquals("""{"typeId":"divider"}""", blocks[2].getValue("type").toString())
        assertEquals("""{"kind":"empty"}""", blocks[2].getValue("content").toString())
    }

    @Test
    fun theBoardAcceptsEveryDocumentItWrites() {
        val documents = listOf(
            CanvasCascadeBlocks.document(parsed(everything)),
            CanvasCascadeBlocks.checklist(listOf(ComposeChecklistItem("a"))),
            CanvasCascadeBlocks.card("t", listOf(ComposeCardField("l", "v")), parsed("b")),
        )
        documents.forEach { json ->
            assertIs<CanvasSceneCheck.Valid>(CanvasSceneValidator.document("cmp-a-i1", json))
            assertTrue(CanvasSceneStateChecks.decodes(json))
            assertTrue(CanvasDocumentText.isRecognizedDocument(json))
        }
    }

    @Test
    fun thePlainTextIsTheMarkdownsTextLineByLine() {
        assertEquals(
            listOf("Title", "Para with bold and a link.", "top", "child", "first", "done", "quoted", "c1", "c2").joinToString("\n"),
            CanvasDocumentText.plainText(CanvasCascadeBlocks.document(parsed(everything))),
        )
        assertEquals(
            "Walk\nWhen: Sat 09:00",
            CanvasDocumentText.plainText(CanvasCascadeBlocks.card("Walk", listOf(ComposeCardField("When", "Sat 09:00")), emptyList())),
        )
    }

    @Test
    fun aFullLengthNoteStaysFarUnderTheDocumentLimit() {
        val section = "## Section\n\nSome **bold** text, *italic* text, `code` and a [link](https://example.com/page).\n\n" +
            "- one item\n  - a child item\n1. numbered\n- [ ] a task\n\n> a quote\n\n---\n\n"
        val markdown = section.repeat(CanvasComposeContract.MAX_MARKDOWN_CHARS / section.length + 1)
            .take(CanvasComposeContract.MAX_MARKDOWN_CHARS).substringBeforeLast("\n\n")
        assertTrue(markdown.length > CanvasComposeContract.MAX_MARKDOWN_CHARS - section.length)
        val json = CanvasCascadeBlocks.document(parsed(markdown))
        assertTrue(json.length < 64 * 1024, "a ${markdown.length}-char note wrote ${json.length} chars")
    }

    @Test
    fun evenTheDensestMarkdownFitsTheDocumentLimit() {
        // Two characters per block is the most JSON per markdown character the subset allows.
        val markdown = "-\n".repeat(CanvasComposeContract.MAX_MARKDOWN_CHARS / 2)
        val json = CanvasCascadeBlocks.document(parsed(markdown))
        assertTrue(json.length < CanvasSceneLimits.MAX_DOCUMENT_CHARS / 2, "wrote ${json.length} chars")
        assertIs<CanvasSceneCheck.Valid>(CanvasSceneValidator.document("cmp-a-i1", json))
    }

    @Test
    fun theWriterOutputIsAJsonObjectWithVersionTwo() {
        val root = ComposeJson.parse(CanvasCascadeBlocks.document(parsed("x")))
        assertIs<JsonObject>(root)
        assertEquals("2", root.getValue("version").jsonPrimitive.content)
    }
}
