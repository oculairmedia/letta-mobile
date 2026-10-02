package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.bounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Room kept at the top of the board when the keyboard moves the camera: for the title and sync bar
 * where there is one, and for the selection bar (a note's colour and size) floating over the target.
 */
private val KEYBOARD_TOP_RESERVE = 72.dp

/** Between what is typed into and the bars riding on the keyboard, beyond the chrome's own inset. */
private val KEYBOARD_MARGIN = LettaDimens.Space.md
private const val KEYBOARD_SETTLE_MS = 150L

/** Where the camera goes on its own: the open-time fit, a region asked for, and the keyboard. */
@Composable
internal fun CanvasBoardCameraEffects(board: CanvasBoard) {
    val ui = board.ui
    // A board drawn on a desktop is mostly off the edge of a phone, which opened on an empty
    // corner of it. On a phone the board opens fitted, once, and never zoomed in past 100%.
    // Decided at the first measured layout after the load: a wide pane narrowed later must not
    // reset the camera and the tool under the user.
    val layout = board.view.resolvedLayout
    LaunchedEffect(ui.initialLoadDone, layout) { board.fitOnOpen(layout) }
    // A region asked for from outside (the chat's "Show on canvas"): framed once the board is
    // loaded and measured, never zoomed in past 100%, after the open-time fit so it wins. A jump,
    // not an animation, so reduced motion has nothing to honour here.
    val cameraTarget = board.host.cameraRequest?.target
    LaunchedEffect(cameraTarget, ui.initialLoadDone, ui.boardSize) { board.frameCameraRequest(cameraTarget) }
    CanvasKeyboardCameraEffect(board)
}

private fun CanvasBoard.fitOnOpen(layout: CanvasLayout?) {
    if (!shouldFitOnOpen(layout)) return
    kept.fittedOnOpen = true
    if (layout == CanvasLayout.COMPACT) {
        fitToContent(maxScale = 1f)
        // And in the select tool, where a drag moves around the board rather than drawing.
        controller.setMode(Mode.SELECT)
    }
}

private fun CanvasBoard.shouldFitOnOpen(layout: CanvasLayout?): Boolean {
    if (!ui.initialLoadDone || layout == null) return false
    return !kept.fittedOnOpen
}

private fun CanvasBoard.frameCameraRequest(target: CanvasCameraTarget?) {
    val request = host.cameraRequest ?: return
    request.frameOn(target, CameraBoard(session?.canvasId?.value, ui.boardSize, ui.initialLoadDone)) { fit -> jumpTo(fit) }
}

/** What the keyboard camera watches, read inside its effect so the keyboard's slide recomposes nothing. */
private class KeyboardWatch(
    val imeInsets: WindowInsets,
    val safeInsets: WindowInsets,
    val density: Density,
    val chromeBottomPx: State<Int>,
    val topReserve: State<Int>,
    val target: State<Rect?>,
)

/** How the camera answers the keyboard. */
private class KeyboardFollow(
    val camera: CanvasKeyboardCamera,
    val marginPx: Float,
    val reducedMotion: State<Boolean>,
)

/**
 * The keyboard covers the foot of a phone's board, and on the shared chat page the chat bar
 * rides up on it. While a note, a text or a shape's text is typed into, the camera (never the
 * element, and never the zoom) keeps it in the band left above the keyboard, the bar and the
 * board's own foot, following the keyboard frame by frame as it slides in, and gives the pan
 * back in step as it slides out; see CanvasKeyboardCamera. With reduced motion the camera waits
 * for the keyboard to settle and moves once. A desktop has no keyboard inset: nothing moves.
 */
@Composable
private fun CanvasKeyboardCameraEffect(board: CanvasBoard) {
    val density = LocalDensity.current
    val insets = board.view.insets
    val watch = KeyboardWatch(
        imeInsets = LocalCanvasImeInsets.current ?: WindowInsets.ime,
        safeInsets = WindowInsets.safeDrawing,
        density = density,
        chromeBottomPx = rememberUpdatedState(with(density) { insets.chromeBottom.roundToPx() }),
        topReserve = rememberUpdatedState(with(density) { insets.chrome.getTop(this) + KEYBOARD_TOP_RESERVE.roundToPx() }),
        target = rememberUpdatedState(board.typingTarget()),
    )
    val follow = KeyboardFollow(
        camera = remember(board.controller) { CanvasKeyboardCamera() },
        marginPx = with(density) { (CANVAS_CHROME_INSET + KEYBOARD_MARGIN).toPx() },
        reducedMotion = rememberUpdatedState(LocalReducedMotion.current),
    )
    LaunchedEffect(board.controller, watch.imeInsets, watch.safeInsets, density) {
        snapshotFlow { watch.frame() }.collectLatest { frame -> board.followKeyboard(frame, follow) }
    }
}

/** What is being typed into, in board units; null when nothing is, or the note is opened large. */
private fun CanvasBoard.typingTarget(): Rect? {
    if (ui.expandedNoteId != null) return null
    val activeId = ui.activeNoteId
    if (activeId != null) return documents.firstOrNull { it.id == activeId }?.frame?.toRect()
    val editingId = ui.editingTextId ?: return null
    return state.elements.firstOrNull { it.id == editingId }?.bounds()
}

private fun KeyboardWatch.frame(): CanvasKeyboardFrame {
    val ime = imeInsets.getBottom(density)
    return CanvasKeyboardFrame(
        ime = ime,
        // On the chat page the bar's inset already stands on the keyboard; elsewhere the
        // keyboard (or the navigation bar under it) is all there is.
        obstruction = maxOf(ime, safeInsets.getBottom(density), chromeBottomPx.value),
        target = target.value,
        topReserve = topReserve.value,
    )
}

private suspend fun CanvasBoard.followKeyboard(frame: CanvasKeyboardFrame, follow: KeyboardFollow) {
    if (follow.reducedMotion.value && frame.ime > 0) delay(KEYBOARD_SETTLE_MS)
    val foot = CanvasBoardFoot(heightPx = ui.footHeight[0].toFloat(), marginPx = follow.marginPx)
    val band = keyboardBand(ui.boardFrame, frame, foot)
    val delta = follow.camera.step(frame.ime, frame.target, controller.state.value.viewport, band) ?: return
    controller.panBy(delta)
    follow.camera.moved(controller.state.value.viewport)
}

/**
 * Ctrl/Cmd + wheel over the board zooms the board, not the window: the host that owns that
 * gesture for UI zoom is told where the board is so it leaves those events alone.
 */
@Composable
internal fun CanvasBoardWheelZoomEffect(board: CanvasBoard) {
    val regions = LocalWheelZoomRegions.current
    val ui = board.ui
    DisposableEffect(regions) {
        val unregister = regions?.register { ui.boardBounds }
        onDispose { unregister?.invoke() }
    }
}

/**
 * Marquee and move are DrawBox gestures; the notes follow the same intents so a marquee takes in
 * note cards and dragging the selection moves them too, committed on release. A phone's
 * multi-selection ends with the selection.
 */
@Composable
internal fun CanvasBoardIntentEffects(board: CanvasBoard) {
    val selectionEmpty = board.state.selectedIds.isEmpty()
    LaunchedEffect(selectionEmpty) { board.endMultiSelectingIfEmpty() }
    LaunchedEffect(board.controller, board.session) {
        board.controller.intents.collect { intent -> board.onBoardIntent(intent) }
    }
}

private fun CanvasBoard.endMultiSelectingIfEmpty() {
    if (state.selectedIds.isEmpty()) ui.multiSelecting = false
}

private fun CanvasBoard.onBoardIntent(intent: Intent) {
    when (intent) {
        is Intent.CommitMarquee -> selectMarqueeNotes(intent.rect)
        is Intent.MoveSelected -> moveNoteGroup(intent.delta)
        is Intent.EndTransform -> commitGroupMove()
        // The text tool asks for a caret in whatever was tapped: an existing piece of text, or the
        // one DrawBox is about to insert there. Double-tapping asks for a caret in whatever is
        // under the pointer, and DrawBox reports that rather than the board watching for its own
        // double tap: a second tap detector in the chain CONSUMED the first tap, so a single tap
        // never reached DrawBox at all and the text tool placed nothing.
        is Intent.RequestTextEditAt -> requestTextEdit(intent)
        is Intent.ClearSelection, is Intent.SelectAt -> dropNoteSelection()
        else -> Unit
    }
}

private fun CanvasBoard.selectMarqueeNotes(rect: Rect) {
    ui.selectedNoteIds = CanvasWorkspaceSupport.findMarqueeNoteIds(rect, liveDocuments)
    if (ui.selectedNoteIds.isNotEmpty()) ui.activeNoteId = null
}

private fun CanvasBoard.moveNoteGroup(delta: Offset) {
    if (ui.selectedNoteIds.isNotEmpty()) ui.groupOffset += delta
}

private fun CanvasBoard.dropNoteSelection() {
    if (ui.groupOffset == Offset.Zero) ui.selectedNoteIds = emptySet()
}

private fun CanvasBoard.requestTextEdit(intent: Intent.RequestTextEditAt) {
    scope.launch {
        CanvasWorkspaceSupport.handleRequestTextEdit(
            RequestTextEditParams(
                offset = intent.offset,
                tolerance = intent.tolerance,
                elements = controller.state.value.elements,
                onEditText = { ui.editingTextId = it },
                // However the text was asked for (a double click, the menu), the shape is what is
                // selected while it is typed.
                onEditShapeText = { shape ->
                    selectElement(shape)
                    ui.editingTextId = shape.id
                },
            ),
        )
    }
}
