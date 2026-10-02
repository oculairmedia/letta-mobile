package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.ak1.drawbox.domain.model.Viewport

/**
 * Where the board is on screen, in the root's pixels, kept from its layout without a state write
 * (the keyboard's camera reads it inside its own effect; see [CanvasKeyboardCamera]).
 */
internal class CanvasBoardFrame {
    var top: Float = 0f
    var width: Float = 0f
    var rootHeight: Float = 0f
}

/** One frame of the keyboard as the board sees it, in px. */
internal data class CanvasKeyboardFrame(
    /** The keyboard's own height; zero once it is gone. */
    val ime: Int,
    /** What covers the window's foot: the keyboard, the system bars, or host chrome riding on them. */
    val obstruction: Int,
    /** What is being typed into, in board units; null when nothing is. */
    val target: Rect?,
    /** Kept clear at the top of the board, for the selection bar floating over the target. */
    val topReserve: Int,
)

/**
 * The camera's answer to the keyboard: it keeps what is being typed into above the keyboard and
 * whatever rides on it, and gives back what it took when the keyboard goes.
 *
 * As the keyboard rises the camera pans only as far as the target needs, frame by frame, so the
 * board moves with the keyboard rather than after it. As it falls the camera returns in step with
 * it: at half the keyboard's height, half the pan is given back; with the keyboard gone, the board
 * is where it was before it came. Panned by hand in between, the board is the person's: the camera
 * forgets its pan and moves nothing back.
 */
internal class CanvasKeyboardCamera {
    private var lift = Offset.Zero
    private var peakIme = 0
    private var after: Viewport? = null

    /**
     * The pan to apply for a keyboard [ime] px tall, the [target] in board units, the [viewport]
     * as it is and the [band] of the board left visible (board px); null when the camera stays.
     * Call [moved] with the viewport the pan produced.
     */
    fun step(ime: Int, target: Rect?, viewport: Viewport, band: Rect): Offset? {
        if (after != null && viewport != after) {
            lift = Offset.Zero
            after = null
        }
        if (ime <= 0) {
            val back = lift
            lift = Offset.Zero
            peakIme = 0
            after = null
            return if (back == Offset.Zero) null else -back
        }
        val delta = if (ime >= peakIme) {
            peakIme = ime
            target?.let { CanvasViewportFit.panIntoBand(it, viewport, band) }
        } else {
            val keep = lift * (ime.toFloat() / peakIme)
            (keep - lift).takeIf { it.getDistance() >= MIN_PAN }
        }
        if (delta != null) lift += delta
        return delta
    }

    /** The viewport a pan from [step] produced, so a later hand pan is told apart from the camera's. */
    fun moved(viewport: Viewport) {
        after = if (lift == Offset.Zero) null else viewport
    }

    private companion object {
        const val MIN_PAN = 0.5f
    }
}

/**
 * The band of a board [frame] left visible over an [obstruction] px tall at the window's foot,
 * less the board's own foot ([footPx], its tool bar and formatting riding on the obstruction) and
 * a [marginPx], below a [topReserve]; in board px.
 */
internal fun keyboardBand(frame: CanvasBoardFrame, obstruction: Int, footPx: Float, marginPx: Float, topReserve: Int): Rect {
    val visibleBottom = frame.rootHeight - obstruction - frame.top
    return Rect(0f, topReserve.toFloat(), frame.width, visibleBottom - footPx - marginPx)
}
