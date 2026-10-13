package com.letta.mobile.ui.components.list

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import com.letta.mobile.ui.host.RowDragTracker
import com.letta.mobile.ui.host.RowHoldDrag

/** A row moved within its own section: the row (by key) and its new place there. */
@Immutable
internal data class RosterMove(val rowKey: String, val toIndex: Int)

/** Where a dragged row is laid out ([self]) and the neighbour it moves towards ([other], one place in [direction]). */
private class RosterDragStep(val self: LazyListItemInfo, val other: LazyListItemInfo, val row: RosterLine.Row<*>, val direction: Int)

/**
 * A row being dragged to a new place in its section (letta-mobile-ckmub.3.25). The row follows the
 * pointer ([offset], drawn as a translation); each time its centre crosses a neighbour's, the list
 * swaps the two in a preview order ([preview]) and its siblings glide aside. On release the host's
 * [RosterReorder] hears the move once; the preview stays drawn until the host's sections change, so
 * the row never jumps back while the new order is being saved.
 */
@Stable
internal class RosterDrag {
    /** The row under the pointer, null when nothing is being dragged. */
    var key by mutableStateOf<String?>(null)
        private set

    /** How far the dragged row is drawn from its place in the current order, in pixels. */
    var offset by mutableFloatStateOf(0f)
        private set

    private var preview by mutableStateOf<RosterMove?>(null)

    /** The rows the preview was made against; the preview is dropped once the host's rows differ. */
    private var previewBase by mutableStateOf<List<String>?>(null)

    /** [base] with the dragged (or just dropped) row in its preview place. */
    fun <T> layoutOver(base: RosterLayout<T>): RosterLayout<T> {
        val move = preview?.takeIf { previewBase == base.signature } ?: return base
        return base.moved(move)
    }

    fun start(row: RosterLine.Row<*>, base: RosterLayout<*>) {
        key = row.key
        offset = 0f
        preview = RosterMove(row.key, row.indexInSection)
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
        val step = stepTowards(dragged, shown, list.layoutInfo.visibleItemsInfo)?.takeIf(::crosses) ?: return
        preview = RosterMove(dragged, step.row.indexInSection + step.direction)
        offset -= step.direction * (step.other.size + gap)
    }

    /** The dragged row's layout and the neighbour it is heading for, when both are laid out in its section. */
    private fun stepTowards(dragged: String, shown: RosterLayout<*>, items: List<LazyListItemInfo>): RosterDragStep? {
        val line = shown.lineOf(dragged)
        val self = items.firstOrNull { it.key == dragged }?.takeIf { it.index == line } ?: return null
        val direction = if (offset > 0f) 1 else -1
        val row = shown.lines.getOrNull(line) as? RosterLine.Row<*> ?: return null
        val neighbour = (shown.lines.getOrNull(line + direction) as? RosterLine.Row<*>)?.takeIf { it.sectionKey == row.sectionKey } ?: return null
        val other = items.firstOrNull { it.index == line + direction && it.key == neighbour.key } ?: return null
        return RosterDragStep(self, other, row, direction)
    }

    private fun crosses(step: RosterDragStep): Boolean {
        val centre = step.self.offset + offset + step.self.size / 2f
        val otherCentre = step.other.offset + step.other.size / 2f
        return if (step.direction > 0) centre > otherCentre else centre < otherCentre
    }

    /** Ends the drag; returns the dragged row and its new place when it moved, for the host. */
    fun <T> drop(base: RosterLayout<T>): Pair<RosterLine.Row<T>, Int>? {
        val dragged = key ?: return null
        key = null
        offset = 0f
        val move = movedRow(dragged, base)
        if (move == null) preview = null
        return move
    }

    private fun <T> movedRow(dragged: String, base: RosterLayout<T>): Pair<RosterLine.Row<T>, Int>? {
        val target = preview?.toIndex?.takeIf { previewBase == base.signature } ?: return null
        val from = base.rows.firstOrNull { it.key == dragged } ?: return null
        return (from to target).takeIf { target != from.indexInSection }
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
                if (down.type == PointerType.Mouse) followMouseDrag(RowDragTracker(down.position, viewConfiguration.touchSlop, drag), down)
            }
        }
    }

/** Follows [down]'s pointer until it lifts, claiming each step once [tracker] is dragging. */
private suspend fun AwaitPointerEventScope.followMouseDrag(tracker: RowDragTracker, down: PointerInputChange) {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }?.takeIf { it.pressed } ?: break
        tracker.follow(change)
        if (tracker.dragging) change.consume()
    }
    tracker.finish()
}
