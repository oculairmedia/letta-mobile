package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How far a double tap on open board zooms in, and how long it takes to ease there. */
internal const val DOUBLE_TAP_ZOOM = 2f
internal const val DOUBLE_TAP_ZOOM_MILLIS = 220

/** A fingertip covers far more than a line is wide. The mouse and pen keep DrawBox's 12dp. */
internal val FINGER_PICK_TOLERANCE = 24.dp
private val POINTER_PICK_TOLERANCE = 12.dp

/**
 * Whether the press DrawBox is picking for came from a finger.
 *
 * DrawBox's tap and drag detectors do not say which pointer pressed, so the board notes a touch
 * press as it passes and DrawBox's pick, in the same dispatch, reads it within
 * [FINGER_WINDOW_MILLIS].
 */
internal class CanvasFingerRecency {
    private var lastFingerMillis = Long.MIN_VALUE

    fun touched(atMillis: Long) {
        lastFingerMillis = atMillis
    }

    fun pickTolerance(nowMillis: Long): Dp =
        if (lastFingerMillis != Long.MIN_VALUE && nowMillis - lastFingerMillis <= FINGER_WINDOW_MILLIS) {
            FINGER_PICK_TOLERANCE
        } else {
            POINTER_PICK_TOLERANCE
        }

    private companion object {
        const val FINGER_WINDOW_MILLIS = 250L
    }
}

/** The rectangle with [a] and [b] as opposite corners, whichever way the finger dragged. */
internal fun boxOf(a: Offset, b: Offset): Rect =
    Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
