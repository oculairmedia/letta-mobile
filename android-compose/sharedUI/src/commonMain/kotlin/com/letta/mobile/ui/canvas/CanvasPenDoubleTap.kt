package com.letta.mobile.ui.canvas

import kotlin.math.hypot

/**
 * Two quick pen taps in one place, while the pen is drawing.
 *
 * In a drawing tool the canvas takes the pen as ink, so DrawBox never sees the pen's
 * clicks and its double click cannot open a shape's text. Every pen tap there draws a
 * dot. This notices the second tap, so the board can take the first tap's dot back and
 * type into the shape instead. Positions are window-logical, as the pen reports them.
 */
internal class CanvasPenDoubleTap(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var downX = 0f
    private var downY = 0f
    private var downMillis = 0L
    private var travelled = 0f
    private var lastTap: Tap? = null

    /** The dot a first tap drew, when [down] is the second tap of a double tap; otherwise null. */
    fun down(x: Float, y: Float): Tap? {
        val now = clock()
        val previous = lastTap
        lastTap = null
        downX = x
        downY = y
        downMillis = now
        travelled = 0f
        if (previous == null) return null
        val near = hypot(x - previous.x, y - previous.y) <= DOUBLE_TAP_SLOP
        return previous.takeIf { near && now - previous.atMillis <= DOUBLE_TAP_MILLIS }
    }

    fun move(x: Float, y: Float) {
        travelled = maxOf(travelled, hypot(x - downX, y - downY))
    }

    /** Remembers a short, still stroke as a tap, with the id of the dot it drew. */
    fun up(x: Float, y: Float, dotId: String?) {
        move(x, y)
        val now = clock()
        lastTap = if (travelled <= TAP_SLOP && now - downMillis <= TAP_MILLIS) Tap(downX, downY, now, dotId) else null
    }

    fun clear() {
        lastTap = null
    }

    data class Tap(val x: Float, val y: Float, val atMillis: Long, val dotId: String?)

    internal companion object {
        const val TAP_SLOP = 6f
        const val TAP_MILLIS = 300L
        const val DOUBLE_TAP_SLOP = 20f
        const val DOUBLE_TAP_MILLIS = 350L
    }
}
