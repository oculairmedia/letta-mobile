package io.ak1.drawbox.presentation.reducer

import androidx.compose.ui.geometry.Offset
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.canHoldText
import io.ak1.drawbox.domain.model.HISTORY_CAP
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.domain.model.ToolSettings
import io.ak1.drawbox.domain.model.Viewport
import io.ak1.drawbox.domain.model.persistsSettings
import io.ak1.drawbox.domain.usecase.UseCase

/**
 * Pure state reducer implementing the MVI pattern.
 *
 * `(State, Intent) → State`. No side effects, no mutation of the input state.
 *
 * Undo model: snapshot-based. Each intent that mutates `elements` in a way the
 * user would expect to undo (insert, delete, recolor, z-order, transform-commit)
 * pushes the previous element list onto `history` and clears `future`. The
 * per-pixel update intents (`UpdateLatestPath`, `UpdateLatestShape`,
 * `MoveSelected`, `SetElementBounds`, `SetElementRotation`) do NOT push to
 * history — the caller is expected to dispatch [Intent.BeginTransform] once at
 * the start of the gesture.
 */
class Reducer(
    private val useCase: UseCase,
) {
    /**
     * One reducer per concern, tried in turn; each answers the intents it owns and passes the rest
     * on (null). An intent none of them owns leaves the state as it is.
     */
    fun reduce(state: State, intent: Intent): State =
        reduceContent(state, intent)
            ?: reduceSelectedStyle(state, intent)
            ?: reduceToolSettings(state, intent)
            ?: reduceSelection(state, intent)
            ?: reduceEraser(state, intent)
            ?: reduceViewAndHistory(state, intent)
            ?: state

    /** Elements added, drawn, edited, moved and removed. */
    private fun reduceContent(state: State, intent: Intent): State? = when (intent) {
        is Intent.AddElement -> state.snapshot().copy(
            elements = useCase.addElement(intent.element, state.elements),
        )
        is Intent.UpdateElement -> state.copy(
            elements = useCase.updateElement(intent.element, state.elements),
        )
        is Intent.DeleteElement -> state.snapshot().copy(
            elements = useCase.deleteElement(intent.elementId, state.elements),
            selectedIds = state.selectedIds - intent.elementId,
        )
        is Intent.InsertNewPath -> state.adding(
            useCase.insertNewPath(intent.offset, state.strokeColor, state.strokeWidth, state.opacity, state.currentItemStrokeStyle),
        )
        is Intent.UpdateLatestPath -> state.copy(
            elements = useCase.updateLatestPath(intent.newPoint, state.elements, intent.pressure),
        )
        is Intent.InsertText -> state.adding(
            useCase.insertText(intent.text, intent.position, intent.fontSize, intent.fontFamilyKey, intent.alignment, intent.color),
        )
        is Intent.UpdateText -> state.snapshot().copy(
            elements = useCase.updateText(state.elements, intent.id, intent.text),
        )
        is Intent.SyncTextMeasuredHeight -> syncMeasuredHeight(state, intent)
        is Intent.InsertImage -> state.adding(useCase.insertImage(intent.bytes, intent.position, intent.intrinsicSize))
        is Intent.InsertNewShape -> state.adding(
            useCase.insertNewShape(
                intent.shapeType,
                intent.offset,
                state.strokeColor,
                state.strokeWidth,
                state.currentItemCornerRadius,
                state.currentItemStrokeStyle,
                state.currentItemFillColor,
                state.currentItemStrokeEnabled,
            ),
        )
        is Intent.UpdateLatestShape -> state.copy(
            elements = useCase.updateLatestShape(intent.newPoint, state.elements),
        )
        is Intent.DeleteSelected -> {
            if (state.selectedIds.isEmpty()) state
            else state.snapshot().copy(
                elements = useCase.deleteSelected(state.elements, state.selectedIds),
                selectedIds = emptySet(),
            )
        }
        is Intent.BeginTransform -> state.snapshot()
        is Intent.EndTransform -> state
        else -> reduceGeometry(state, intent)
    }

    /** An element's geometry changed in place, bound arrows following it. */
    private fun reduceGeometry(state: State, intent: Intent): State? = when (intent) {
        is Intent.MoveSelected -> state.copy(
            elements = useCase.propagateBindings(
                useCase.translateSelected(state.elements, state.selectedIds, intent.delta),
            ),
        )
        is Intent.SetElementBounds -> state.copy(
            elements = useCase.propagateBindings(
                useCase.setElementBounds(state.elements, intent.id, intent.bounds),
            ),
        )
        is Intent.SetElementRotation -> state.copy(
            elements = useCase.propagateBindings(
                useCase.setElementRotation(state.elements, intent.id, intent.rotationDegrees),
            ),
        )
        is Intent.SetElementPoints -> state.copy(
            elements = useCase.setElementPoints(state.elements, intent.id, intent.points),
        )
        is Intent.SetLineBend -> state.copy(
            elements = useCase.setLineBend(state.elements, intent.id, intent.bend),
        )
        is Intent.FinalizeArrowBindings -> state.copy(
            // Don't snapshot here — this intent is always dispatched at the END
            // of a gesture whose START already snapshotted (InsertNewShape for
            // a freshly drawn arrow, BeginTransform for an endpoint drag). A
            // second snapshot here would split a single user action across two
            // undo entries.
            elements = useCase.propagateBindings(
                useCase.finalizeArrowBindings(state.elements, intent.id),
            ),
        )
        else -> null
    }

    /**
     * Short-circuit on no-op so identical layouts in successive frames don't generate State copies
     * (recompositions, listener wakeups, sync-layer chatter). The 0.5px threshold matches the
     * renderer's drift gate.
     */
    private fun syncMeasuredHeight(state: State, intent: Intent.SyncTextMeasuredHeight): State {
        val target = state.elements.firstOrNull { it.id == intent.id } as? Element.Text
        if (target == null || kotlin.math.abs(target.measuredHeight - intent.height) < 0.5f) return state
        return state.copy(elements = useCase.syncTextMeasuredHeight(state.elements, intent.id, intent.height))
    }

    /** The selected elements restyled or restacked, as one undo step. */
    private fun reduceSelectedStyle(state: State, intent: Intent): State? = when (intent) {
        is Intent.SetSelectedFontSize -> state.editSelected { e, ids -> useCase.setSelectedFontSize(e, ids, intent.size) }
        is Intent.SetSelectedTextAlignment -> state.editSelected { e, ids -> useCase.setSelectedTextAlignment(e, ids, intent.alignment) }
        is Intent.SetSelectedFontFamily -> state.editSelected { e, ids -> useCase.setSelectedFontFamily(e, ids, intent.fontFamilyKey) }
        is Intent.SetSelectedStrokeColor -> state.editSelected { e, ids -> useCase.setSelectedStrokeColor(e, ids, intent.color) }
        is Intent.SetSelectedFillColor -> state.editSelected { e, ids -> useCase.setSelectedFillColor(e, ids, intent.color) }
        is Intent.SetSelectedStrokeEnabled -> state.editSelected { e, ids -> useCase.setSelectedStrokeEnabled(e, ids, intent.enabled) }
        is Intent.SetSelectedStrokeWidth -> state.editSelected { e, ids -> useCase.setSelectedStrokeWidth(e, ids, intent.width) }
        is Intent.SetSelectedCornerRadius -> state.editSelected { e, ids -> useCase.setSelectedCornerRadius(e, ids, intent.radius) }
        is Intent.SetSelectedStrokeStyle -> state.editSelected { e, ids -> useCase.setSelectedStrokeStyle(e, ids, intent.style) }
        is Intent.SetSelectedTextColor -> state.editSelected { e, ids -> useCase.setSelectedTextColor(e, ids, intent.color) }
        is Intent.BringSelectionToFront -> state.editSelected { e, ids -> useCase.bringToFront(e, ids) }
        is Intent.SendSelectionToBack -> state.editSelected { e, ids -> useCase.sendToBack(e, ids) }
        else -> null
    }

    /** What the next element drawn will look like, and which tool draws it. */
    private fun reduceToolSettings(state: State, intent: Intent): State? = when (intent) {
        is Intent.SetStrokeColor -> state.copy(strokeColor = intent.color)
        is Intent.SetStrokeWidth -> state.copy(strokeWidth = intent.width)
        is Intent.SetCornerRadius -> state.copy(currentItemCornerRadius = intent.radius)
        is Intent.SetStrokeStyle -> state.copy(currentItemStrokeStyle = intent.style)
        is Intent.SetFillColor -> state.copy(currentItemFillColor = intent.color)
        is Intent.SetStrokeEnabled -> state.copy(currentItemStrokeEnabled = intent.enabled)
        is Intent.SetFontSize -> state.copy(currentItemFontSize = intent.size)
        is Intent.SetFontFamily -> state.copy(currentItemFontFamilyKey = intent.fontFamilyKey)
        is Intent.SetTextAlignment -> state.copy(currentItemTextAlignment = intent.alignment)
        is Intent.SetOpacity -> state.copy(opacity = intent.opacity)
        is Intent.SetBgColor -> state.copy(bgColor = intent.bgColor)
        is Intent.SetBackgroundPattern -> state.copy(bgPattern = intent.pattern)
        is Intent.SetMode -> state.switchedTo(intent.mode)
        is Intent.SetEraserSize -> state.copy(eraserSize = intent.size)
        is Intent.SetSelectInsideHollowShapes -> state.copy(selectInsideHollowShapes = intent.enabled)
        else -> null
    }

    private fun reduceSelection(state: State, intent: Intent): State? = when (intent) {
        is Intent.SelectAt -> {
            val hit = useCase.hitTopmost(state.elements, intent.offset, intent.tolerance, state.selectInsideHollowShapes)
            state.copy(
                selectedIds = when {
                    hit == null -> emptySet()
                    intent.additive && hit.id in state.selectedIds -> state.selectedIds - hit.id
                    intent.additive -> state.selectedIds + hit.id
                    else -> setOf(hit.id)
                },
            )
        }
        is Intent.RequestTextEditAt -> {
            // Select the tapped text so the edit target is the sole selection;
            // the controller emits Event.TextEditRequested off this. Leave
            // selection untouched when the topmost hit isn't a text element.
            val hit = useCase.hitTopmost(state.elements, intent.offset, intent.tolerance, state.selectInsideHollowShapes)
            if (hit.holdsText()) state.copy(selectedIds = setOf(hit!!.id)) else state
        }
        is Intent.SetMarqueeRect -> state.copy(marqueeRect = intent.rect)
        is Intent.CommitMarquee -> state.copy(
            selectedIds = useCase.selectInRect(state.elements, intent.rect),
            marqueeRect = null,
        )
        is Intent.ClearSelection -> state.copy(selectedIds = emptySet())
        is Intent.SelectIds -> state.copy(selectedIds = state.elements.map { it.id }.filter { it in intent.ids }.toSet())
        else -> null
    }

    private fun reduceEraser(state: State, intent: Intent): State? = when (intent) {
        // Defensive reset: a previous session that was cancelled mid-sweep
        // without an EndErase would leave the dirty flag stuck.
        is Intent.BeginErase, is Intent.EndErase -> if (state.erasingSessionDirty) state.copy(erasingSessionDirty = false) else state
        is Intent.EraseAt -> eraseAt(state, intent)
        else -> null
    }

    /**
     * Snapshot lazily on the FIRST removal of the current session — a tap or drag through empty
     * space pushes nothing to history. The eraseAt helper returns the same list instance when no
     * element is hit, so we can detect a miss by reference and short-circuit.
     */
    private fun eraseAt(state: State, intent: Intent.EraseAt): State {
        val next = useCase.eraseAt(state.elements, intent.point, intent.radius)
        if (next === state.elements) return state
        val base = if (!state.erasingSessionDirty) state.snapshot() else state
        return base.copy(
            elements = next,
            erasingSessionDirty = true,
            selectedIds = state.selectedIds.intersect(next.map { it.id }.toSet()),
        )
    }

    private fun reduceViewAndHistory(state: State, intent: Intent): State? = when (intent) {
        is Intent.PanBy -> state.copy(viewport = state.viewport.panBy(intent.delta))
        is Intent.ZoomBy -> state.copy(viewport = state.viewport.zoomBy(intent.factor, intent.focalScreen))
        is Intent.ZoomTo -> state.copy(viewport = state.viewport.zoomTo(intent.targetScale, intent.focalScreen))
        is Intent.ResetCamera -> state.copy(viewport = Viewport())
        is Intent.SetTempPan -> state.copy(tempPanActive = intent.active)
        is Intent.Undo -> undo(state)
        is Intent.Redo -> redo(state)
        is Intent.MergeUndoSteps -> if (intent.count < 2) state else state.copy(
            // The oldest of the merged steps is the state to go back to; the rest go.
            history = state.history.dropLast((intent.count - 1).coerceAtMost((state.history.size - 1).coerceAtLeast(0))),
        )
        // A fresh drawing, but the host's picking preference is not drawing content.
        is Intent.Reset -> State(selectInsideHollowShapes = state.selectInsideHollowShapes)
        else -> null
    }

    private fun undo(state: State): State {
        val prev = state.history.lastOrNull() ?: return state
        return state.copy(
            elements = prev,
            history = state.history.dropLast(1),
            future = (state.future + listOf(state.elements)).takeLast(HISTORY_CAP),
            selectedIds = state.selectedIds.intersect(prev.map { it.id }.toSet()),
        )
    }

    private fun redo(state: State): State {
        val next = state.future.lastOrNull() ?: return state
        return state.copy(
            elements = next,
            future = state.future.dropLast(1),
            history = (state.history + listOf(state.elements)).takeLast(HISTORY_CAP),
            selectedIds = state.selectedIds.intersect(next.map { it.id }.toSet()),
        )
    }

    /**
     * Topmost element hit at [point] within [tolerance], or null. Exposes the
     * pick used by [reduce] so the controller can resolve edit targets for
     * [Event.TextEditRequested] without duplicating hit-test logic.
     */
    fun hitTopmost(
        elements: List<Element>,
        point: Offset,
        tolerance: Float,
        hollowInterior: Boolean = false,
    ): Element? = useCase.hitTopmost(elements, point, tolerance, hollowInterior)

    /** Push current elements onto [State.history] and clear [State.future]. */
    private fun State.snapshot(): State = copy(
        history = (history + listOf(elements)).takeLast(HISTORY_CAP),
        future = emptyList(),
    )

    /** [element] added as one undo step. */
    private fun State.adding(element: Element): State = snapshot().copy(elements = useCase.addElement(element, elements))

    /** The selected elements changed by [edit] as one undo step; nothing when none is selected. */
    private inline fun State.editSelected(edit: (List<Element>, Set<String>) -> List<Element>): State =
        if (selectedIds.isEmpty()) this else snapshot().copy(elements = edit(elements, selectedIds))

    /**
     * Per-tool memory: capture the outgoing mode's style snapshot if it's a persisted mode, then
     * restore the incoming mode's saved snapshot (if any). Utility modes (SELECT, ERASER, PAN)
     * pass through settings unchanged in both directions.
     */
    private fun State.switchedTo(next: Mode): State {
        val nextMemory = if (mode.persistsSettings()) toolMemory + (mode to toolSettings()) else toolMemory
        val restored = if (next.persistsSettings()) nextMemory[next] else null
        return copy(
            mode = next,
            selectedIds = if (next == Mode.SELECT) selectedIds else emptySet(),
            marqueeRect = null,
            toolMemory = nextMemory,
        ).restoring(restored)
    }

    private fun State.toolSettings() = ToolSettings(
        strokeColor = strokeColor,
        strokeWidth = strokeWidth,
        strokeStyle = currentItemStrokeStyle,
        cornerRadius = currentItemCornerRadius,
        fillColor = currentItemFillColor,
        strokeEnabled = currentItemStrokeEnabled,
        fontSize = currentItemFontSize,
        fontFamilyKey = currentItemFontFamilyKey,
        textAlignment = currentItemTextAlignment,
    )

    /** The style a tool last had, when it had one saved; the current style otherwise. */
    private fun State.restoring(saved: ToolSettings?): State {
        if (saved == null) return this
        return copy(
            strokeColor = saved.strokeColor,
            strokeWidth = saved.strokeWidth,
            currentItemStrokeStyle = saved.strokeStyle,
            currentItemCornerRadius = saved.cornerRadius,
            // A saved null fill means stroke-only.
            currentItemFillColor = saved.fillColor,
            currentItemStrokeEnabled = saved.strokeEnabled,
            currentItemFontSize = saved.fontSize,
            currentItemFontFamilyKey = saved.fontFamilyKey,
            currentItemTextAlignment = saved.textAlignment,
        )
    }
}

/** Whether [this] is something a text edit applies to: a text element or a text-holding shape. */
internal fun Element?.holdsText(): Boolean =
    this is Element.Text || (this is Element.Shape && this.canHoldText)
