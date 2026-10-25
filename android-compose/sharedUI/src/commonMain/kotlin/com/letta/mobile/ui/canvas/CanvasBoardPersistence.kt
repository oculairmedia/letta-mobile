package com.letta.mobile.ui.canvas

import com.letta.mobile.data.canvas.CanvasHistory
import com.letta.mobile.util.Telemetry
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.PayLoad
import io.ak1.drawbox.domain.model.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** True when two controller states carry the same immutable drawing, regardless of tools/camera. */
internal fun State.matchesDrawing(other: State): Boolean = elements === other.elements && bgColor == other.bgColor

/** Records the drawing actually imported, including DrawBox's defaults and normalization. */
internal suspend fun CanvasBoard.acceptDrawingBaseline() {
    val drawing = controller.state.value
    ui.drawingBaseline = withContext(Dispatchers.Default) { drawing.serializeDrawing() }
    ui.importedDrawing = drawing.elements to drawing.bgColor
    ui.lastSavedElements = drawing.elements
}

/** Completes persistence before a viewer may dispose the board; failure keeps the draft mounted. */
internal suspend fun CanvasBoard.flushDrawing(): Boolean = ui.drawingSaveMutex.withLock { flushDrawingLocked() }

/** Caller holds [CanvasBoardUi.drawingSaveMutex], also used by remote imports. */
internal suspend fun CanvasBoard.flushDrawingLocked(): Boolean {
    if (!ui.initialLoadDone || session == null) return true
    while (true) {
        val drawing = controller.state.value
        if (ui.importedDrawing?.let { (elements, bg) -> elements === drawing.elements && bg == drawing.bgColor } == true) return true
        val baseline = ui.drawingBaseline ?: return false
        if (!persistDrawingOrRetainDraft(drawing, baseline)) return false
    }
}

private suspend fun CanvasBoard.persistDrawingOrRetainDraft(drawing: State, baseline: String): Boolean = try {
    // Finish an acknowledged write even if a newer stroke cancels the debounce effect.
    withContext(NonCancellable) { persistDrawing(drawing, baseline) }
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (expectedFailure: Exception) {
    ui.statusMessage = "Error: Could not save drawing: ${expectedFailure.message ?: "Unknown"}"
    Telemetry.error("CanvasWorkspace", "drawing.save.failed", expectedFailure, "canvasId" to requireNotNull(session).canvasId.value)
    false
}

private suspend fun CanvasBoard.persistDrawing(drawing: State, baseline: String) {
    val json = withContext(Dispatchers.Default) {
        drawing.serializeDrawing().also { json ->
            val activeSession = requireNotNull(session)
            activeSession.applyLocalScene(json, baseJson = baseline)
        }
    }
    // Acknowledge only this snapshot, never the merged session scene. Remote elements that are
    // still absent from the controller must be imported, rather than marked as already shown.
    ui.drawingBaseline = json
    ui.lastExportedJson = json
    ui.lastDrawing = withContext(Dispatchers.Default) { CanvasWorkspaceSupport.drawingOf(json) }
    ui.importedDrawing = drawing.elements to drawing.bgColor
    recordDrawingStep(drawing.elements)
}

private fun State.serializeDrawing(): String = DrawingSerializer.serialize(PayLoad(bgColor = bgColor, elements = elements))

/** A saved drawing change is one undo step, unless undo or redo made it. */
private fun CanvasBoard.recordDrawingStep(elementsNow: List<Element>) {
    val shouldRecord = CanvasWorkspaceSupport.shouldRecordDrawingStep(ui.lastSavedElements, elementsNow, ui.applyingHistory)
    ui.lastSavedElements = elementsNow
    if (ui.applyingHistory) {
        ui.applyingHistory = false
    } else if (shouldRecord) {
        kept.history.record(CanvasHistory.Step.Drawing())
    }
}
