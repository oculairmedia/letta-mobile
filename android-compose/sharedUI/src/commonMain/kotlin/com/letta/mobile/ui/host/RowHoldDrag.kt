package com.letta.mobile.ui.host

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange

/**
 * What a held row does when the finger then moves on: a touch row menu
 * opens on a long-press, and a list that reorders (RosterList) lets the same hold become a drag.
 * The touch [LettaHost.rowMenu] reads [LocalRowHoldDrag]: once the held pointer travels past the
 * touch slop it reports the drag here, in pixels along the list.
 */
@Stable
interface RowHoldDrag {
    fun onStart()

    fun onDrag(deltaY: Float)

    fun onEnd()
}

/** The drag the row under this point offers; null for rows that do not move. */
val LocalRowHoldDrag = compositionLocalOf<RowHoldDrag?> { null }

/**
 * Follows one pointer for a [RowHoldDrag]: the drag starts once the pointer is more than [slop]
 * from [origin], then each step's vertical travel is reported; [finish] ends it.
 */
class RowDragTracker(private val origin: Offset, private val slop: Float, private val drag: RowHoldDrag) {
    var dragging: Boolean = false
        private set

    /** Takes one pressed [change] of the followed pointer. */
    fun follow(change: PointerInputChange) {
        if (dragging) {
            drag.onDrag(change.position.y - change.previousPosition.y)
        } else if ((change.position - origin).getDistance() > slop) {
            dragging = true
            drag.onStart()
        }
    }

    /** Ends the drag (when there was one); true when the pointer dragged. */
    fun finish(): Boolean {
        if (dragging) drag.onEnd()
        return dragging
    }
}
