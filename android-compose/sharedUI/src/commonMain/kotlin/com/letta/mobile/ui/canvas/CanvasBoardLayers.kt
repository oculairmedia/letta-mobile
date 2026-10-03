package com.letta.mobile.ui.canvas

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.data.canvas.CanvasBackgroundPattern
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasSnapAnchor
import com.letta.mobile.ui.theme.LettaDimens
import io.ak1.drawbox.DrawBox
import io.ak1.drawbox.domain.model.Element
import com.letta.mobile.ui.canvas.plugin.CanvasPluginLayer
import com.letta.mobile.ui.canvas.plugin.PluginBoardSelection
import com.letta.mobile.ui.canvas.plugin.rememberPluginBoard
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.canHoldText
import io.ak1.drawbox.input.imageDragAndDropTarget
import io.ak1.drawbox.text.InlineShapeTextEditor
import io.ak1.drawbox.text.InlineTextEditor
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

/** Below the header bar (and its inset), so the storage-fault banner never covers the title or actions. */
private val STORAGE_FAULT_TOP = CANVAS_CHROME_INSET + CanvasHeaderBarHeight + LettaDimens.Space.sm

/** What the board can add at a point, with the image picker that adds pictures. */
@Composable
internal fun boardInsertActions(board: CanvasBoard): BoardInsertActions {
    val imagePicker = rememberFilePickerLauncher(
        type = FileKitType.Image,
        mode = FileKitMode.Multiple(maxItems = CanvasImages.MAX_PICK),
    ) { files -> board.placePickedFiles(files) }
    return board.insertActions { world ->
        board.ui.imagesAt = world
        imagePicker.launch()
    }
}

/** The board's own input: keys, focus, its size and place, dropped images, and a finger's press. */
@Composable
internal fun Modifier.canvasBoardInput(board: CanvasBoard): Modifier {
    val ui = board.ui
    return this
        .focusRequester(ui.boardFocus)
        .focusable()
        .onKeyEvent { board.onBoardKey(it) }
        .semantics { contentDescription = "Canvas workspace" }
        .onSizeChanged { ui.boardSize = it }
        // Image files dragged in from the desktop land where they are dropped.
        .imageDragAndDropTarget { drops -> board.placeDroppedImages(drops) }
        .onGloballyPositioned { board.trackBoardPosition(it) }
        .pointerInput(board.controller) {
            awaitPointerEventScope {
                while (true) {
                    board.watchInitialPress(awaitPointerEvent(PointerEventPass.Initial))
                }
            }
        }
}

private fun CanvasBoard.trackBoardPosition(coordinates: LayoutCoordinates) {
    val bounds = coordinates.boundsInRoot()
    ui.boardBounds = bounds
    ui.boardFrame.top = bounds.top
    ui.boardFrame.width = coordinates.size.width.toFloat()
    ui.boardFrame.rootHeight = coordinates.findRootCoordinates().size.height.toFloat()
    ui.chromeRegions.sceneRootInWindow = coordinates.findRootCoordinates().positionInWindow()
}

/** The wheel zooms; a real touch press picks with a fingertip's reach, before DrawBox sees it. */
private fun CanvasBoard.watchInitialPress(event: PointerEvent) {
    CanvasWorkspaceSupport.handleWheelZoom(event, controller)
    if (event.type == PointerEventType.Press && event.changes.any { it.type == PointerType.Touch }) {
        ui.fingerRecency.touched(kotlin.time.Clock.System.now().toEpochMilliseconds())
    }
}

/** Everything drawn on the board, under its chrome. */
@Composable
internal fun BoxScope.CanvasBoardLayers(board: CanvasBoard) {
    // DrawBox Canvas layer. A press that reaches the drawing (not a note card, not the chrome
    // above it) is a click on the board, which lets the active note go.
    CanvasDrawingLayer(board)
    CanvasTextEditingLayer(board)
    // The stroke under the nib, until DrawBox owns it.
    CanvasPenPreview(
        samples = board.ui.penPreview,
        state = board.state,
        modifier = Modifier.fillMaxSize(),
    )
    CanvasBoardNotes(board)
    CanvasBoardPlugins(board)
    // Presence layer (Card I3.5)
    PresenceLayer(
        presences = board.flows.sharing.presences.value,
        currentPeerId = board.host.currentPeerId,
    )
    CanvasBoardDocumentEffects(board)
    CanvasBoardSnapIndicator(board)
    // A save that did not reach disk stays on the board until restart.
    CanvasStorageFaultOverlay(
        session = board.session,
        modifier = Modifier.align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = STORAGE_FAULT_TOP, start = CANVAS_CHROME_INSET, end = CANVAS_CHROME_INSET),
    )
}

@Composable
private fun CanvasDrawingLayer(board: CanvasBoard) {
    val state = board.state
    val ui = board.ui
    val pattern = board.docs.backgroundPattern
    DrawBox(
        state = state,
        onIntent = board.controller::onIntent,
        // Shapes and notes share one selection look; see CanvasSelectionChrome.
        selectionStyle = canvasSelectionStyle(),
        additiveTaps = board.view.compact && ui.multiSelecting,
        pickTolerance = { ui.fingerRecency.pickTolerance(kotlin.time.Clock.System.now().toEpochMilliseconds()) },
        // Gestures read the controller's state as it is now, not as of the last frame, so
        // anything the board dispatches during a press is already seen by that press.
        liveState = { board.controller.state.value },
        // The shape whose text is being typed keeps its outline; its text is the editor's.
        hiddenTextElementIds = setOfNotNull(editingShapeText(state.elements, ui.editingTextId)?.id),
        // A grid is DrawBox's: crisp one-pixel lines at every zoom, in the colour and spacing the
        // background menu sets. Dots and lines are the board's tile instead, and "none" turns
        // both off.
        showGrid = pattern.kind == CanvasBackgroundPattern.GRID,
        gridColor = pattern.tint(),
        gridSpacing = pattern.spacing,
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .semantics { contentDescription = "Canvas board" }
            .canvasDrawingGestures(board),
    )
}

@Composable
private fun Modifier.canvasDrawingGestures(board: CanvasBoard): Modifier {
    val controller = board.controller
    return this
        .boardContextGesture(
            onContext = { board.openBoardMenu(it) },
            onLongPress = { board.longPressAt(it) },
            onBox = { board.dragSelectionBox(it) },
        )
        // Two finger taps on open board zoom in there; on an element DrawBox opens its text.
        .touchDoubleTapZoom(
            canZoomAt = { screen -> board.isOpenBoardAt(screen) },
            onZoom = { focal -> board.easeZoom(DOUBLE_TAP_ZOOM, focal) },
        )
        // On a phone one finger on open board in the select tool pans: dragging is how you move
        // around a board on a phone. (Two-finger pinch is DrawBox's.)
        .touchNavigation(
            enabled = board.view.compact,
            canPanFrom = { screen -> board.isOpenBoardAt(screen) },
            onPan = { delta -> controller.panBy(delta) },
        )
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    board.onBoardPress(awaitPointerEvent(PointerEventPass.Initial))
                }
            }
        }
        // After DrawBox has handled the event (Final pass): track Alt and the pointer while a
        // connector is drawn, and on release snap the connector just finished.
        .pointerInput(board.session) {
            awaitPointerEventScope {
                // What was on the board when the press began, so the release can tell a shape
                // that was just drawn from one that was already there.
                var idsAtPress: Set<String> = emptySet()
                while (true) {
                    idsAtPress = board.afterDrawBox(awaitPointerEvent(PointerEventPass.Final), idsAtPress)
                }
            }
        }
}

private fun CanvasBoard.isOpenBoardAt(screen: Offset): Boolean {
    return CanvasWorkspaceSupport.isOpenBoard(controller.state.value, screen, TEXT_HIT_TOLERANCE)
}

/** A press on the drawing lets the active note (unless one is opened large) and the caret go. */
private fun CanvasBoard.onBoardPress(event: PointerEvent) {
    if (event.type != PointerEventType.Press) return
    if (ui.expandedNoteId == null) ui.activeNoteId = null
    ui.editingTextId = null
    runCatching { ui.boardFocus.requestFocus() }
}

private fun CanvasBoard.afterDrawBox(event: PointerEvent, idsAtPress: Set<String>): Set<String> {
    val ids = if (event.type == PointerEventType.Press) {
        controller.state.value.elements.mapTo(HashSet()) { it.id }
    } else {
        idsAtPress
    }
    if (event.type == PointerEventType.Release) {
        CanvasWorkspaceSupport.shapeJustDrawn(controller.state.value, ids)?.let { openTextIn(it) }
    }
    val result = CanvasWorkspaceSupport.handleFinalPointerPass(
        FinalPointerPassParams(
            event = event,
            current = controller.state.value,
            onEraseNotes = { eraseNotesAt(it) },
            onSnapLatestConnector = {
                CanvasWorkspaceSupport.snapLatestConnector(controller, session, liveDocuments, scope)
            },
        ),
    )
    ui.altHeld = result.altHeld
    ui.drawingConnectorAt = result.drawingConnectorAt
    return ids
}

/**
 * The caret, where DrawBox asked for one. Its own editor, placed by its own viewport: the text on
 * the board and the text being typed are then the same thing, measured and wrapped by the same
 * code, which is what a text element is for.
 */
@Composable
private fun CanvasTextEditingLayer(board: CanvasBoard) {
    val state = board.state
    val ui = board.ui
    // The text tool places an element and the board puts the caret in it. Read from the
    // ELEMENTS rather than from the insert intent: the intent flow is a buffered broadcast that
    // still drops for a subscriber that falls far enough behind, and the elements are the state
    // itself, so a caret read from them cannot go missing.
    LaunchedEffect(state.elements) { board.caretInNewText() }
    val editingText = CanvasTextElements.byId(state.elements, ui.editingTextId)
    if (editingText != null) {
        InlineTextEditor(
            editingText,
            state.viewport,
            editingText.text,
        ) { typed -> board.controller.updateText(editingText.id, typed) }
    }
    // A shape's text is typed in place, over the shape, the same way.
    val editingShape = editingShapeText(state.elements, ui.editingTextId)
    if (editingShape != null) {
        InlineShapeTextEditor(
            editingShape,
            state.viewport,
            editingShape.text,
            onDraftChange = { typed -> board.controller.updateText(editingShape.id, typed) },
        )
    }
}

private fun CanvasBoard.caretInNewText() {
    val (ids, emptyId) = CanvasWorkspaceSupport.detectNewEmptyTextElement(state.elements, kept.knownTextIds)
    kept.knownTextIds = ids
    if (emptyId != null) ui.editingTextId = emptyId
}

/** The shape whose text is being typed, when [editingId] names one that holds text. */
private fun editingShapeText(elements: List<Element>, editingId: String?): Element.Shape? {
    val shape = editingId?.let { id -> elements.firstOrNull { it.id == id } as? Element.Shape }
    return shape?.takeIf { it.canHoldText }
}

/** Block documents live on the board as note cards, in world coordinates. */
@Composable
private fun CanvasBoardNotes(board: CanvasBoard) {
    val session = board.session ?: return
    val documents = board.documents
    if (documents.isEmpty()) return
    val state = board.state
    val ui = board.ui
    // A frameless note's slot comes from the shared placement engine, laid out against the same
    // bounds zoom-to-fit uses (letta-mobile-bglj6.11). Only worked out when there is a frameless
    // note at all.
    val framelessFrames = remember(documents, state.elements) {
        framelessFramesOf(documents) { CanvasViewportFit.contentBounds(board.state.elements, documents) }
    }
    CanvasNotesLayer(
        onLiveFrame = { id, frame -> board.trackLiveFrame(id, frame) },
        framelessFrames = framelessFrames,
        onFittedHeight = ui.fittedNoteHeights::putOrRemove,
        session = session,
        documents = documents,
        viewport = state.viewport,
        activeNoteId = ui.activeNoteId,
        expandedNoteId = ui.expandedNoteId,
        onActivate = { ui.activeNoteId = it },
        onExpand = { ui.expandedNoteId = it },
        onToolbar = { ui.noteToolbar = it },
        selectedIds = ui.selectedNoteIds,
        groupOffset = ui.groupOffset,
        // The eraser takes a note the way it takes a stroke: touch it and it is gone.
        eraseMode = state.mode == Mode.ERASER,
        onErase = { id -> board.eraseNote(id) },
        onPress = { id, shift -> board.pressNote(id, shift) },
        onGroupDrag = { delta -> ui.groupOffset += delta },
        onGroupDragEnd = { board.commitGroupMove() },
        modifier = Modifier.fillMaxSize().clipToBounds(),
    )
}

/**
 * Plugin elements (letta-mobile-s416w.4), beside the notes and in the same world units. A selected
 * one is the board's active item, so a press on the board or on a note lets it go.
 */
@Composable
private fun CanvasBoardPlugins(board: CanvasBoard) {
    val session = board.session ?: return
    CanvasPluginLayer(
        board = rememberPluginBoard(session, board.flows.sessionDoc.value, board.host.assets),
        viewport = board.state.viewport,
        modifier = Modifier.fillMaxSize().clipToBounds(),
        selection = PluginBoardSelection(
            selectedId = board.ui.activeNoteId,
            eraseMode = board.state.mode == Mode.ERASER,
            onSelect = { id -> board.selectPluginElement(id) },
        ),
    )
}

/** Picking a plugin element replaces the board's selection, as picking a note does. */
private fun CanvasBoard.selectPluginElement(id: String) {
    controller.clearSelection()
    ui.selectedNoteIds = emptySet()
    ui.activeNoteId = id
}

private fun CanvasBoard.trackLiveFrame(id: String, frame: CanvasDocumentFrame?) {
    if (frame == null) ui.liveNoteFrames.remove(id) else ui.liveNoteFrames[id] = frame
}

private fun CanvasBoard.eraseNote(id: String) {
    if (ui.activeNoteId == id) ui.activeNoteId = null
    ui.selectedNoteIds = ui.selectedNoteIds - id
    deleteNote(id)
}

private fun CanvasBoard.pressNote(id: String, shift: Boolean) {
    if (shift) {
        toggleNoteSelected(id)
        return
    }
    if (id in ui.selectedNoteIds) return
    // Picking a note replaces the board's selection, exactly as picking a shape does. Without
    // this the drawn selection stayed put and the note joined it, so a plain click read as a
    // shift-click. A shape's label picks its shape: the text is the shape's.
    val owner = CanvasShapeLabels.shapeIdOf(id)?.let { shapeId -> state.elements.firstOrNull { it.id == shapeId } }
    if (owner != null) selectElement(owner) else controller.clearSelection()
    ui.selectedNoteIds = emptySet()
    ui.activeNoteId = id
}

private fun CanvasBoard.toggleNoteSelected(id: String) {
    ui.selectedNoteIds = if (id in ui.selectedNoteIds) ui.selectedNoteIds - id else ui.selectedNoteIds + id
    ui.activeNoteId = null
}

/**
 * The board keeping its notes and drawing in step: a label lets go when its shape does, the pen
 * draws, labels follow their shapes, and connectors follow their notes.
 */
@Composable
private fun CanvasBoardDocumentEffects(board: CanvasBoard) {
    val state = board.state
    val ui = board.ui
    // Picking a drawing element hands the selection to DrawBox; the note lets go. A shape's label
    // is the exception: its shape being selected is how its text is edited. Keyed on the
    // selection itself, not just whether there is one: duplicating a shape selects the copy
    // without a board press, and the original's label must let go then too.
    LaunchedEffect(state.selectedIds) { board.letLabelGo() }
    CanvasPenEffect(board)
    // A label lives inside its shape: it is re-framed whenever the shape moves or is resized, and
    // removed with it. Keyed on the element list so a shape dragged by DrawBox carries its text
    // along in the same frame.
    LaunchedEffect(state.elements, board.liveDocuments, board.session, ui.importedRevision, ui.initialLoadDone) {
        board.reconcileLabels()
    }
    // A bound connector end follows its note: whenever a document's frame changes (a local drag,
    // a peer, the agent), every arrow bound to it is re-pointed through DrawBox, so the move
    // reaches the scene through the normal export rather than a re-import that would throw the
    // camera back.
    LaunchedEffect(board.documents, board.docs.arrowBindings) { board.followNoteConnectors() }
}

private fun CanvasBoard.letLabelGo() {
    val labelOf = ui.activeNoteId?.let(CanvasShapeLabels::shapeIdOf)
    if (view.hasSelection && labelOf !in state.selectedIds) ui.activeNoteId = null
}

/**
 * The pen draws its own strokes.
 *
 * Everything else the pen does - pressing a button, picking a note, dragging a handle - arrives
 * as ordinary input and needs nothing here. Drawing is the exception: pressure is per sample and
 * no mouse event can carry it, so those events are taken here and built into one Element.Path on
 * lift. Taking them also stops the platform delivering the same stroke a second time without
 * pressure. Flipping the stylus over erases: the eraser nib is a tool in its own right, so it is
 * read from the event rather than asked of the user.
 */
@Composable
private fun CanvasPenEffect(board: CanvasBoard) {
    // Layout coordinates are in pixels; the pen reports in the window's logical units. On a
    // scaled display those differ by the density, and subtracting a pixel-space board offset
    // from a logical-space point put every event outside the board.
    val penDensity = LocalDensity.current.density
    // Which window's pen this canvas answers, so two open boards cannot take each other's events
    // or clear each other's registration.
    val penTarget = LocalCanvasPenTarget.current
    // The registry belongs to the host that reads the tablet, not to the process.
    val penRegistry = LocalCanvasPenRegistry.current
    val ui = board.ui
    val penConsumer = remember(board.session, board.state.mode, penDensity, penTarget, penRegistry, ui.boardBounds) {
        CanvasWorkspaceSupport.createPenConsumer(
            PenConsumerParams(
                controller = board.controller,
                session = board.session,
                boardBounds = ui.boardBounds,
                chromeRegions = ui.chromeRegions,
                penDensity = penDensity,
                penPreview = ui.penPreview,
                onEraseArea = { board.eraseNotesAt(it) },
                onDoubleTapText = { board.openTextIn(it) },
            ),
        )
    }
    DisposableEffect(penConsumer, penTarget, penRegistry) {
        val disposePen = penRegistry.register(penTarget, penConsumer)
        onDispose { disposePen() }
    }
}

private suspend fun CanvasBoard.reconcileLabels() {
    CanvasWorkspaceSupport.reconcileShapeLabels(
        ReconcileShapeLabelsParams(
            elements = state.elements,
            liveDocuments = liveDocuments,
            session = session,
            sessionDoc = flows.sessionDoc.value,
            importedRevision = ui.importedRevision,
            initialLoadDone = ui.initialLoadDone,
            onClearActiveNoteIf = { id -> if (ui.activeNoteId == id) ui.activeNoteId = null },
            recordDeletion = { block -> recordingWithLastDrawing("deleting a labelled shape") { block() } },
        ),
    )
}

private fun CanvasBoard.followNoteConnectors() {
    ui.lastFrames = CanvasWorkspaceSupport.followConnectors(
        FollowConnectorsParams(
            controller = controller,
            elements = state.elements,
            arrowBindings = docs.arrowBindings,
            documents = documents,
            lastFrames = ui.lastFrames,
        ),
    )
}

/** While a line or arrow is drawn, the anchor it would snap to shows as a ring. */
@Composable
private fun CanvasBoardSnapIndicator(board: CanvasBoard) {
    val anchor = board.snapAnchor() ?: return
    CanvasSnapIndicator(anchor = anchor, viewport = board.state.viewport)
}

private fun CanvasBoard.snapAnchor(): CanvasSnapAnchor? {
    val at = ui.drawingConnectorAt ?: return null
    if (!isDrawingConnector() || ui.altHeld) return null
    val drawing = CanvasSnapping.latestConnector(state.elements)
    return CanvasSnapping.nearest(at, CanvasSnapping.anchors(state.elements, liveDocuments, drawing?.id), state.viewport.scale)
}

private fun CanvasBoard.isDrawingConnector(): Boolean {
    return state.mode == Mode.LINE || state.mode == Mode.ARROW
}
