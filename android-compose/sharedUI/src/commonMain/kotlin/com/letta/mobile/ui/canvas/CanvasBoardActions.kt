package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.data.canvas.CanvasSession
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.canHoldText
import io.ak1.drawbox.input.DroppedImage
import io.ak1.drawbox.input.pasteImageFromClipboard
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Runs a document change and records it as one undoable step.
 *
 * The step is a diff of the documents either side of [block] rather than the ops inside it: a
 * single board action can touch several documents through several calls, and what undo owes
 * the person is the state they had.
 */
internal suspend fun CanvasBoard.recordingDocuments(label: String, block: suspend () -> Unit) {
    CanvasWorkspaceSupport.recordDocumentChange(work.recorder, DocumentChangeRequest(label = label), block)
}

/**
 * Records document work that belongs to the drawing change just made.
 *
 * A label goes because its shape went, and the board notices a moment later - the reconciler
 * runs when the elements settle - so the two halves of one action arrive separately. Folding
 * this half into that step is what makes one press give back the shape AND the words it was
 * holding; recorded as a step of its own it would take two, with an empty box in between.
 * When there is no drawing step to fold into, it is recorded on its own rather than lost.
 */
internal suspend fun CanvasBoard.recordingWithLastDrawing(label: String, block: suspend () -> Unit) {
    CanvasWorkspaceSupport.recordDocumentChange(work.recorder, DocumentChangeRequest(label, attachToLastDrawing = true), block)
}

/** Deletes the note [id], as one undoable step. */
internal fun CanvasBoard.deleteNote(id: String) {
    scope.launch { recordingDocuments("deleting a note") { runCatching { session?.removeDocument(id) } } }
}

/** Deletes the notes [ids], as one undoable step. */
internal fun CanvasBoard.deleteNotes(ids: Set<String>) {
    scope.launch {
        recordingDocuments("deleting notes") { ids.forEach { id -> runCatching { session?.removeDocument(id) } } }
    }
}

/** A group drag ends: every selected note lands where it was dragged, as one batch op. */
internal fun CanvasBoard.commitGroupMove() {
    val offset = ui.groupOffset
    ui.groupOffset = Offset.Zero
    val s = session ?: return
    if (offset == Offset.Zero || ui.selectedNoteIds.isEmpty()) return
    val frames = CanvasWorkspaceSupport.buildMoveFrames(documents, ui.selectedNoteIds, offset, ui.fittedNoteHeights)
    scope.launch {
        recordingDocuments("moving notes") { runCatching { s.moveDocuments(frames) } }
    }
}

/**
 * The eraser is a drag, not a click, and DrawBoxController does not surface EraseAt on its
 * intent flow, so notes are taken by watching the eraser's own pointer instead.
 */
internal fun CanvasBoard.eraseNotesAt(area: EraserArea) {
    if (session == null) return
    val ids = CanvasWorkspaceSupport.findErasedNoteIds(area, liveDocuments)
    if (ids.isEmpty()) return
    if (ui.activeNoteId in ids) ui.activeNoteId = null
    ui.selectedNoteIds = ui.selectedNoteIds - ids
    deleteNotes(ids)
}

/**
 * Picks [element] alone, in the select tool. By id, not by a point on it: a point can land on
 * something covering it, such as the connector quick-create ends on the new shape.
 */
internal fun CanvasBoard.selectElement(element: Element) {
    if (controller.state.value.selectedIds == setOf(element.id)) return
    controller.setMode(Mode.SELECT)
    controller.selectIds(setOf(element.id))
}

/**
 * Puts the caret in [element]: a text element's own editor, or the text of a shape that holds
 * it. A shape stays selected while its text is typed: the menu that floats up is the shape's,
 * because the shape is the thing being worked on.
 */
internal fun CanvasBoard.openTextIn(element: Element) {
    when {
        element is Element.Text -> ui.editingTextId = element.id
        element is Element.Shape && element.canHoldText -> {
            selectElement(element)
            ui.editingTextId = element.id
        }
    }
}

/** Delete/Backspace: removes the drawn selection, the selected notes or the active note. */
internal fun CanvasBoard.deleteFocused(): Boolean {
    return CanvasWorkspaceSupport.deleteFocused(
        DeleteFocusedParams(
            selectedNoteIds = ui.selectedNoteIds,
            hasSelection = view.hasSelection,
            activeNoteId = ui.activeNoteId,
            expandedNoteId = ui.expandedNoteId,
            hasSession = session != null,
            onDeleteSelection = { controller.deleteSelected() },
            onDeleteNotes = { ids ->
                ui.selectedNoteIds = emptySet()
                deleteNotes(ids)
            },
            onDeleteActiveNote = { id ->
                ui.activeNoteId = null
                deleteNote(id)
            },
        ),
    )
}

/** Duplicates the drawn selection, or else the active note. */
internal fun CanvasBoard.duplicateFocused(): Boolean {
    if (view.hasSelection) {
        return CanvasWorkspaceSupport.duplicateDrawnSelection(
            controller = controller,
            elements = state.elements,
            selectedIds = state.selectedIds,
            onStatus = { ui.statusMessage = it },
        )
    }
    return CanvasWorkspaceSupport.duplicateActiveNote(
        activeNoteId = ui.activeNoteId,
        documents = documents,
        session = session,
        onCreated = { id, note, frame, s -> storeDuplicate(NoteDuplicate(id, note, frame), s) },
    )
}

/** A note copied to a new id and frame. */
private class NoteDuplicate(val id: String, val note: CanvasSceneDocument, val frame: CanvasDocumentFrame)

private fun CanvasBoard.storeDuplicate(copy: NoteDuplicate, s: CanvasSession) {
    val note = copy.note
    scope.launch {
        recordingDocuments("duplicating a note") {
            runCatching { s.setDocument(copy.id, note.json, frame = copy.frame, color = note.color, style = note.style) }
                .onSuccess {
                    ui.activeNoteId = copy.id
                    ui.statusMessage = "Duplicated note"
                }
        }
    }
}

/**
 * Images: picked (several at once), pasted or dropped, each made ready off the main thread
 * (see prepareCanvasImage) and placed where it was asked for.
 */
internal fun CanvasBoard.placeImages(sources: List<ByteArray>, at: Offset) {
    if (sources.isEmpty()) return
    scope.launch {
        val images = withContext(Dispatchers.Default) { sources.mapNotNull { prepareCanvasImage(it) } }
        if (images.isEmpty()) {
            ui.statusMessage = "Could not read that image"
            return@launch
        }
        CanvasImages.insert(controller, images, at)
        ui.statusMessage = if (images.size == 1) "Added an image" else "Added ${images.size} images"
    }
}

/** What the image picker handed back, placed where the add menu asked for it. */
internal fun CanvasBoard.placePickedFiles(files: List<PlatformFile>?) {
    if (files.isNullOrEmpty()) return
    scope.launch {
        val bytes = withContext(Dispatchers.Default) {
            files.mapNotNull { file -> runCatching { file.readBytes() }.getOrNull() }
        }
        placeImages(bytes, ui.imagesAt)
    }
}

/** Image files dragged in from the desktop land where they are dropped. */
internal fun CanvasBoard.placeDroppedImages(drops: List<DroppedImage>) {
    val first = drops.firstOrNull() ?: return
    placeImages(drops.map { it.bytes }, controller.state.value.viewport.screenToWorld(first.dropPositionScreen))
}

/** Puts an image from the clipboard on the board at [at]. */
internal fun CanvasBoard.pasteImage(at: Offset) {
    pasteImageFromClipboard { bytes, _ -> placeImages(listOf(bytes), at) }
}

/**
 * Keys on the board: Delete/Backspace removes the drawn selection or the active note, Esc lets
 * both go, Ctrl/Cmd+Z and Ctrl/Cmd+Shift+Z (or +Y) undo and redo, Ctrl/Cmd+D duplicates.
 * Handled where they bubble to, so a note editor keeps every key it consumes.
 */
internal fun CanvasBoard.onBoardKey(event: KeyEvent): Boolean {
    return when (canvasKeyAction(event)) {
        CanvasKeyAction.DELETE -> deleteFocused()
        CanvasKeyAction.ESCAPE -> letGo()
        CanvasKeyAction.UNDO -> {
            undoBoard()
            true
        }
        CanvasKeyAction.REDO -> {
            redoBoard()
            true
        }
        CanvasKeyAction.DUPLICATE -> duplicateFocused()
        CanvasKeyAction.PASTE -> {
            pasteImage(controller.state.value.viewport.screenToWorld(view.boardCenter))
            true
        }
        null -> false
    }
}

/** Esc: the selection, the note, the caret and the opened note all let go. */
private fun CanvasBoard.letGo(): Boolean {
    controller.clearSelection()
    ui.activeNoteId = null
    ui.editingTextId = null
    ui.expandedNoteId = null
    ui.selectedNoteIds = emptySet()
    return true
}

/** A new note's id. */
@OptIn(ExperimentalTime::class)
internal fun newNoteId(): String {
    return "note-${Clock.System.now().toEpochMilliseconds()}"
}

/** Adds an empty note at [world], clear of the notes already there, and puts the caret in it. */
internal fun CanvasBoard.addNoteAt(world: Offset) {
    val s = session ?: return
    val frame = clearOfExisting(newNoteFrame(world), liveDocuments.mapNotNull { it.frame })
    val id = newNoteId()
    scope.launch {
        recordingDocuments("adding a note") {
            runCatching { s.setDocument(id, "", frame = frame, color = NoteColors.first().hex) }
                .onSuccess {
                    ui.activeNoteId = id
                    ui.focusRequest.documentId = id
                    ui.statusMessage = "Added note"
                }
                .onFailure { ui.statusMessage = "Error: could not add note (${it.message})" }
        }
    }
}

/** What the board can add at a point, for its menus and tool bars; [onAddImages] opens the picker. */
internal fun CanvasBoard.insertActions(onAddImages: (Offset) -> Unit): BoardInsertActions {
    return BoardInsertActions(
        onAddNote = if (session != null) ({ world -> addNoteAt(world) }) else null,
        onAddText = { world -> controller.insertEmptyText(controller.state.value, world) },
        onAddImages = onAddImages,
        onAddShape = { mode, world -> addShapeAt(mode, world) },
    )
}

/** A shape added from a menu: a shape that takes a label gets the caret, any other is selected. */
private fun CanvasBoard.addShapeAt(mode: Mode, world: Offset) {
    val id = CanvasInsert.addShape(controller, mode, world)
    val added = controller.state.value.elements.firstOrNull { it.id == id }
    when {
        added == null -> Unit
        CanvasShapeLabels.canLabel(added) && session != null -> openTextIn(added)
        else -> selectElement(added)
    }
}

/** Undoes the board's last step: an unsaved stroke first, else the history's newest step. */
internal fun CanvasBoard.undoBoard() {
    val drawingUnsaved = ui.lastSavedElements?.let { !CanvasWorkspaceSupport.sameDrawing(it, state.elements) } == true
    CanvasWorkspaceSupport.undoBoard(work.historyActions, drawingUnsaved, flows.canUndo.value)
}

/** Redoes the board's last undone step. */
internal fun CanvasBoard.redoBoard() {
    CanvasWorkspaceSupport.redoBoard(work.historyActions, flows.canRedo.value)
}

/**
 * Fits everything on the board (elements and notes) with padding; an empty board just goes back
 * to 100% at the origin. [maxScale] lets the open-time fit shrink a board without enlarging it.
 */
internal fun CanvasBoard.fitToContent(maxScale: Float = CanvasViewportFit.MAX_SCALE): Boolean {
    val bounds = CanvasViewportFit.contentBounds(state.elements, documents)
    val fit = CanvasViewportFit.fitOrNull(bounds, ui.boardSize, maxScale)
    if (fit == null) {
        controller.resetCamera()
        return false
    }
    jumpTo(fit)
    return true
}

/**
 * Puts the camera on [fit] in one jump. From the reset camera (scale 1, no offset): zooming about
 * the origin leaves the offset at zero, then one pan places the content.
 */
internal fun CanvasBoard.jumpTo(fit: CanvasFit) {
    controller.resetCamera()
    controller.zoomBy(fit.scale, Offset.Zero)
    controller.panBy(fit.offset)
}

/**
 * An export stands on its own, so every image must go in whole: those still showing a preview
 * get their bytes from the store or the host first, and an export that cannot have them all
 * says so instead of writing previews in their place.
 */
internal suspend fun CanvasBoard.exportStandalone(handler: (String) -> Unit) {
    val completion = CanvasImageAssets.completeForExport(controller.state.value.elements, host.assets) { ref ->
        session?.fetchAsset(ref)
    }
    completion.completed.forEach { controller.onIntent(Intent.UpdateElement(it)) }
    if (completion.missing > 0) {
        ui.statusMessage = "Export needs every image: ${completion.missing} not loaded yet"
        return
    }
    handler(controller.exportStandaloneJson())
}
