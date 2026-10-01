package com.letta.mobile.data.canvas.compose

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.ceil

/**
 * The height placement RESERVES for a composed item (letta-mobile-bglj6.9, plan section 3.4, D3).
 *
 * The compose layer cannot measure text: the host has no fonts, and Android and Skiko measure the
 * same words differently. So it books a height that is an overestimate by construction, and the
 * renderer fits the real content WITHIN it (it shrinks the card to what it measured and never grows
 * past the booking; anything beyond scrolls inside the card). Pure arithmetic over characters, with
 * no font, density or Compose API, done in whole world units so every peer and every target books
 * the same number.
 *
 * Why it is an overestimate:
 * - Every character is booked at a WORST-CASE advance for its class ([advanceEm]): 0.6 em for
 *   ordinary text, 0.75 em for capitals, 0.9 em for the widest Latin glyphs, 1.2 em for wide
 *   (CJK, emoji) characters, 0.65 em for monospace code.
 * - Lines are counted by greedy WORD wrap ([lineCount]), which is never fewer than the characters
 *   divided by the line: a word that does not fit starts a new line, a word longer than a line is
 *   broken across lines.
 * - A line is booked at 1.5 x the font size (Compose sets about 1.2), each block gets 8 more, the
 *   chrome is booked at more than the card draws, and the total is multiplied by [SAFETY_FACTOR].
 * - Font sizes are the editor's own (cascade-editor 1.9.2 `CascadeEditorTypography` defaults: body
 *   16, headings 32/28/24/20/18/16 by level, code 14 mono); the inner width removes the card's and
 *   the editor's horizontal padding and each nesting level's indent.
 *
 * The only place it is knowingly short is [MAX_RESERVE]: a note longer than that is booked at the
 * cap and scrolls. The desktop measurement test of C6 holds the estimate to real renders.
 */
object CanvasComposeReserve {
    /** Booked heights are clamped to this range (TEXT has no floor beyond one line). */
    const val MIN_RESERVE = 120f
    const val MAX_RESERVE = 1_200f

    /** Multiplies the whole estimate: room for what the per-character model still misses. */
    const val SAFETY_FACTOR = 1.15

    /** The handle bar a note card draws above its body (28 in the renderer). */
    const val CHROME_HANDLE = 32.0

    /** The card body's vertical padding (4 + 4 in the renderer). */
    const val CHROME_PADDING = 24.0

    /**
     * Horizontal padding per side inside a note card: the card's own (12) plus the editor's block
     * padding (16, `CascadeEditorDimensions.blockHorizontalPadding`).
     */
    const val HORIZONTAL_PADDING = 28.0

    /** Each nesting level indents its blocks by this much (`CascadeEditorDimensions.indentUnit`). */
    const val INDENT = 24.0

    /** The width a list bullet, number or to-do checkbox takes from its line (the plan's 3 characters at 16, rounded up). */
    const val MARKER_WIDTH = 32.0

    /** A quote's bar and its inset. */
    const val QUOTE_INSET = 16.0

    /** Line height as a multiple of the font size, and the space added under every block. */
    const val LINE_HEIGHT_EM = 1.5
    const val BLOCK_GAP = 8.0

    /** A code block's own vertical padding, and the height a divider takes. */
    const val CODE_PADDING = 16.0
    const val DIVIDER_HEIGHT = 24.0

    const val BODY_FONT = 16.0
    const val CODE_FONT = 14.0

    /** cascade-editor's heading sizes, levels 1 to 6. */
    val HEADING_FONTS: List<Double> = listOf(32.0, 28.0, 24.0, 20.0, 18.0, 16.0)

    /** TEXT element font sizes, the ones the compiler writes into the DrawBox Text element. */
    const val TEXT_HEADING_FONT = 36.0
    const val TEXT_BODY_FONT = 18.0

    /** Advance of an ordinary character, and of a monospace one, in em. */
    const val ADVANCE_EM = 0.6
    const val MONO_ADVANCE_EM = 0.65

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The height to book for a NOTE, CHECKLIST or CARD document of [blocks] at [width] (the kind's
     * width; [fontScale] is the document style's, 1 for anything compose writes).
     */
    fun reserve(kind: ComposeKind, blocks: List<ReserveBlock>, width: Float = CanvasComposeContract.width(kind), fontScale: Float = 1f): Float {
        require(kind != ComposeKind.TEXT && kind != ComposeKind.GROUP) { "$kind is not a note; use reserveText or the group's children" }
        return documentReserve(blocks, width.toDouble(), fontScale.toDouble())
    }

    /** [reserve] of a stored Cascade v2 document (`{"version":2,"blocks":[...]}`), the form every note is kept in. */
    fun reserveDocument(documentJson: String, width: Float = CanvasComposeContract.NOTE_WIDTH, fontScale: Float = 1f): Float =
        documentReserve(blocksOf(documentJson), width.toDouble(), fontScale.toDouble())

    /**
     * The height to book for a TEXT element: its lines at [wrapWidth] (the DrawBox `wrapWidth`)
     * in the TEXT font size, times the safety factor. At least one line; no chrome, no 120 floor.
     */
    fun reserveText(text: String, size: ComposeTextSize, wrapWidth: Float = CanvasComposeContract.width(ComposeKind.TEXT, size)): Float {
        val font = textFont(size)
        val lines = lineCount(text, wrapWidth.toDouble(), font, mono = false)
        val raw = lines * LINE_HEIGHT_EM * font * SAFETY_FACTOR
        return ceil(raw).coerceAtMost(MAX_RESERVE.toDouble()).toFloat()
    }

    fun textFont(size: ComposeTextSize): Double = if (size == ComposeTextSize.HEADING) TEXT_HEADING_FONT else TEXT_BODY_FONT

    /**
     * The blocks of a Cascade v2 document, depth-first, nested `children` one indent deeper. Reads
     * only `type.typeId`, `type.level` and `content.text`, so a newer document still yields its
     * blocks; a block of a type it does not know is booked as a paragraph, unreadable input as none.
     */
    fun blocksOf(documentJson: String): List<ReserveBlock> {
        if (documentJson.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(documentJson) }.getOrNull() as? JsonObject ?: return emptyList()
        val out = mutableListOf<ReserveBlock>()
        collect(root["blocks"], depth = 0, into = out)
        return out
    }

    private fun collect(blocks: JsonElement?, depth: Int, into: MutableList<ReserveBlock>) {
        (blocks as? JsonArray)?.forEach { element ->
            val block = element as? JsonObject ?: return@forEach
            val type = block["type"] as? JsonObject
            val typeId = (type?.get("typeId") as? JsonPrimitive)?.contentOrNull
            val level = (type?.get("level") as? JsonPrimitive)?.intOrNull ?: 1
            val text = ((block["content"] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
            into += ReserveBlock(ReserveBlockType.of(typeId), text, level, depth)
            collect(block["children"], depth + 1, into)
        }
    }

    private fun documentReserve(blocks: List<ReserveBlock>, width: Double, fontScale: Double): Float {
        val body = blocks.sumOf { blockHeight(it, width, fontScale) }
        val raw = (CHROME_HANDLE + CHROME_PADDING + body) * SAFETY_FACTOR
        return ceil(raw).coerceIn(MIN_RESERVE.toDouble(), MAX_RESERVE.toDouble()).toFloat()
    }

    /** One block's booked height: its wrapped lines at its font, plus the block gap. */
    fun blockHeight(block: ReserveBlock, width: Double, fontScale: Double = 1.0): Double {
        if (block.type == ReserveBlockType.DIVIDER) return DIVIDER_HEIGHT + BLOCK_GAP
        val font = fontOf(block) * fontScale
        val inner = width - 2 * HORIZONTAL_PADDING - block.depth * INDENT - insetOf(block.type)
        val lines = lineCount(block.text, inner, font, mono = block.type == ReserveBlockType.CODE)
        val extra = if (block.type == ReserveBlockType.CODE) CODE_PADDING else 0.0
        return lines * LINE_HEIGHT_EM * font + BLOCK_GAP + extra
    }

    private fun fontOf(block: ReserveBlock): Double = when (block.type) {
        ReserveBlockType.HEADING -> HEADING_FONTS[(block.level - 1).coerceIn(0, HEADING_FONTS.lastIndex)]
        ReserveBlockType.CODE -> CODE_FONT
        else -> BODY_FONT
    }

    private fun insetOf(type: ReserveBlockType): Double = when (type) {
        ReserveBlockType.BULLET, ReserveBlockType.NUMBERED, ReserveBlockType.TODO -> MARKER_WIDTH
        ReserveBlockType.QUOTE -> QUOTE_INSET
        else -> 0.0
    }

    /**
     * Lines [text] takes at [width] world units in a [font] of that size: hard line breaks kept,
     * greedy word wrap at spaces, a word longer than a line broken across lines, every character
     * at its worst-case advance. Empty text is one line (an empty block still takes a line).
     */
    fun lineCount(text: String, width: Double, font: Double, mono: Boolean): Int {
        // A box narrower than a glyph still sets one glyph a line (hardLineCount breaks before every one).
        val capacity = maxOf(width / font, 0.0)
        var lines = 0
        text.split('\n').forEach { hard ->
            lines += hardLineCount(hard, capacity, mono)
        }
        return maxOf(lines, 1)
    }

    /** Lines of one hard line, widths in em; [capacity] is the line's width in em. */
    private fun hardLineCount(line: String, capacity: Double, mono: Boolean): Int {
        val space = if (mono) MONO_ADVANCE_EM else ADVANCE_EM
        var lines = 1
        var used = 0.0
        line.split(' ').forEachIndexed { i, word ->
            val advances = advancesOf(word, mono)
            val wordWidth = advances.sum()
            val lead = if (i == 0 || used == 0.0) 0.0 else space
            when {
                used + lead + wordWidth <= capacity -> used += lead + wordWidth
                wordWidth <= capacity -> {
                    lines++
                    used = wordWidth
                }
                else -> {
                    // Too long for any line: it starts on a fresh line and breaks between characters.
                    if (used > 0.0) lines++
                    used = 0.0
                    advances.forEach { advance ->
                        if (used + advance > capacity && used > 0.0) {
                            lines++
                            used = 0.0
                        }
                        used += advance
                    }
                }
            }
        }
        return lines
    }

    private fun advancesOf(word: String, mono: Boolean): List<Double> {
        val out = ArrayList<Double>(word.length)
        var i = 0
        while (i < word.length) {
            val c = word[i]
            if (c.isHighSurrogate() && i + 1 < word.length && word[i + 1].isLowSurrogate()) {
                // An emoji or a supplementary-plane ideograph: one wide glyph.
                out += WIDE_ADVANCE_EM
                i += 2
                continue
            }
            out += advanceEm(c, mono)
            i++
        }
        return out
    }

    /** Wide characters (CJK, fullwidth forms, emoji) in em. */
    const val WIDE_ADVANCE_EM = 1.2

    /** A capital letter in em (round capitals such as O, Q, G run near 0.7 in common sans faces). */
    const val CAPITAL_ADVANCE_EM = 0.75

    /** The widest Latin glyphs in em. */
    const val BROAD_ADVANCE_EM = 0.9

    private const val BROAD = "MWmw@%"

    /** The worst-case advance booked for [c], in em. */
    fun advanceEm(c: Char, mono: Boolean): Double = when {
        isWide(c) -> WIDE_ADVANCE_EM
        mono -> MONO_ADVANCE_EM
        c in BROAD -> BROAD_ADVANCE_EM
        c.isUpperCase() -> CAPITAL_ADVANCE_EM
        else -> ADVANCE_EM
    }

    private fun isWide(c: Char): Boolean {
        val code = c.code
        return code in 0x1100..0x115F ||
            code in 0x2E80..0xA4CF ||
            code in 0xAC00..0xD7A3 ||
            code in 0xF900..0xFAFF ||
            code in 0xFE30..0xFE4F ||
            code in 0xFF00..0xFF60 ||
            code in 0xFFE0..0xFFE6 ||
            code in 0x2600..0x27BF
    }

}

/** What the estimator needs of one block: its type, its words, its heading level and nesting depth. */
data class ReserveBlock(
    val type: ReserveBlockType,
    val text: String,
    val level: Int = 1,
    val depth: Int = 0,
)

/** The Cascade block types the estimator tells apart (plan section 3.2). */
enum class ReserveBlockType {
    PARAGRAPH,
    HEADING,
    BULLET,
    NUMBERED,
    TODO,
    QUOTE,
    CODE,
    DIVIDER,
    ;

    companion object {
        /** The type of a Cascade `typeId`; anything unknown is booked as a paragraph. */
        fun of(typeId: String?): ReserveBlockType = when (typeId) {
            "heading" -> HEADING
            "bullet_list" -> BULLET
            "numbered_list" -> NUMBERED
            "todo" -> TODO
            "quote" -> QUOTE
            "code" -> CODE
            "divider" -> DIVIDER
            else -> PARAGRAPH
        }
    }
}
