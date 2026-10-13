package com.letta.mobile.ui.components.list

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import com.letta.mobile.ui.host.RowHoldDrag
import kotlin.math.abs

/**
 * A row being dragged to a new place in its section (letta-mobile-ckmub.3.25). The row follows the
 * pointer ([offset], drawn as a translation); each time its centre crosses a neighbour's, the list
 * swaps the two in a preview order ([previewKey] at [previewIndex]) and its siblings glide aside.
 * On release the host's [RosterReorder] hears the move once; the preview stays drawn until the
 * host's sections change, so the row never jumps back while the new order is being saved.
 */
@Stable
internal class RosterDrag {
    /** The row under the pointer, null when nothing is being dragged. */
    var key by mutableStateOf<String?>(null)
        private set

    /** How far the dragged row is drawn from its place in the current order, in pixels. */
    var offset by mutableFloatStateOf(0f)
        private set

    private var previewKey by mutableStateOf<String?>(null)
    private var previewIndex by mutableIntStateOf(0)

    /** The rows the preview was made against; the preview is dropped once the host's rows differ. */
    private var previewBase by mutableStateOf<List<String>?>(null)

    /** [base] with the dragged (or just dropped) row in its preview place. */
    fun <T> layoutOver(base: RosterLayout<T>): RosterLayout<T> {
        val moved = previewKey ?: return base
        if (previewBase != base.signature) return base
        return base.moved(moved, previewIndex)
    }

    fun start(row: RosterLine.Row<*>, base: RosterLayout<*>) {
        key = row.key
        offset = 0f
        previewKey = row.key
        previewIndex = row.indexInSection
        previewBase = base.signature
    }

    /**
     * Moves the dragged row by [delta] pixels within [shown] (the order on screen), swapping it with
     * the neighbour whose centre it crosses. [list] is where the rows are laid out; [gap] is the
     * space between two rows. A swap waits for the list to lay the previous one out.
     */
    fun dragBy(delta: Float, shown: RosterLayout<*>, list: LazyListState, gap: Float) {
        val dragged = key ?: return
        offset += delta
        val line = shown.lineOf(dragged)
        val items = list.layoutInfo.visibleItemsInfo
        val self = items.firstOrNull { it.key == dragged }?.takeIf { it.index == line } ?: return
        val direction = if (offset > 0f) 1 else -1
        val row = shown.lines.getOrNull(line) as? RosterLine.Row<*> ?: return
        val neighbour = shown.lines.getOrNull(line + direction) as? RosterLine.Row<*> ?: return
        if (neighbour.sectionKey != row.sectionKey) return
        val other = items.firstOrNull { it.index == line + direction } ?: return
        if (!crosses(self, other, direction)) return
        previewIndex = row.indexInSection + direction
        offset -= direction * (other.size + gap)
    }

    private fun crosses(self: LazyListItemInfo, other: LazyListItemInfo, direction: Int): Boolean {
        val centre = self.offset + offset + self.size / 2f
        val otherCentre = other.offset + other.size / 2f
        return if (direction > 0) centre > otherCentre else centre < otherCentre
    }

    /** Ends the drag; returns the dragged row and its new place when it moved, for the host. */
    fun <T> drop(base: RosterLayout<T>): Pair<RosterLine.Row<T>, Int>? {
        val dragged = key ?: return null
        key = null
        offset = 0f
        val from = base.rows.firstOrNull { it.key == dragged }
        val move = from?.let { it to previewIndex }?.takeIf { previewIndex != it.first.indexInSection && previewBase == base.signature }
        if (move == null) previewKey = null
        return move
    }
}

/** What one row's gestures report to its list's [RosterDrag]; rebuilt per composition, read through a stable holder. */
internal class RosterDragCallbacks(
    val onStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    val onEnd: () -> Unit,
)

/**
 * The stable face of a row's drag: the touch row menu holds it as its [RowHoldDrag] (hold, then
 * drag) and the pointer drag modifier calls it; both read the latest [callbacks].
 */
internal class RosterRowDrag : RowHoldDrag {
    var callbacks: RosterDragCallbacks? = null

    override fun onStart() {
        callbacks?.onStart?.invoke()
    }

    override fun onDrag(deltaY: Float) {
        callbacks?.onDrag?.invoke(deltaY)
    }

    override fun onEnd() {
        callbacks?.onEnd?.invoke()
    }
}

/**
 * A mouse drags a row straight away, vertically; a touch drags through the row menu's hold. It
 * watches the Initial pass, so once the press travels past the slop it claims the gesture before
 * the row's own click and the host's long-press menu see it: a drag neither clicks nor opens a menu.
 */
internal fun Modifier.rosterPointerDrag(enabled: Boolean, drag: RosterRowDrag): Modifier =
    if (!enabled) {
        this
    } else {
        this.pointerInput(drag) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.type == PointerType.Mouse) followMouseDrag(down, viewConfiguration.touchSlop, drag)
            }
        }
    }

/** Follows [down]'s pointer until it lifts: a drag through [drag] once it has moved past [slop]. */
private suspend fun AwaitPointerEventScope.followMouseDrag(down: PointerInputChange, slop: Float, drag: RowHoldDrag) {
    var dragging = false
    var travelled = 0f
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) break
        val step = change.position.y - change.previousPosition.y
        travelled += step
        if (!dragging && abs(travelled) > slop) {
            dragging = true
            drag.onStart()
        } else if (dragging) {
            drag.onDrag(step)
        }
        if (dragging) change.consume()
    }
    if (dragging) drag.onEnd()
}
