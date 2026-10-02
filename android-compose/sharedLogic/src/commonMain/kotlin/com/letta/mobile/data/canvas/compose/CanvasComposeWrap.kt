package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.ADVANCE_EM
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.BROAD_ADVANCE_EM
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.CAPITAL_ADVANCE_EM
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.MONO_ADVANCE_EM
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve.WIDE_ADVANCE_EM

/**
 * The lines text takes at [width] world units in a [font] of that size, as [CanvasComposeReserve]
 * books them: hard line breaks kept, greedy word wrap at spaces, a word longer than a line broken
 * across lines, every character at its worst-case advance ([mono] for code).
 */
class WorstCaseWrap(width: Double, font: Double, private val mono: Boolean = false) {
    // A box narrower than a glyph still sets one glyph a line (a break comes before every one).
    private val capacity = maxOf(width / font, 0.0)

    /** Lines [text] takes. Empty text is one line (an empty block still takes a line). */
    fun lines(text: String): Int = maxOf(text.split('\n').sumOf(::hardLines), 1)

    /** Lines of one hard line, widths in em. */
    private fun hardLines(line: String): Int {
        val fill = LineFill()
        line.split(' ').forEachIndexed { i, word -> fill.place(advancesOf(word), afterSpace = i > 0) }
        return fill.lines
    }

    private fun advancesOf(word: String): List<Double> {
        val out = ArrayList<Double>(word.length)
        var i = 0
        while (i < word.length) {
            // An emoji or a supplementary-plane ideograph: one wide glyph.
            val pair = word[i].isHighSurrogate() && word.getOrNull(i + 1)?.isLowSurrogate() == true
            out += if (pair) WIDE_ADVANCE_EM else advanceEm(word[i])
            i += if (pair) 2 else 1
        }
        return out
    }

    /** The worst-case advance booked for [c], in em. */
    private fun advanceEm(c: Char): Double = when {
        isWide(c) -> WIDE_ADVANCE_EM
        mono -> MONO_ADVANCE_EM
        c in BROAD -> BROAD_ADVANCE_EM
        c.isUpperCase() -> CAPITAL_ADVANCE_EM
        else -> ADVANCE_EM
    }

    private fun spaceAdvance(): Double = if (mono) MONO_ADVANCE_EM else ADVANCE_EM

    /** Words set one after another on the lines of one hard line. */
    private inner class LineFill {
        var lines = 1
            private set
        private var used = 0.0

        fun place(advances: List<Double>, afterSpace: Boolean) {
            val wordWidth = advances.sum()
            val lead = if (!afterSpace || used == 0.0) 0.0 else spaceAdvance()
            when {
                used + lead + wordWidth <= capacity -> used += lead + wordWidth
                wordWidth <= capacity -> newLine(wordWidth)
                else -> breakAcross(advances)
            }
        }

        /** Too long for any line: it starts on a fresh line and breaks between characters. */
        private fun breakAcross(advances: List<Double>) {
            if (used > 0.0) newLine(0.0)
            advances.forEach { advance ->
                if (used > 0.0 && used + advance > capacity) newLine(0.0)
                used += advance
            }
        }

        private fun newLine(width: Double) {
            lines++
            used = width
        }
    }

    private companion object {
        const val BROAD = "MWmw@%"

        /** Wide characters: CJK, Hangul, fullwidth forms, and the symbol blocks emoji come from. */
        val WIDE_RANGES = listOf(
            0x1100..0x115F,
            0x2E80..0xA4CF,
            0xAC00..0xD7A3,
            0xF900..0xFAFF,
            0xFE30..0xFE4F,
            0xFF00..0xFF60,
            0xFFE0..0xFFE6,
            0x2600..0x27BF,
        )

        fun isWide(c: Char): Boolean = WIDE_RANGES.any { c.code in it }
    }
}
