package com.letta.mobile.ui.canvas

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasSceneDocument
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.model.canHoldText
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.launch
import io.ak1.drawbox.domain.model.State as DrawBoxState

/** How far (board px) an arrow must be pulled out of a quick-create target to count as one. */
internal const val QUICK_PULL_MIN_PX = 24f
private const val QUICK_CREATE_GLIDE_MILLIS = 420

/** How near, in screen pixels at 100%, a press has to be to an element to pick it. */
internal const val TEXT_HIT_TOLERANCE = 8f

/**
 * Moves the board by [delta] with a smooth ramp up and down rather than in one step, telling
 * [onStep] each part of the move, for anything drawn in screen space that rides with the board.
 */
internal fun CanvasBoard.glideBy(delta: Offset, onStep: (Offset) -> Unit = {}) {
    scope.launch {
        var applied = Offset.Zero
        animate(0f, 1f, animationSpec = tween(QUICK_CREATE_GLIDE_MILLIS, easing = EaseInOutCubic)) { fraction, _ ->
            val step = delta * fraction - applied
            controller.panBy(step)
            onStep(step)
            applied += step
        }
    }
}

/** The one selected element, when it is a shape that holds text. */
private fun selectedTextShape(current: DrawBoxState): Element.Shape? {
    val selected = current.elements.singleOrNull { it.id in current.selectedIds } as? Element.Shape
    return selected?.takeIf { it.canHoldText }
}

/** The z-index above everything on the board. */
private fun topZIndex(current: DrawBoxState): Int {
    return current.elements.maxOfOrNull { it.zIndex } ?: 0
}

/** The active note as the board has it now. */
private fun CanvasBoard.activeLiveNote(): CanvasSceneDocument? {
    val id = ui.activeNoteId ?: return null
    return liveDocuments.firstOrNull { it.id == id }
}

/**
 * Where a pulled arrow is let go comes into view as it is let go, so the shape picked from the
 * menu there lands in sight and the board has no reason to move again when it is made. The
 * arrow and the menu ride with the board.
 */
internal fun CanvasBoard.revealDrop(drop: QuickCreateDrag) {
    val current = controller.state.value
    val base = selectedTextShape(current) ?: CanvasQuickCreate.defaultShape(current.strokeColor, current.strokeWidth)
    val landing = CanvasQuickCreate.landing(base, current.viewport.screenToWorld(drop.to))
    val delta = CanvasViewportFit.panToShow(landing, current.viewport, ui.boardSize, centre = view.compact) ?: return
    glideBy(delta) { step -> ui.quickDrop = ui.quickDrop?.let { it.copy(from = it.from + step, to = it.to + step) } }
}

/** Folds every undo step DrawBox recorded since it had [stepsBefore] into one. */
private fun DrawBoxController.mergeUndoStepsSince(stepsBefore: Int) {
    onIntent(Intent.MergeUndoSteps(state.value.history.size - stepsBefore))
}

/**
 * Adds [next] joined to the element at [from] by an arrow off its [direction] side, as one undo
 * step, and puts the caret in it. [fromNote]: the arrow starts on a note card, which DrawBox
 * does not bind, so the board snaps it.
 */
internal fun CanvasBoard.addJoinedShape(
    from: Rect,
    next: Element.Shape,
    direction: QuickCreateDirection,
    fromNote: Boolean = false,
) {
    val undoStepsBefore = controller.state.value.history.size
    controller.onIntent(Intent.AddElement(next))
    val (start, end) = CanvasQuickCreate.connector(from, next.bounds(), direction)
    CanvasQuickCreate.addArrow(controller, start, end)?.let { arrowId ->
        controller.onIntent(Intent.FinalizeArrowBindings(arrowId))
        // Once bound, it leaves one shape and meets the other square to their sides, and
        // keeps doing so as either moves.
        controller.onIntent(Intent.SmoothConnector(arrowId))
    }
    // A phone shows little of the board, so the new shape is centred for typing into it;
    // a wide board only moves when the new shape would land off its edge. It glides there:
    // a jump loses where the shape came from.
    CanvasViewportFit.panToShow(next.bounds(), controller.state.value.viewport, ui.boardSize, centre = view.compact)
        ?.let { glideBy(it) }
    // The shape and its arrow are one action: one undo takes both.
    controller.mergeUndoStepsSince(undoStepsBefore)
    if (fromNote) snapConnectorToNotes()
    openTextIn(next)
}

/** Snaps the connector just drawn to the note it starts on. */
private fun CanvasBoard.snapConnectorToNotes() {
    val s = session ?: return
    CanvasWorkspaceSupport.snapLatestConnector(controller, s, s.documents(), scope)
}

/** A new note at [frame], joined by an arrow to [from] on its [direction] side, the caret in it. */
internal fun CanvasBoard.addJoinedNote(
    from: Rect,
    frame: CanvasDocumentFrame,
    color: String?,
    direction: QuickCreateDirection,
) {
    val s = session ?: return
    val id = newNoteId()
    scope.launch {
        recordingDocuments("adding a note") {
            runCatching { s.setDocument(id, "", frame = frame, color = color) }.onSuccess {
                val (start, end) = CanvasQuickCreate.connector(from, frame.toRect(), direction)
                // The session's documents, which already hold the note just added.
                if (CanvasQuickCreate.addArrow(controller, start, end) != null) snapConnectorToNotes()
                ui.activeNoteId = id
                ui.focusRequest.documentId = id
            }
        }
    }
}

/** Inserts an empty text at [world] in the board's current text style. */
internal fun DrawBoxController.insertEmptyText(current: DrawBoxState, world: Offset) {
    insertText(
        "",
        world,
        current.currentItemFontSize,
        current.currentItemFontFamilyKey,
        current.currentItemTextAlignment,
        current.strokeColor,
    )
}

/** A text at [world], joined by an arrow to [from], as one undo step, the caret in it. */
internal fun CanvasBoard.addJoinedText(from: Rect, world: Offset, direction: QuickCreateDirection) {
    val current = controller.state.value
    val undoStepsBefore = current.history.size
    val before = current.elements.mapTo(HashSet()) { it.id }
    controller.insertEmptyText(current, world)
    val (start, _) = CanvasQuickCreate.connector(from, Rect(world, world), direction)
    CanvasQuickCreate.addArrow(controller, start, world)
    controller.mergeUndoStepsSince(undoStepsBefore)
    controller.state.value.elements
        .firstOrNull { it.id !in before && it is Element.Text }
        ?.let { openTextIn(it) }
}

/**
 * Miro's quick create: an empty copy of the selected shape (or note) one gap away, joined to it
 * by an arrow, with the caret in it.
 */
internal fun CanvasBoard.quickCreate(direction: QuickCreateDirection) {
    val current = controller.state.value
    val shape = selectedTextShape(current)
    if (shape != null) {
        val next = CanvasQuickCreate.nextShape(shape, direction, topZIndex(current))
        addJoinedShape(shape.bounds(), next, direction)
        return
    }
    val note = activeLiveNote() ?: return
    val frame = note.frame ?: return
    addJoinedNote(frame.toRect(), CanvasQuickCreate.nextFrame(frame, direction), note.color, direction)
}

/** What a pulled arrow comes out of: a shape that holds text, or else the active note. */
private class QuickCreateSource(val shape: Element.Shape?, val note: CanvasSceneDocument?, val from: Rect)

private fun CanvasBoard.quickCreateSource(current: DrawBoxState): QuickCreateSource? {
    val shape = selectedTextShape(current)
    val note = if (shape == null) activeLiveNote() else null
    val from = shape?.bounds() ?: note?.frame?.toRect() ?: return null
    return QuickCreateSource(shape, note, from)
}

/**
 * An arrow pulled out of a quick-create target and let go: what the menu there picked goes
 * where it was let go, joined to the element it came out of.
 */
internal fun CanvasBoard.quickCreateAt(drop: QuickCreateDrag, kind: QuickCreateKind) {
    val current = controller.state.value
    val world = current.viewport.screenToWorld(drop.to)
    val source = quickCreateSource(current) ?: return
    val direction = CanvasQuickCreate.directionToward(source.from, world)
    when (kind) {
        QuickCreateKind.NOTE -> {
            val color = source.note?.color ?: NoteColors.first().hex
            addJoinedNote(source.from, newNoteFrame(world), color, direction)
        }
        QuickCreateKind.TEXT -> addJoinedText(source.from, world, direction)
        else -> addPulledShape(source, drop, kind)
    }
}

private fun CanvasBoard.addPulledShape(source: QuickCreateSource, drop: QuickCreateDrag, kind: QuickCreateKind) {
    val current = controller.state.value
    val world = current.viewport.screenToWorld(drop.to)
    val base = source.shape ?: CanvasQuickCreate.defaultShape(current.strokeColor, current.strokeWidth)
    val next = CanvasQuickCreate.shapeAt(base, world, kind, topZIndex(current))
    // The arrow leaves the side it was pulled from, curving round to where it was let go.
    addJoinedShape(source.from, next, drop.direction, fromNote = source.shape == null)
}

/**
 * A long press or right click: pick what is under it, then open the menu for that, or the add
 * menu on empty board.
 */
internal fun CanvasBoard.openBoardMenu(screen: Offset) {
    val current = controller.state.value
    val world = current.viewport.screenToWorld(screen)
    // DrawBox only selects in the select tool, so the board hit-tests itself and, on an
    // element, moves to the select tool with that element picked - where Miro leaves you too.
    val hit = CanvasWorkspaceSupport.elementAt(current, world, TEXT_HIT_TOLERANCE / current.viewport.scale)
    if (hit != null) selectElement(hit) else controller.clearSelection()
    ui.boardMenu = BoardMenuRequest(
        screen = screen,
        world = world,
        onElement = hit != null,
        canEditText = CanvasWorkspaceSupport.holdsText(hit),
    )
}

/**
 * A finger held on an element adds it to the selection (or takes it back out), on every board.
 * Held on open board it opens the menu, or on a desktop drags out a selection box.
 */
internal fun CanvasBoard.longPressAt(screen: Offset): LongPressOutcome {
    val current = controller.state.value
    val world = current.viewport.screenToWorld(screen)
    val tolerance = FINGER_PICK_TOLERANCE.value * view.boardDensity / current.viewport.scale
    val hit = CanvasWorkspaceSupport.elementAt(current, world, tolerance)
    if (hit == null) {
        if (host.chrome.longPressDrawsSelectionBox) return LongPressOutcome.BOX
        openBoardMenu(screen)
        return LongPressOutcome.DONE
    }
    controller.setMode(Mode.SELECT)
    val ids = current.selectedIds
    controller.selectIds(if (hit.id in ids && ids.size > 1) ids - hit.id else ids + hit.id)
    if (view.compact) ui.multiSelecting = true
    return LongPressOutcome.DONE
}

/**
 * The selection box a held finger drags out: drawn as it goes, and on lifting it selects what it
 * covers, in the select tool, where a selection can be worked on.
 */
internal fun CanvasBoard.dragSelectionBox(drag: SelectionBoxDrag) {
    val viewport = controller.state.value.viewport
    val box = drag.to?.let { boxOf(viewport.screenToWorld(drag.from), viewport.screenToWorld(it)) }
    when {
        box == null -> controller.onIntent(Intent.SetMarqueeRect(null))
        drag.released -> {
            controller.setMode(Mode.SELECT)
            controller.onIntent(Intent.CommitMarquee(box))
        }
        else -> controller.onIntent(Intent.SetMarqueeRect(box))
    }
}

/**
 * Zooms by [factor] about [focal] over a short ease, as a double tap asks for: a jump loses
 * where you were looking.
 */
internal fun CanvasBoard.easeZoom(factor: Float, focal: Offset) {
    scope.launch {
        var applied = 1f
        animate(1f, factor, animationSpec = tween(DOUBLE_TAP_ZOOM_MILLIS)) { value, _ ->
            controller.zoomBy(value / applied, focal)
            applied = value
        }
    }
}
