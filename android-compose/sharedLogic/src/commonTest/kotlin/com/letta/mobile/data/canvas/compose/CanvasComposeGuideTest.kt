package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasToolContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.14: the guide `canvas_compose_guide` answers says what the code does. Every
 * cap, code and colour it states is the constant's, every refusal example is refused the way it
 * says, and the markdown rules it lists are the parser's.
 */
class CanvasComposeGuideTest {
    private val guide = CanvasComposeGuide.text

    /** The guide without its example request: the prose an agent reads as rules. */
    private val prose = guide.substringBefore("## Example")

    @Test
    fun itIsAShortRead() {
        assertTrue(guide.length <= CanvasComposeGuide.MAX_CHARS, "the guide is ${guide.length} characters")
        println("canvas_compose_guide: ${guide.length} characters")
    }

    @Test
    fun everyKindAndCodeIsDocumented() {
        ComposeKind.entries.forEach { assertTrue("- ${it.name} {" in prose, "kind ${it.name}") }
        ComposeErrorCode.entries.forEach { assertTrue("- ${it.name}: " in prose, "error code ${it.name}") }
        ComposeProblemCode.entries.forEach { code ->
            assertTrue(CanvasComposeGuide.PROBLEM_EXAMPLES.any { it.code == code }, "no example for $code")
            assertTrue("- ${code.name} at " in prose, "problem code $code")
        }
        assertTrue(CanvasComposeService.PUBLISH_FAILED in prose)
    }

    /** Each cap where the guide states it, read back out of the text, is the contract's. */
    @Test
    fun everyStatedCapIsTheConstant() {
        fun stated(pattern: String): Int {
            val match = Regex(pattern).find(prose) ?: error("the guide does not say /$pattern/")
            return match.groupValues[1].toInt()
        }
        assertEquals(CanvasComposeContract.MAX_ITEMS, stated("""items \(required\): what to make, in reading order; 1 to (\d+),"""))
        assertEquals(48, stated("""artifact_id: names the artifact; lowercase letters, digits, _ and -, at most (\d+)\."""))
        assertTrue(CanvasComposeContract.isArtifactId("a".repeat(48)) && !CanvasComposeContract.isArtifactId("a".repeat(49)))
        assertEquals(32, stated("""optional "key": lowercase letters, digits, _ and -, at most (\d+),"""))
        assertTrue(CanvasComposeContract.isKey("k".repeat(32)) && !CanvasComposeContract.isKey("k".repeat(33)))
        assertEquals(CanvasComposeContract.MAX_TITLE_CHARS, stated("""title: names the artifact in the chat, at most (\d+) characters"""))
        assertEquals(CanvasComposeContract.MAX_TITLE_CHARS, stated("""Titles are at most (\d+) characters"""))
        assertEquals(CanvasComposeContract.MAX_REQUEST_BYTES / 1024, stated("""The whole request is at most (\d+) KiB"""))
        assertEquals(CanvasComposeContract.MAX_MARKDOWN_CHARS, stated("""NOTE \{markdown, title\?, color\?\}: markdown up to (\d+) characters"""))
        assertEquals(CanvasComposeContract.MAX_CHECKLIST_ITEMS, stated("""checked\?\}\], title\?, color\?\}: 1 to (\d+) entries"""))
        assertEquals(CanvasComposeContract.MAX_VALUE_CHARS, stated("""entries of up to (\d+) characters"""))
        assertEquals(CanvasComposeContract.MAX_CARD_FIELDS, stated("""a titled note: up to (\d+) fields"""))
        assertEquals(CanvasComposeContract.MAX_LABEL_CHARS, stated("""fields \(label up to (\d+),"""))
        assertEquals(CanvasComposeContract.MAX_VALUE_CHARS, stated("""value up to (\d+)\)"""))
        assertEquals(CanvasComposeContract.MAX_CARD_BODY_CHARS, stated("""then up to (\d+) characters of markdown"""))
        assertEquals(CanvasComposeContract.MAX_TEXT_CHARS, stated("""size: heading\|body\}: up to (\d+) characters"""))
        assertEquals(CanvasComposeContract.MAX_LABEL_CHARS, stated("""a labelled frame \(label up to (\d+)\)"""))
        assertEquals(CanvasComposeMarkdown.MAX_PROBLEMS, stated("""At most (\d+) problems are listed per field"""))
        assertEquals(CanvasComposeIds.DERIVED_HEX, stated("""\(a- and (\d+) hex digits\)"""))
        assertEquals(CanvasComposeContract.NOTE_WIDTH.toInt(), stated("""Notes and cards are (\d+) wide"""))
        assertEquals(CanvasComposeContract.TEXT_HEADING_WIDTH.toInt(), stated("""heading TEXT (\d+)\)"""))
        assertEquals(CanvasComposePlacement.WIDE_CONTENT.toInt(), stated("""wider than (\d+)\)"""))
        assertTrue("2 columns for 2 to 4 items, 3 columns for 5 to 9 items, 4 columns for 10 or more" in prose, prose)
        assertEquals(listOf(1, 2, 2, 2, 3, 3, 3, 3, 3, 4), (1..10).map(CanvasComposePlacement::columns))
    }

    @Test
    fun theColoursAreTheOnesTheCompilerWrites() {
        assertEquals(CanvasComposeContract.COLOR_PRESETS, CanvasComposeColors.PRESETS.keys.toList())
        CanvasComposeColors.PRESETS.forEach { (name, hex) ->
            assertTrue("$name ($hex)" in prose, "colour $name")
            assertEquals(hex, CanvasComposeColors.of(name))
        }
        assertTrue("a CARD is white (${CanvasComposeColors.CARD_DEFAULT})" in prose)
        val ready = readyOf(compileText("""{"items":[{"kind":"NOTE","markdown":"x"},{"kind":"CARD","title":"t"},{"kind":"NOTE","color":"red","markdown":"y"}]}"""))
        val colours = ready.ops.filterIsInstance<CanvasOp.SetDocumentOp>().map { it.color }
        assertEquals(listOf(null, CanvasComposeColors.CARD_DEFAULT, CanvasComposeColors.PRESETS.getValue("red")), colours)
    }

    /** Every problem example in the guide is refused with its code at its path. */
    @Test
    fun everyProblemExampleIsRefusedAsShown() {
        CanvasComposeGuide.PROBLEM_EXAMPLES.forEach { example ->
            val refusal = refusedOf(compileText(example.request))
            assertTrue(
                refusal.problems.any { it.path == example.path && it.code == example.code.name },
                "${example.code}: ${example.request.take(120)} was refused as ${refusal.problems}",
            )
            assertTrue("- ${example.code.name} at ${example.path}: ${example.shown}" in prose, example.code.name)
        }
        // UNSUPPORTED_VERSION, as the guide says.
        val version = refusedOf(compileText("""{"version":2,"items":[{"kind":"NOTE","markdown":"x"}]}"""))
        assertEquals(ComposeErrorCode.UNSUPPORTED_VERSION, version.code)
        assertEquals("/version", version.problems.single().path)
        assertEquals(ComposeProblemCode.BAD_VALUE.name, version.problems.single().code)
        // ARTIFACT_EXISTS at /artifact_id.
        val board = ComposeBoard()
        board.publish(readyOf(compileText(WEEKEND_PLAN, board.sceneJson)).ops)
        val changed = WEEKEND_PLAN.replace("Milk", "Oat milk")
        val exists = refusedOf(compileText(changed, board.sceneJson))
        assertEquals(ComposeErrorCode.ARTIFACT_EXISTS, exists.code)
        assertEquals("/artifact_id", exists.problems.single().path)
        // ...and the same content is a retry.
        assertTrue(readyOf(compileText(WEEKEND_PLAN, board.sceneJson)).alreadyPublished)
    }

    /** What the markdown section promises and refuses is what the parser does. */
    @Test
    fun theMarkdownRulesAreTheParsers() {
        fun parsed(markdown: String) = assertIs<MdParse.Parsed>(CanvasComposeMarkdown.parse(markdown, "/m"), markdown).blocks
        fun refused(markdown: String, code: ComposeProblemCode = ComposeProblemCode.UNSUPPORTED_MARKDOWN) {
            val problems = assertIs<MdParse.Refused>(CanvasComposeMarkdown.parse(markdown, "/m"), "accepted: $markdown").problems
            assertTrue(problems.all { it.code == code.name }, "$markdown: $problems")
            if (code == ComposeProblemCode.UNSUPPORTED_MARKDOWN) assertTrue(problems.all { "at offset" in it.message }, "$problems")
        }
        // Accepted, one per construct the guide lists.
        listOf(
            "para", "# h1\n## h2\n### h3", "> quote", "```\ncode\n```", "a\n\n---\n\nb", "- a\n- b", "* a\n* b",
            "1. one\n2. two", "- [ ] to do\n- [x] done", "**b** *i* `c` [t](https://u) ~~s~~ \\*",
        ).forEach { parsed(it) }
        // One nested level, stored flat as an indented item.
        val nested = parsed("- parent\n  - child")
        assertEquals(listOf(0, 1), nested.map { (it as MdBlock.BulletItem).indent })
        // Refused, one per rule the guide lists.
        refused("<b>Hi</b>")
        refused("<https://example.com>")
        refused("![alt](https://example.com/a.png)")
        refused("| a | b |\n|---|---|")
        refused("Text[^1]\n\n[^1]: note")
        refused("[text][ref]\n\n[ref]: https://example.com")
        refused("Title\n=====")
        refused("    indented code")
        refused("3. starts at three")
        refused("#### four")
        refused("- item\n  # heading")
        refused("> > nested quote")
        refused("```\nnever closed")
        refused("- a\n  - b\n    - c", ComposeProblemCode.NESTING_TOO_DEEP)
        assertEquals(3, CanvasComposeMarkdown.MAX_HEADING_LEVEL)
        assertTrue("# to ### headings" in prose)
    }

    @Test
    fun keysAndIdsAreAsDescribed() {
        val ready = readyOf(
            compileText(
                """{"artifact_id":"plan","items":[{"kind":"NOTE","markdown":"a"},{"kind":"NOTE","markdown":"b"},{"kind":"NOTE","markdown":"c"},""" +
                    """{"kind":"GROUP","label":"G","children":[{"kind":"NOTE","markdown":"d"}]}]}""",
            ),
        )
        val ids = ready.ops.map { (it as? CanvasOp.SetDocumentOp)?.documentId ?: (it as CanvasOp.AddElementOp).elementId }.toSet()
        listOf("cmp-plan-i0", "cmp-plan-i1", "cmp-plan-i3", "cmp-plan-i3-c0", "cmp-plan-i3-label").forEach { assertTrue(it in ids, "$it not in $ids") }
        assertTrue("i0, i1, and i3-c0 for the first child of the group at i3" in prose)
        assertTrue("cmp-<artifact_id>-<key>; a group's label is cmp-<artifact_id>-<key>-label" in prose)
        // Without an artifact_id: derived from the tool call.
        assertEquals("a-", CanvasComposeIds.derived("call-1").take(2))
        assertEquals(2 + CanvasComposeIds.DERIVED_HEX, CanvasComposeIds.derived("call-1").length)
    }

    @Test
    fun itPointsAtTheOtherTools() {
        assertTrue(CanvasToolContract.APPLY_OPS in prose)
        assertTrue(CanvasToolContract.LIST in prose)
        assertTrue(CanvasToolContract.COMPOSE_GUIDE in CanvasToolContract.compose.description)
        assertTrue(CanvasToolContract.compose.description.length <= CanvasToolContract.COMPOSE_DESCRIPTION_MAX_CHARS)
    }
}
