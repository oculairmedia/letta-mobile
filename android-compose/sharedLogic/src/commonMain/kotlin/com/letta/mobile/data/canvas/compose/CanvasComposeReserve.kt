package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.BLOCK_GAP
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.BODY_FONT
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.CHROME_HANDLE
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.CHROME_PADDING
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.CODE_FONT
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.CODE_PADDING
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.DIVIDER_HEIGHT
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.HEADING_FONTS
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.HORIZONTAL_PADDING
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.INDENT
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.LINE_HEIGHT_EM
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.MARKER_WIDTH
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.MAX_RESERVE
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.MIN_RESERVE
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.QUOTE_INSET
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.SAFETY_FACTOR
import kotlin.math.ceil

/**
 * The height placement RESERVES for a composed item (letta-mobile-bglj6.9, plan section 3.4, D3).
 *
 * The compose layer cannot measure text: the host has no fonts, and Android and Skiko measure the
 * same words differently. So it books a height that is an overestimate by construction, and the
 * renderer fits the real content WITHIN it (sharedUI `CanvasNoteAutoFit`, letta-mobile-bglj6.11: it
 * shrinks the card to what it measured; content longer than the booking first gets smaller type,
 * down to a floor, and only then a taller card, visually and never written back). Pure arithmetic
 * over characters, with no font, density or Compose API, done in whole world units so every peer
 * and every target books the same number.
 *
 * Why it is an overestimate:
 * - Every character is booked at a WORST-CASE advance for its class ([WorstCaseWrap]): 0.6 em for
 *   ordinary text, 0.75 em for capitals, 0.9 em for the widest Latin glyphs, 1.2 em for wide
 *   (CJK, emoji) characters, 0.65 em for monospace code.
 * - Lines are counted by greedy WORD wrap ([WorstCaseWrap.lines]), which is never fewer than the characters
 *   divided by the line: a word that does not fit starts a new line, a word longer than a line is
 *   broken across lines.
 * - A line is booked at 1.5 x the font size (Compose sets about 1.2), each block gets 8 more, the
 *   chrome is booked at more than the card draws, and the total is multiplied by [SAFETY_FACTOR].
 * - Font sizes are the editor's own (cascade-editor 1.9.2 `CascadeEditorTypography` defaults: body
 *   16, headings 32/28/24/20/18/16 by level, code 14 mono); the inner width removes the card's and
 *   the editor's horizontal padding and each nesting level's indent.
 *
 * The only place it is knowingly short is [MAX_RESERVE]: a note longer than that is booked at the
 * cap and the renderer grows it. The desktop measurement test of C6 (sharedUI
 * `CanvasNoteAutoFitUiTest`) holds the estimate to real renders.
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

    /** Wide characters (CJK, fullwidth forms, emoji) in em. */
    const val WIDE_ADVANCE_EM = 1.2

    /** A capital letter in em (round capitals such as O, Q, G run near 0.7 in common sans faces). */
    const val CAPITAL_ADVANCE_EM = 0.75

    /** The widest Latin glyphs in em. */
    const val BROAD_ADVANCE_EM = 0.9

    /**
     * The height to book for a NOTE, CHECKLIST or CARD document of [blocks] at [width] (the kind's
     * width; [fontScale] is the document style's, 1 for anything compose writes).
     */
    fun reserve(kind: ComposeKind, blocks: List<ReserveBlock>, width: Float = CanvasComposeContract.width(kind), fontScale: Float = 1f): Float {
        require(kind != ComposeKind.TEXT && kind != ComposeKind.GROUP) { "$kind is not a note; use reserveText or the group's children" }
        return ReserveColumn(width.toDouble(), fontScale.toDouble()).reserve(blocks)
    }

    /** [reserve] of a stored Cascade v2 document (`{"version":2,"blocks":[...]}`), the form every note is kept in. */
    fun reserveDocument(documentJson: String, width: Float = CanvasComposeContract.NOTE_WIDTH, fontScale: Float = 1f): Float =
        ReserveColumn(width.toDouble(), fontScale.toDouble()).reserve(blocksOf(documentJson))

    /**
     * The height to book for a TEXT element: its lines at [wrapWidth] (the DrawBox `wrapWidth`)
     * in the TEXT font size, times the safety factor. At least one line; no chrome, no 120 floor.
     */
    fun reserveText(text: String, size: ComposeTextSize, wrapWidth: Float = CanvasComposeContract.width(ComposeKind.TEXT, size)): Float {
        val font = textFont(size)
        val lines = WorstCaseWrap(wrapWidth.toDouble(), font).lines(text)
        val raw = lines * LINE_HEIGHT_EM * font * SAFETY_FACTOR
        return ceil(raw).coerceAtMost(MAX_RESERVE.toDouble()).toFloat()
    }

    fun textFont(size: ComposeTextSize): Double = if (size == ComposeTextSize.HEADING) TEXT_HEADING_FONT else TEXT_BODY_FONT

    /** The blocks of a Cascade v2 document, as [ReserveDocument.blocksOf] reads them. */
    fun blocksOf(documentJson: String): List<ReserveBlock> = ReserveDocument.blocksOf(documentJson)
}

/**
 * A note card's column, [width] world units wide with its type scaled by [fontScale]: the height
 * [CanvasComposeReserve] books for blocks set in it.
 */
class ReserveColumn(private val width: Double, private val fontScale: Double = 1.0) {
    /** The whole card: chrome and blocks, times the safety factor, clamped to the reserve range. */
    fun reserve(blocks: List<ReserveBlock>): Float {
        val body = blocks.sumOf(::blockHeight)
        val raw = (CHROME_HANDLE + CHROME_PADDING + body) * SAFETY_FACTOR
        return ceil(raw).coerceIn(MIN_RESERVE.toDouble(), MAX_RESERVE.toDouble()).toFloat()
    }

    /** One block's booked height: its wrapped lines at its font, plus the block gap. */
    fun blockHeight(block: ReserveBlock): Double {
        if (block.type == ReserveBlockType.DIVIDER) return DIVIDER_HEIGHT + BLOCK_GAP
        val font = fontOf(block) * fontScale
        val inner = width - 2 * HORIZONTAL_PADDING - block.depth * INDENT - insetOf(block.type)
        val code = block.type == ReserveBlockType.CODE
        val lines = WorstCaseWrap(inner, font, mono = code).lines(block.text)
        val extra = if (code) CODE_PADDING else 0.0
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
}
