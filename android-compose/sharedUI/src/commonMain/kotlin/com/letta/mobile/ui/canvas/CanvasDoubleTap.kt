package com.letta.mobile.ui.canvas

import kotlin.math.hypot

/**
 * Two quick taps in one place, for input the board sees one sample at a time: the pen while it
 * draws, and a finger on open board.
 *
 * [down] answers whether this press finishes a double tap; [up] decides whether the contact that
 * just lifted was a tap at all (short and still). A tap can carry a [Tap.mark], such as the id of
 * the dot a pen tap drew, so the double tap can take it back.
 */
internal class CanvasDoubleTap(
    private val tapSlop: Float = PEN_TAP_SLOP,
    private val doubleTapSlop: Float = PEN_DOUBLE_TAP_SLOP,
    private val tapMillis: Long = TAP_MILLIS,
    private val doubleTapMillis: Long = DOUBLE_TAP_MILLIS,
) {
    private var downX = 0f
    private var downY = 0f
    private var downMillis = 0L
    private var travelled = 0f
    private var lastTap: Tap? = null

    /** The first tap, when this press at [atMillis] is the second of a double tap; otherwise null. */
    fun down(x: Float, y: Float, atMillis: Long): Tap? {
        val previous = lastTap
        lastTap = null
        downX = x
        downY = y
        downMillis = atMillis
        travelled = 0f
        if (previous == null) return null
        val near = hypot(x - previous.x, y - previous.y) <= doubleTapSlop
        return previous.takeIf { near && atMillis - previous.atMillis <= doubleTapMillis }
    }

    fun move(x: Float, y: Float) {
        travelled = maxOf(travelled, hypot(x - downX, y - downY))
    }

    /** Remembers a short, still contact as a tap, with its [mark]. */
    fun up(x: Float, y: Float, atMillis: Long, mark: String? = null) {
        move(x, y)
        lastTap = if (travelled <= tapSlop && atMillis - downMillis <= tapMillis) Tap(downX, downY, atMillis, mark) else null
    }

    fun clear() {
        lastTap = null
    }

    data class Tap(val x: Float, val y: Float, val atMillis: Long, val mark: String?)

    internal companion object {
        /** Window-logical pixels, as the pen reports them. */
        const val PEN_TAP_SLOP = 6f
        const val PEN_DOUBLE_TAP_SLOP = 20f
        const val TAP_MILLIS = 300L
        const val DOUBLE_TAP_MILLIS = 350L
    }
}
