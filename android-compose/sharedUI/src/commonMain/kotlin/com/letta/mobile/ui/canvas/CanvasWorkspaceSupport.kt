package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.letta.mobile.data.canvas.CanvasArrowBinding
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasDocumentUndo
import com.letta.mobile.data.canvas.CanvasHistory
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.data.canvas.CanvasSession
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.model.translate
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock

internal enum class HistoryDirection(val verb: String, val past: String) {
    Undo("undo", "Undid"),
    Redo("redo", "Redid");

    val isRedo: Boolean get() = this == Redo
}

internal data class HistoryMessageContext(
    val label: String,
    val direction: HistoryDirection,
    val success: Boolean,
)

internal data class DocumentChangeRequest(
    val label: String,
    val attachToLastDrawing: Boolean = false,
)

internal data class EraserArea(
    val world: Offset,
    val radius: Float,
)

internal data class HistoryActionContext(
    val controller: DrawBoxController,
    val session: CanvasSession?,
    val history: CanvasHistory,
    val scope: CoroutineScope,
    val onApplyingHistory: (Boolean) -> Unit,
    val onStatusMessage: (String) -> Unit,
)

internal data class DocumentRecorderContext(
    val session: CanvasSession?,
    val history: CanvasHistory,
    val isApplyingHistory: () -> Boolean,
)

internal data class PenConsumerParams(
    val controller: DrawBoxController,
    val session: CanvasSession?,
    val boardBounds: Rect?,
    val chromeRegions: CanvasChromeRegions,
    val penDensity: Float,
    val penPreview: MutableList<Element.PathSample>,
    val onEraseArea: (EraserArea) -> Unit,
)

internal data class DrawPhaseParams(
    val event: CanvasPenEvent,
    val world: Offset,
    val controller: DrawBoxController,
    val penPreview: MutableList<Element.PathSample>,
)

internal data class FollowConnectorsParams(
    val controller: DrawBoxController,
    val elements: List<Element>,
    val arrowBindings: Map<String, CanvasArrowBinding>,
    val documents: List<CanvasSceneDocument>,
    val lastFrames: Map<String, CanvasDocumentFrame>,
)

internal object CanvasWorkspaceSupport {
    internal const val DUPLICATE_OFFSET = 20f

    private fun hasBoundEndpoint(binding: CanvasArrowBinding): Boolean =
        binding.start != null || binding.end != null

    /**
     * Snaps the connector a release just finished to the nearest anchors within the snap radius:
     * its points move onto them, ends on notes are recorded in the session, and ends on drawn
     * shapes are handed to DrawBox's own binding pass so they follow the shape from then on.
     */
    fun snapLatestConnector(
        controller: DrawBoxController,
        session: CanvasSession?,
        documents: List<CanvasSceneDocument>,
        scope: CoroutineScope,
    ) {
        val current = controller.state.value
        val connector = CanvasSnapping.latestConnector(current.elements) ?: return
        val anchors = CanvasSnapping.anchors(current.elements, documents, connector.id)
        val snapped = CanvasSnapping.snap(connector, anchors, current.viewport.scale) ?: return
        if (snapped.points != connector.points) {
            controller.onIntent(Intent.SetElementPoints(connector.id, snapped.points))
        }
        if (snapped.boundToShape) {
            controller.onIntent(Intent.FinalizeArrowBindings(connector.id))
        }
        if (session != null && hasBoundEndpoint(snapped.binding)) {
            scope.launch { runCatching { session.bindArrow(connector.id, snapped.binding) } }
        }
    }

    /** [element] moved by [DUPLICATE_OFFSET] with a fresh id, the way whiteboards duplicate in place. */
    fun duplicateElement(element: Element): Element {
        val moved = element.translate(Offset(DUPLICATE_OFFSET, DUPLICATE_OFFSET))
        val id = "${element.id}-copy-${Clock.System.now().toEpochMilliseconds()}"
        return when (moved) {
            is Element.Shape -> moved.copy(id = id, startBinding = null, endBinding = null)
            is Element.Path -> moved.copy(id = id)
            is Element.Text -> moved.copy(id = id)
            else -> moved
        }
    }

    fun shapeSelectionPoint(shape: Element.Shape): Offset =
        when (shape.shapeType) {
            ShapeType.LINE,
            ShapeType.ARROW -> shape.points.first()
            else -> shape.bounds().let { Offset(it.center.x, it.top) }
        }

    /**
     * A point DrawBox's hit test finds [element] at: on the outline for closed shapes (an unfilled
     * rectangle is only hit on its stroke), the first point of a line, arrow or stroke, the centre
     * for text (hit by its box).
     */
    fun selectionPointOf(element: Element): Offset = when (element) {
        is Element.Shape -> shapeSelectionPoint(element)
        is Element.Path -> element.bounds().let { Offset(it.center.x, it.top) }
        else -> element.bounds().center
    }

    fun documentHistoryMessage(context: HistoryMessageContext): String =
        if (context.success) "${context.direction.past} ${context.label}"
        else "Could not ${context.direction.verb} ${context.label}"

    fun duplicateDrawnSelection(
        controller: DrawBoxController,
        elements: List<Element>,
        selectedIds: Set<String>,
        onStatus: (String) -> Unit,
    ): Boolean {
        if (selectedIds.isEmpty()) return false
        val copies = elements.filter { it.id in selectedIds }.map { duplicateElement(it) }
        copies.forEach { controller.onIntent(Intent.AddElement(it)) }
        controller.clearSelection()
        copies.forEach { copy ->
            controller.onIntent(Intent.SelectAt(selectionPointOf(copy), 4f))
        }
        onStatus("Duplicated ${copies.size} element(s)")
        return true
    }

    fun duplicateActiveNote(
        activeNoteId: String?,
        documents: List<CanvasSceneDocument>,
        session: CanvasSession?,
        onCreated: (String, CanvasSceneDocument, CanvasDocumentFrame, CanvasSession) -> Unit,
    ): Boolean {
        if (activeNoteId == null || session == null) return false
        val note = documents.firstOrNull { it.id == activeNoteId } ?: return false
        val baseFrame = note.frame ?: defaultNoteFrame(0)
        val frame = baseFrame.copy(x = baseFrame.x + DUPLICATE_OFFSET, y = baseFrame.y + DUPLICATE_OFFSET)
        val id = "${note.id.substringBefore('-')}-${Clock.System.now().toEpochMilliseconds()}"
        onCreated(id, note, frame, session)
        return true
    }

    fun findErasedNoteIds(area: EraserArea, documents: List<CanvasSceneDocument>): Set<String> =
        documents.filter { doc ->
            val f = doc.frame ?: return@filter false
            Rect(
                f.x - area.radius,
                f.y - area.radius,
                f.x + f.width + area.radius,
                f.y + f.height + area.radius,
            ).contains(area.world)
        }.map { it.id }.toSet()

    fun findMarqueeNoteIds(rect: Rect, documents: List<CanvasSceneDocument>): Set<String> =
        documents.filter { doc ->
            val f = doc.frame ?: return@filter false
            rect.overlaps(Rect(f.x, f.y, f.x + f.width, f.y + f.height))
        }.map { it.id }.toSet()

    fun buildMoveFrames(
        documents: List<CanvasSceneDocument>,
        selectedIds: Set<String>,
        offset: Offset,
    ): Map<String, CanvasDocumentFrame> =
        documents.filter { it.id in selectedIds }.mapNotNull { doc ->
            doc.frame?.let { doc.id to it.copy(x = it.x + offset.x, y = it.y + offset.y) }
        }.toMap()

    fun isPointInsideBounds(point: Offset, bounds: Rect): Boolean =
        point.x >= bounds.left && point.x <= bounds.right &&
            point.y >= bounds.top && point.y <= bounds.bottom

    fun isWorldPointInDocuments(world: Offset, documents: List<CanvasSceneDocument>): Boolean =
        documents.any { doc ->
            val f = doc.frame ?: return@any false
            world.x >= f.x && world.y >= f.y &&
                world.x <= f.x + f.width && world.y <= f.y + f.height
        }

    fun applyDrawingStep(
        context: HistoryActionContext,
        step: CanvasHistory.Step.Drawing,
        direction: HistoryDirection,
    ) {
        context.onApplyingHistory(true)
        if (direction.isRedo) context.controller.redo() else context.controller.undo()
        val documents = step.documents ?: return
        val session = context.session ?: return
        val ops = if (direction.isRedo) documents.redo else documents.undo
        context.scope.launch {
            runCatching { session.applyLocalStamped(ops) }
        }
    }

    fun applyDocumentsStep(
        context: HistoryActionContext,
        step: CanvasHistory.Step.Documents,
        direction: HistoryDirection,
    ) {
        val session = context.session ?: return
        val ops = if (direction.isRedo) step.redo else step.undo
        context.scope.launch {
            context.onApplyingHistory(true)
            val applied = runCatching { session.applyLocalStamped(ops) }
            context.onApplyingHistory(false)
            if (!applied.isSuccess) {
                if (direction.isRedo) context.history.undo() else context.history.redo()
            }
            context.onStatusMessage(
                documentHistoryMessage(
                    HistoryMessageContext(
                        label = step.label,
                        direction = direction,
                        success = applied.isSuccess,
                    ),
                ),
            )
        }
    }

    fun applyHistory(
        context: HistoryActionContext,
        step: CanvasHistory.Step?,
        direction: HistoryDirection,
    ) {
        when (step) {
            null -> Unit
            is CanvasHistory.Step.Drawing -> applyDrawingStep(context, step, direction)
            is CanvasHistory.Step.Documents -> applyDocumentsStep(context, step, direction)
        }
    }

    fun undoBoard(
        context: HistoryActionContext,
        drawingUnsaved: Boolean,
        canUndo: Boolean,
    ) {
        if (drawingUnsaved && canUndo) {
            context.onApplyingHistory(true)
            context.controller.undo()
            return
        }
        val step = context.history.undo()
        if (step != null) {
            applyHistory(context, step, HistoryDirection.Undo)
        } else if (canUndo) {
            context.onApplyingHistory(true)
            context.controller.undo()
        }
    }

    fun redoBoard(
        context: HistoryActionContext,
        canRedo: Boolean,
    ) {
        val step = context.history.redo()
        if (step != null) {
            applyHistory(context, step, HistoryDirection.Redo)
        } else if (canRedo) {
            context.onApplyingHistory(true)
            context.controller.redo()
        }
    }

    suspend fun recordDocumentChange(
        context: DocumentRecorderContext,
        request: DocumentChangeRequest,
        block: suspend () -> Unit,
    ) {
        val s = context.session
        if (s == null || context.isApplyingHistory()) {
            block()
            return
        }
        val before = s.documents()
        block()
        val step = CanvasDocumentUndo.stepBetween(before, s.documents(), request.label) ?: return
        val attached = request.attachToLastDrawing && context.history.addToLastDrawing(step)
        if (!attached) {
            context.history.record(step)
        }
    }

    fun detectNewEmptyTextElement(
        elements: List<Element>,
        previousIds: Set<String>?,
    ): Pair<Set<String>, String?> {
        val ids = CanvasTextElements.ids(elements)
        if (previousIds == null) return ids to null
        val added = ids - previousIds
        val emptyId = added.firstOrNull { CanvasTextElements.byId(elements, it)?.text.isNullOrEmpty() }
        return ids to emptyId
    }

    fun followConnectors(params: FollowConnectorsParams): Map<String, CanvasDocumentFrame> {
        val frames = params.documents.mapNotNull { doc -> doc.frame?.let { doc.id to it } }.toMap()
        frames.forEach { (id, frame) ->
            if (params.lastFrames[id] == frame || id !in params.lastFrames) return@forEach
            params.arrowBindings.forEach { (elementId, binding) ->
                val connector = params.elements.firstOrNull { it.id == elementId } as? Element.Shape
                    ?: return@forEach
                CanvasSnapping.follow(connector, binding, id, frame)?.let { geometry ->
                    params.controller.onIntent(Intent.SetElementPoints(elementId, geometry.points))
                    if (geometry.bend != connector.bend) {
                        params.controller.onIntent(Intent.SetLineBend(elementId, geometry.bend))
                    }
                }
            }
        }
        return frames
    }

    private fun validatePenPosition(
        event: CanvasPenEvent,
        params: PenConsumerParams,
    ): Offset? {
        val current = params.controller.state.value
        if (current.tempPanActive) return null
        val board = params.boardBounds ?: return null
        val inRoot = Offset(event.x * params.penDensity, event.y * params.penDensity)
        if (!isPointInsideBounds(inRoot, board)) return null
        if (params.chromeRegions.contains(inRoot)) return null
        val onBoard = Offset(inRoot.x - board.left, inRoot.y - board.top)
        return current.viewport.screenToWorld(onBoard)
    }

    private fun handleEraserEvent(
        event: CanvasPenEvent,
        world: Offset,
        controller: DrawBoxController,
        onEraseArea: (EraserArea) -> Unit,
    ): Boolean {
        val isErasing = event.phase == CanvasPenEvent.Phase.DOWN || event.phase == CanvasPenEvent.Phase.MOVE
        if (!isErasing) return false
        val current = controller.state.value
        controller.onIntent(Intent.EraseAt(world, current.eraserSize))
        onEraseArea(EraserArea(world, current.eraserSize / current.viewport.scale.coerceAtLeast(0.01f)))
        return true
    }

    private fun handleDrawPhase(
        params: DrawPhaseParams,
        activeStroke: CanvasPenStroke?,
    ): Pair<CanvasPenStroke?, Boolean> {
        val current = params.controller.state.value
        val event = params.event
        val world = params.world
        val preview = params.penPreview
        return when (event.phase) {
            CanvasPenEvent.Phase.DOWN -> {
                preview.clear()
                val started = current.beginPenStroke()
                val added = started.add(world, event.pressure)
                if (added != null) preview += added
                started to true
            }
            CanvasPenEvent.Phase.MOVE -> {
                val added = activeStroke?.add(world, event.pressure)
                if (added != null) preview += added
                activeStroke to (activeStroke != null)
            }
            CanvasPenEvent.Phase.UP, CanvasPenEvent.Phase.OUT -> {
                preview.clear()
                val path = activeStroke?.finish("pen-${Clock.System.now().toEpochMilliseconds()}")
                if (path != null) params.controller.onIntent(Intent.AddElement(path))
                null to (activeStroke != null)
            }
            CanvasPenEvent.Phase.IN -> activeStroke to false
        }
    }

    fun createPenConsumer(params: PenConsumerParams): (CanvasPenEvent) -> Boolean {
        var stroke: CanvasPenStroke? = null
        var strokeStartedOnDocument = false
        return consumer@{ event ->
            val world = validatePenPosition(event, params) ?: return@consumer false
            if (event.tool == CanvasPenTool.ERASER) {
                return@consumer handleEraserEvent(event, world, params.controller, params.onEraseArea)
            }
            val current = params.controller.state.value
            if (event.tool != CanvasPenTool.DRAW || !current.mode.isFreehandDrawing()) return@consumer false
            if (event.phase == CanvasPenEvent.Phase.DOWN) {
                strokeStartedOnDocument = isWorldPointInDocuments(world, params.session?.documents().orEmpty())
            }
            if (strokeStartedOnDocument) return@consumer false
            val drawParams = DrawPhaseParams(event, world, params.controller, params.penPreview)
            val (updatedStroke, handled) = handleDrawPhase(drawParams, stroke)
            stroke = updatedStroke
            handled
        }
    }
}
