package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.compose.CanvasComposeFixtures.B
import com.letta.mobile.data.canvas.compose.CanvasComposeFixtures.document
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The reserve estimator (letta-mobile-bglj6.9): pinned fixture values, the clamps, monotonicity,
 * and the property that matters, that it never books less than a pessimistic rendering of the
 * same content (below the cap). The real-render gate is C6's desktop measurement test over
 * [CanvasComposeFixtures].
 */
class CanvasComposeReserveTest {
    @Test
    fun fixtureReservesArePinned() {
        val actual = CanvasComposeFixtures.notes.map { it.name to CanvasComposeReserve.reserveDocument(it.documentJson, it.width) }
        assertEquals(CanvasComposeFixtures.notes.map { it.name to it.reserve }, actual)
        val texts = CanvasComposeFixtures.texts.map { it.name to CanvasComposeReserve.reserveText(it.text, it.size) }
        assertEquals(CanvasComposeFixtures.texts.map { it.name to it.reserve }, texts)
    }

    @Test
    fun fixtureReservesCoverThePessimisticRendering() {
        CanvasComposeFixtures.notes.forEach { case ->
            val worst = PessimisticRenderer.note(CanvasComposeReserve.blocksOf(case.documentJson), case.width.toDouble())
            assertTrue(case.reserve >= minOf(worst, CanvasComposeReserve.MAX_RESERVE.toDouble()), "${case.name}: ${case.reserve} < $worst")
        }
        CanvasComposeFixtures.texts.forEach { case ->
            val font = CanvasComposeReserve.textFont(case.size)
            val worst = PessimisticRenderer.lines(case.text, CanvasComposeContract.width(ComposeKind.TEXT, case.size).toDouble(), font, mono = false) *
                PessimisticRenderer.LINE_EM * font
            assertTrue(case.reserve >= worst, "${case.name}: ${case.reserve} < $worst")
        }
    }

    @Test
    fun reservesAreClampedBetweenTheFloorAndTheCap() {
        assertEquals(CanvasComposeReserve.MIN_RESERVE, CanvasComposeReserve.reserveDocument(document()))
        assertEquals(CanvasComposeReserve.MIN_RESERVE, CanvasComposeReserve.reserveDocument(""))
        assertEquals(CanvasComposeReserve.MIN_RESERVE, CanvasComposeReserve.reserveDocument("not json"))
        val huge = document(*Array(200) { B("paragraph", "word ".repeat(40)) })
        assertEquals(CanvasComposeReserve.MAX_RESERVE, CanvasComposeReserve.reserveDocument(huge))
        assertEquals(CanvasComposeReserve.MAX_RESERVE, CanvasComposeReserve.reserveText("x ".repeat(4_000), ComposeTextSize.HEADING))
    }

    @Test
    fun reservesAreWholeNumbers() {
        val random = Random(7)
        repeat(200) {
            val reserve = CanvasComposeReserve.reserveDocument(randomDocument(random))
            assertEquals(ceil(reserve.toDouble()).toFloat(), reserve)
        }
    }

    @Test
    fun theReserveNeverShrinksAsTextGrows() {
        val random = Random(11)
        repeat(50) {
            val words = List(random.nextInt(1, 200)) { randomWord(random) }
            var previous = 0f
            for (n in 1..words.size) {
                val text = words.take(n).joinToString(" ")
                val reserve = CanvasComposeReserve.reserveDocument(document(B("paragraph", text)))
                assertTrue(reserve >= previous, "'$text' booked $reserve after $previous")
                previous = reserve
                val textReserve = CanvasComposeReserve.reserveText(text, ComposeTextSize.BODY)
                assertTrue(textReserve > 0f)
            }
        }
    }

    @Test
    fun theReserveNeverShrinksAsBlocksAreAdded() {
        val random = Random(13)
        repeat(50) {
            val blocks = randomBlocks(random, 12)
            var previous = 0f
            for (n in 1..blocks.size) {
                val reserve = CanvasComposeReserve.reserveDocument(document(*blocks.take(n).toTypedArray()))
                assertTrue(reserve >= previous)
                previous = reserve
            }
        }
    }

    @Test
    fun theReserveIsNeverBelowAPessimisticRenderingOfRandomNotes() {
        val random = Random(17)
        repeat(1_000) {
            val json = randomDocument(random)
            val width = listOf(240f, 320f, 480f).random(random)
            val reserve = CanvasComposeReserve.reserveDocument(json, width).toDouble()
            val worst = PessimisticRenderer.note(CanvasComposeReserve.blocksOf(json), width.toDouble())
            assertTrue(reserve >= minOf(worst, CanvasComposeReserve.MAX_RESERVE.toDouble()), "$json at $width: $reserve < $worst")
        }
    }

    @Test
    fun aTextReserveIsNeverBelowAPessimisticRendering() {
        val random = Random(19)
        repeat(500) {
            val text = List(random.nextInt(1, 40)) { randomWord(random) }.joinToString(" ")
            ComposeTextSize.entries.forEach { size ->
                val font = CanvasComposeReserve.textFont(size)
                val width = CanvasComposeContract.width(ComposeKind.TEXT, size).toDouble()
                val worst = PessimisticRenderer.lines(text, width, font, mono = false) * PessimisticRenderer.LINE_EM * font
                val reserve = CanvasComposeReserve.reserveText(text, size).toDouble()
                assertTrue(reserve >= minOf(worst, CanvasComposeReserve.MAX_RESERVE.toDouble()), "'$text' $size: $reserve < $worst")
            }
        }
    }

    @Test
    fun wordWrapNeverCountsFewerLinesThanThePlansCharacterFormula() {
        val random = Random(23)
        repeat(1_000) {
            val text = List(random.nextInt(0, 60)) { randomWord(random) }.joinToString(" ")
            val width = random.nextInt(60, 700).toDouble()
            val font = listOf(14.0, 16.0, 22.0, 36.0).random(random)
            // The plan's lines = ceil(chars / floor(width / (0.6 * font))), over the characters a
            // line holds: the space a line breaks at is on neither line.
            val perLine = maxOf(1, (width / (CanvasComposeReserve.ADVANCE_EM * font)).toInt())
            val plan = maxOf(1, ceil(text.count { it != ' ' } / perLine.toDouble()).toInt())
            assertTrue(CanvasComposeReserve.lineCount(text, width, font, mono = false) >= plan, "'$text' at $width/$font")
        }
    }

    @Test
    fun aLongUnbrokenWordBreaksAcrossLines() {
        // 16 px body, 0.6 em per character: 9.6 per character, 250 / 9.6 = 26 characters a line.
        assertEquals(1, CanvasComposeReserve.lineCount("a".repeat(26), 250.0, 16.0, mono = false))
        assertEquals(2, CanvasComposeReserve.lineCount("a".repeat(27), 250.0, 16.0, mono = false))
        assertEquals(10, CanvasComposeReserve.lineCount("a".repeat(260), 250.0, 16.0, mono = false))
        // A short word then a long one: the long one starts on its own line.
        assertEquals(3, CanvasComposeReserve.lineCount("hi " + "a".repeat(52), 250.0, 16.0, mono = false))
        // A box narrower than one character still holds one a line.
        assertEquals(5, CanvasComposeReserve.lineCount("abcde", 1.0, 16.0, mono = false))
    }

    @Test
    fun wordsThatDoNotFitWrapAndHardBreaksAreKept() {
        // 26 characters a line: "aaaa...(20) bbbbbbbb" does not fit, the second word wraps.
        assertEquals(2, CanvasComposeReserve.lineCount("a".repeat(20) + " " + "b".repeat(8), 250.0, 16.0, mono = false))
        assertEquals(3, CanvasComposeReserve.lineCount("one\ntwo\nthree", 250.0, 16.0, mono = false))
        assertEquals(1, CanvasComposeReserve.lineCount("", 250.0, 16.0, mono = false))
    }

    @Test
    fun wideCapitalAndBroadCharactersBookMoreRoom() {
        val width = 240.0
        val lower = CanvasComposeReserve.lineCount("a".repeat(100), width, 16.0, mono = false)
        val capitals = CanvasComposeReserve.lineCount("O".repeat(100), width, 16.0, mono = false)
        val broad = CanvasComposeReserve.lineCount("W".repeat(100), width, 16.0, mono = false)
        val wide = CanvasComposeReserve.lineCount("漢".repeat(100), width, 16.0, mono = false)
        val emoji = CanvasComposeReserve.lineCount("😀".repeat(100), width, 16.0, mono = false)
        assertTrue(lower < capitals && capitals < broad && broad < wide, "$lower $capitals $broad $wide")
        assertEquals(wide, emoji)
    }

    @Test
    fun blocksAreReadFromTheStoredDocumentWithTheirNesting() {
        val json = document(
            B("heading", "Title", level = 1),
            B("bullet_list", "parent", children = listOf(B("todo", "child", checked = true))),
            B("mystery_block", "unknown"),
        )
        assertEquals(
            listOf(
                ReserveBlock(ReserveBlockType.HEADING, "Title", level = 1, depth = 0),
                ReserveBlock(ReserveBlockType.BULLET, "parent", depth = 0),
                ReserveBlock(ReserveBlockType.TODO, "child", depth = 1),
                ReserveBlock(ReserveBlockType.PARAGRAPH, "unknown", depth = 0),
            ),
            CanvasComposeReserve.blocksOf(json),
        )
    }

    /**
     * cascade-editor 1.9.2 writes `BlockType.Heading(2)` as `{"typeId":"heading_2"}` (verified by
     * encoding one with the library's DocumentSchema; letta-mobile-bglj6.11). Read that, and a bare
     * `heading` with a `level` too, as headings at their level; never as a 16-px paragraph.
     */
    @Test
    fun headingLevelsAreReadFromTheLibrarysTypeId() {
        val library = """{"version":2,"blocks":[{"id":"h","type":{"typeId":"heading_2"},"content":{"kind":"text","version":1,"text":"Hi","spans":[]}}]}"""
        assertEquals(listOf(ReserveBlock(ReserveBlockType.HEADING, "Hi", level = 2)), CanvasComposeReserve.blocksOf(library))
        val bare = """{"version":2,"blocks":[{"id":"h","type":{"typeId":"heading","level":3},"content":{"kind":"text","version":1,"text":"Hi","spans":[]}}]}"""
        assertEquals(listOf(ReserveBlock(ReserveBlockType.HEADING, "Hi", level = 3)), CanvasComposeReserve.blocksOf(bare))
        assertEquals(ReserveBlockType.PARAGRAPH, ReserveBlockType.of("heading_x"))
        assertEquals(6, ReserveBlockType.headingLevel("heading_6"))
    }

    @Test
    fun headingsBookTheEditorsOwnSizes() {
        val one = CanvasComposeReserve.blockHeight(ReserveBlock(ReserveBlockType.HEADING, "Title", level = 1), 320.0)
        val three = CanvasComposeReserve.blockHeight(ReserveBlock(ReserveBlockType.HEADING, "Title", level = 3), 320.0)
        assertEquals(1.5 * 32 + 8, one)
        assertEquals(1.5 * 24 + 8, three)
        // A document with the same text in a heading books more than in a paragraph.
        assertTrue(
            CanvasComposeReserve.reserve(ComposeKind.NOTE, listOf(ReserveBlock(ReserveBlockType.HEADING, LONG))) >
                CanvasComposeReserve.reserve(ComposeKind.NOTE, listOf(ReserveBlock(ReserveBlockType.PARAGRAPH, LONG))),
        )
    }

    @Test
    fun reserveFromBlocksAgreesWithReserveFromTheDocument() {
        val random = Random(29)
        repeat(100) {
            val json = randomDocument(random)
            assertEquals(
                CanvasComposeReserve.reserveDocument(json),
                CanvasComposeReserve.reserve(ComposeKind.NOTE, CanvasComposeReserve.blocksOf(json)),
            )
        }
    }

    @Test
    fun aLargerFontScaleBooksMore() {
        val json = document(B("paragraph", LONG))
        assertTrue(CanvasComposeReserve.reserveDocument(json, fontScale = 1.5f) > CanvasComposeReserve.reserveDocument(json))
    }

    @Test
    fun textAndGroupAreNotNotes() {
        assertFailsWith<IllegalArgumentException> { CanvasComposeReserve.reserve(ComposeKind.TEXT, emptyList()) }
        assertFailsWith<IllegalArgumentException> { CanvasComposeReserve.reserve(ComposeKind.GROUP, emptyList(), 320f) }
    }

    @Test
    fun theSameInputBooksTheSameHeight() {
        val random = Random(31)
        repeat(100) {
            val json = randomDocument(random)
            assertEquals(CanvasComposeReserve.reserveDocument(json), CanvasComposeReserve.reserveDocument(json))
        }
    }

    private companion object {
        const val LONG = "A sentence long enough to wrap over several lines of a three hundred and twenty unit note card."

        private val alphabet = "abcdefghijklmnopqrstuvwxyz"
        private val types = listOf("paragraph", "heading", "bullet_list", "numbered_list", "todo", "quote", "code", "divider")

        fun randomWord(random: Random): String {
            val kind = random.nextInt(20)
            val length = if (kind == 0) random.nextInt(30, 120) else random.nextInt(1, 12)
            return buildString {
                repeat(length) {
                    append(
                        when (random.nextInt(14)) {
                            0 -> "MW@%".random(random)
                            1 -> ('A'..'Z').random(random)
                            2 -> "漢字かなカナ한글".random(random)
                            3 -> ('0'..'9').random(random)
                            else -> alphabet.random(random)
                        },
                    )
                }
            }
        }

        fun randomBlocks(random: Random, max: Int, depth: Int = 0): List<B> = List(random.nextInt(1, max + 1)) {
            val type = types.random(random)
            val text = if (type == "divider") "" else List(random.nextInt(0, 30)) { randomWord(random) }.joinToString(" ")
            val children = if (depth == 0 && random.nextInt(5) == 0) randomBlocks(random, 3, depth + 1) else emptyList()
            B(type, text, level = if (type == "heading") random.nextInt(1, 4) else null, children = children)
        }

        fun randomDocument(random: Random): String = document(*randomBlocks(random, 8).toTypedArray())
    }
}

/**
 * A deliberately pessimistic model of how the card renders, independent of the estimator's own
 * arithmetic: real worst-case advances of a common sans face in em (lower case up to 0.56, capitals
 * up to 0.72, M/W 0.9, CJK and emoji 1.0, monospace 0.6), pixel-level greedy word wrap, line height
 * 1.3 em, the renderer's real chrome (28 handle bar, 4 + 4 padding, 12 + 16 horizontal padding per
 * side, 24 per nesting level, 28 for a list marker, 12 for a quote), 6 between blocks.
 */
internal object PessimisticRenderer {
    const val LINE_EM = 1.3
    private const val HANDLE = 28.0
    private const val PADDING = 8.0
    private const val SIDE = 28.0

    fun advance(c: Char, mono: Boolean): Double = when {
        c.isSurrogate() -> 0.5 // half of a pair: the pair together is one 1.0 glyph
        c.code in 0x1100..0x115F || c.code in 0x2E80..0xFFEF -> 1.0
        mono -> 0.6
        c in "MWmw@%" -> 0.9
        c.isUpperCase() -> 0.72
        else -> 0.56
    }

    fun lines(text: String, width: Double, font: Double, mono: Boolean): Int = text.split('\n').sumOf { hard ->
        var lines = 1
        var used = 0.0
        val space = advance(' ', mono) * font
        hard.split(' ').forEach { word ->
            val glyphs = word.map { advance(it, mono) * font }
            val w = glyphs.sum()
            val lead = if (used == 0.0) 0.0 else space
            if (used + lead + w <= width) {
                used += lead + w
            } else if (w <= width) {
                lines++
                used = w
            } else {
                if (used > 0.0) lines++
                used = 0.0
                glyphs.forEach { g ->
                    if (used + g > width && used > 0.0) {
                        lines++
                        used = 0.0
                    }
                    used += g
                }
            }
        }
        lines
    }

    fun note(blocks: List<ReserveBlock>, width: Double): Double = HANDLE + PADDING + blocks.sumOf { block ->
        val font = when (block.type) {
            ReserveBlockType.HEADING -> listOf(32.0, 28.0, 24.0, 20.0, 18.0, 16.0)[(block.level - 1).coerceIn(0, 5)]
            ReserveBlockType.CODE -> 14.0
            else -> 16.0
        }
        if (block.type == ReserveBlockType.DIVIDER) return@sumOf 17.0
        val inset = when (block.type) {
            ReserveBlockType.BULLET, ReserveBlockType.NUMBERED, ReserveBlockType.TODO -> 28.0
            ReserveBlockType.QUOTE -> 12.0
            else -> 0.0
        }
        val inner = maxOf(width - 2 * SIDE - block.depth * 24.0 - inset, font)
        val codePadding = if (block.type == ReserveBlockType.CODE) 12.0 else 0.0
        lines(block.text, inner, font, block.type == ReserveBlockType.CODE) * LINE_EM * font + 6.0 + codePadding
    }
}
