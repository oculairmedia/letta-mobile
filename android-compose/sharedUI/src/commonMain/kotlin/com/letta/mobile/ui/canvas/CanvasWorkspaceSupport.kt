package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.letta.mobile.data.canvas.CanvasArrowBinding
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasDocumentUndo
import com.letta.mobile.data.canvas.CanvasHistory
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.data.canvas.CanvasSession
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import io.ak1.drawbox.domain.model.State as DrawBoxState
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.model.canHoldText
import io.ak1.drawbox.domain.model.resolvedTextColor
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

internal data class ConnectorUpdateParams(
    val elementId: String,
    val binding: CanvasArrowBinding,
    val documentId: String,
    val frame: CanvasDocumentFrame,
)

internal data class ExternalSyncParams(
    val doc: CanvasDocument?,
    val lastImportedRev: Long,
    val lastExportedJson: String?,
    val lastDrawing: String?,
)

internal data class ExternalSyncResult(
    val shouldImport: Boolean,
    val cleanJson: String?,
    val newImportedRev: Long,
    val statusMessage: String?,
)

internal data class FinalPointerPassParams(
    val event: PointerEvent,
    val current: DrawBoxState,
    val onEraseNotes: (EraserArea) -> Unit,
    val onSnapLatestConnector: () -> Unit,
)

/** What decides whether quick-create targets show, and where. */
internal data class QuickCreateAnchorParams(
    val state: DrawBoxState,
    val activeNote: com.letta.mobile.data.canvas.CanvasSceneDocument?,
    val editing: Boolean,
)

/** What the floating selection bar is anchored to. */
internal data class BarAnchorParams(
    val state: DrawBoxState,
    val documents: List<com.letta.mobile.data.canvas.CanvasSceneDocument>,
    val selectedNoteIds: Set<String>,
    val activeNote: com.letta.mobile.data.canvas.CanvasSceneDocument?,
    val groupOffset: Offset,
)

internal data class FinalPointerPassResult(
    val altHeld: Boolean,
    val drawingConnectorAt: Offset?,
)

internal data class DeleteFocusedParams(
    val selectedNoteIds: Set<String>,
    val hasSelection: Boolean,
    val activeNoteId: String?,
    val expandedNoteId: String?,
    val hasSession: Boolean,
    val onDeleteSelection: () -> Unit,
    val onDeleteNotes: (Set<String>) -> Unit,
    val onDeleteActiveNote: (String) -> Unit,
)

internal data class RequestTextEditParams(
    val offset: Offset,
    val tolerance: Float,
    val elements: List<Element>,
    val onEditText: (String) -> Unit,
    val onEditShapeText: (Element.Shape) -> Unit,
)

internal data class ReconcileShapeLabelsParams(
    val elements: List<Element>,
    val liveDocuments: List<CanvasSceneDocument>,
    val session: CanvasSession?,
    val sessionDoc: CanvasDocument?,
    val importedRevision: Long,
    val initialLoadDone: Boolean,
    val onClearActiveNoteIf: (String) -> Unit,
    val recordDeletion: suspend (suspend () -> Unit) -> Unit,
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
            is Element.Image -> moved.copy(id = id)
        }
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
        controller.selectIds(copies.map { it.id }.toSet())
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
        val frames = extractDocumentFrames(params.documents)
        syncMovedFrames(params, frames)
        return frames
    }

    private fun extractDocumentFrames(documents: List<CanvasSceneDocument>): Map<String, CanvasDocumentFrame> =
        documents.mapNotNull { doc -> doc.frame?.let { doc.id to it } }.toMap()

    private fun syncMovedFrames(params: FollowConnectorsParams, frames: Map<String, CanvasDocumentFrame>) {
        for ((id, frame) in frames) {
            val prev = params.lastFrames[id]
            if (prev != null && prev != frame) {
                updateConnectorsForFrame(params, id, frame)
            }
        }
    }

    private fun updateConnectorsForFrame(params: FollowConnectorsParams, id: String, frame: CanvasDocumentFrame) {
        for ((elementId, binding) in params.arrowBindings) {
            updateSingleConnector(
                params = params,
                update = ConnectorUpdateParams(
                    elementId = elementId,
                    binding = binding,
                    documentId = id,
                    frame = frame,
                ),
            )
        }
    }

    private fun updateSingleConnector(
        params: FollowConnectorsParams,
        update: ConnectorUpdateParams,
    ) {
        val connector = params.elements.firstOrNull { it.id == update.elementId } as? Element.Shape ?: return
        val geometry = CanvasSnapping.follow(connector, update.binding, update.documentId, update.frame) ?: return
        params.controller.onIntent(Intent.SetElementPoints(update.elementId, geometry.points))
        if (geometry.bend != connector.bend) {
            params.controller.onIntent(Intent.SetLineBend(update.elementId, geometry.bend))
        }
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

    fun evaluateExternalDocSync(params: ExternalSyncParams): ExternalSyncResult? {
        val doc = params.doc ?: return null
        if (doc.revision <= params.lastImportedRev) return null
        val sceneJson = doc.sceneJson
        val diffJson = sceneJson.isNotBlank() && sceneJson != params.lastExportedJson
        val clean = if (diffJson) CanvasOpProjector.stripMetadataForDrawBox(sceneJson) else null
        val shouldImport = clean != null && !CanvasOpProjector.drawingsEqual(clean, params.lastDrawing)
        val msg = if (shouldImport) "Agent updated canvas (rev ${doc.revision})" else null
        return ExternalSyncResult(
            shouldImport = shouldImport,
            cleanJson = clean,
            newImportedRev = doc.revision,
            statusMessage = msg,
        )
    }

    fun computeJsonExportStatus(json: String, wasAutosaving: Boolean): String? {
        if (wasAutosaving) return null
        return if (json.contains("\"elements\"")) {
            "Exported JSON (${json.length} chars, verified)"
        } else {
            "Warning: Exported JSON missing 'elements' key"
        }
    }

    fun computeSvgExportStatus(svg: String): String =
        if (svg.contains("<svg", ignoreCase = true)) {
            "Exported SVG (${svg.length} chars, verified)"
        } else {
            "Warning: Exported SVG missing '<svg' tag"
        }

    fun handleSvgShareToChat(
        svg: String,
        maxBytes: Int,
        onShare: (ByteArray, String) -> Unit,
    ): String {
        val bytes = svg.encodeToByteArray()
        return if (bytes.size <= maxBytes) {
            onShare(bytes, "image/svg+xml")
            "Shared canvas SVG (${bytes.size} bytes) to chat"
        } else {
            "Error: Exported SVG exceeds attachment limit (${bytes.size} bytes)"
        }
    }

    /**
     * Whether a save is a step to undo. An image given its asset ref, or its full bytes in place of
     * its preview, is the board keeping its books, not something anyone did: undoing it would only
     * strip the ref (to be adopted again) or put the preview back.
     */
    fun shouldRecordDrawingStep(
        elementsBefore: List<Element>?,
        elementsNow: List<Element>,
        isApplyingHistory: Boolean,
    ): Boolean = !isApplyingHistory && elementsBefore != null && !sameDrawing(elementsBefore, elementsNow)

    /**
     * Whether [a] and [b] are the same drawing as far as anyone did anything: equal, or differing
     * only in images' asset bookkeeping. What decides an undo step decides which undo applies too.
     */
    fun sameDrawing(a: List<Element>, b: List<Element>): Boolean =
        a == b || a.map(::withoutAssetBookkeeping) == b.map(::withoutAssetBookkeeping)

    private fun withoutAssetBookkeeping(element: Element): Element =
        if (element is Element.Image) {
            element.copy(bytes = ByteArray(0), assetRef = null, mediaType = null, preview = null)
        } else {
            element
        }

    fun deleteFocused(params: DeleteFocusedParams): Boolean {
        if (params.selectedNoteIds.isNotEmpty() && params.hasSession) {
            params.onDeleteNotes(params.selectedNoteIds)
            if (params.hasSelection) params.onDeleteSelection()
            return true
        }
        if (params.hasSelection) {
            params.onDeleteSelection()
            return true
        }
        val id = params.activeNoteId ?: return false
        if (!params.hasSession || params.expandedNoteId != null) return false
        params.onDeleteActiveNote(id)
        return true
    }

    /**
     * Where a request for a caret lands: an existing text element, else the shape under it that
     * holds text (its own text, native to the shape since letta-mobile-8cik1).
     */
    fun handleRequestTextEdit(params: RequestTextEditParams) {
        val text = CanvasTextElements.at(params.elements, params.offset, params.tolerance)
        if (text != null) {
            params.onEditText(text.id)
            return
        }
        val shape = io.ak1.drawbox.domain.model.topmostHit(params.elements, params.offset, params.tolerance, hollowInterior = true)
            as? Element.Shape ?: return
        if (shape.canHoldText) params.onEditShapeText(shape)
    }

    fun handleWheelZoom(
        event: PointerEvent,
        controller: DrawBoxController,
        zoomStep: Float = WHEEL_ZOOM_STEP,
    ): Boolean {
        if (event.type != PointerEventType.Scroll) return false
        val modifiers = event.keyboardModifiers
        if (!modifiers.isCtrlPressed && !modifiers.isMetaPressed) return false
        val change = event.changes.firstOrNull() ?: return false
        val delta = event.changes.fold(0f) { acc, c -> acc + c.scrollDelta.y }
        if (delta == 0f) return false
        val factor = if (delta > 0f) 1f / zoomStep else zoomStep
        controller.zoomBy(factor, change.position)
        event.changes.forEach { it.consume() }
        return true
    }

    private fun isConnectorMode(mode: io.ak1.drawbox.domain.model.Mode): Boolean =
        mode == io.ak1.drawbox.domain.model.Mode.LINE || mode == io.ak1.drawbox.domain.model.Mode.ARROW

    private fun eraseNotesIfErasing(
        params: FinalPointerPassParams,
        isPressed: Boolean,
        position: Offset?,
    ) {
        if (params.current.mode != io.ak1.drawbox.domain.model.Mode.ERASER) return
        if (!isPressed || position == null) return
        val current = params.current
        val radius = current.eraserSize / current.viewport.scale.coerceAtLeast(0.01f)
        params.onEraseNotes(EraserArea(world = current.viewport.screenToWorld(position), radius = radius))
    }

    private fun connectorAnchorAt(
        event: PointerEvent,
        current: DrawBoxState,
        isPressed: Boolean,
        position: Offset?,
    ): Offset? {
        if (!isPressed || position == null) return null
        val type = event.type
        val isDragOrPress = type == PointerEventType.Press || type == PointerEventType.Move
        return if (isDragOrPress) current.viewport.screenToWorld(position) else null
    }

    fun handleFinalPointerPass(params: FinalPointerPassParams): FinalPointerPassResult {
        val event = params.event
        val altHeld = event.keyboardModifiers.isAltPressed
        val position = event.changes.firstOrNull()?.position
        val isPressed = event.changes.any { it.pressed }

        eraseNotesIfErasing(params, isPressed, position)

        val connectorAt = when {
            !isConnectorMode(params.current.mode) -> null
            event.type == PointerEventType.Release -> {
                if (!altHeld) params.onSnapLatestConnector()
                null
            }
            else -> connectorAnchorAt(event, params.current, isPressed, position)
        }
        return FinalPointerPassResult(altHeld = altHeld, drawingConnectorAt = connectorAt)
    }

    /** The selected [shape]'s text, as the property panel's Text target edits it. */
    fun shapeTextActions(shape: Element.Shape, controller: DrawBoxController): ShapeTextActions = ShapeTextActions(
        color = shape.resolvedTextColor,
        onColor = { controller.onIntent(io.ak1.drawbox.domain.model.Intent.SetSelectedTextColor(it)) },
        fontSize = shape.fontSize,
        onFontSize = { controller.onIntent(io.ak1.drawbox.domain.model.Intent.SetSelectedFontSize(it)) },
        fontFamily = shape.fontFamilyKey,
        onFontFamily = { controller.onIntent(io.ak1.drawbox.domain.model.Intent.SetSelectedFontFamily(it)) },
        alignment = shape.textAlignment,
        onAlignment = { controller.onIntent(io.ak1.drawbox.domain.model.Intent.SetSelectedTextAlignment(it)) },
    )

    /**
     * Folds each shape label document (see [CanvasShapeLabels]) into its shape: the label's words
     * become the shape's text, its colour and alignment the text's, and the document and its
     * ownership record go. A shape that already has text keeps it; its label is dropped.
     */
    suspend fun foldLabelsIntoShapes(session: CanvasSession, controller: DrawBoxController) {
        val owners = session.labelOwners()
        if (owners.isEmpty()) return
        val labels = session.documents().filter { it.id in owners }
        labels.forEach { label ->
            // Not loaded yet (or already gone): leave it. Folding needs the shape to fold into, and an
            // orphaned label is the reconciler's to remove, not this.
            val shape = controller.state.value.elements.firstOrNull { it.id == owners[label.id] } as? Element.Shape
                ?: return@forEach
            if (shape.canHoldText && shape.text.isEmpty()) {
                val words = com.letta.mobile.data.canvas.CanvasDocumentText.plainText(label.json)
                if (words.isNotEmpty()) {
                    controller.onIntent(io.ak1.drawbox.domain.model.Intent.UpdateElement(labelledShape(shape, words, label.style)))
                }
            }
            runCatching { session.removeDocument(label.id) }
            runCatching { session.setLabelOwner(label.id, null) }
        }
    }

    private fun labelledShape(shape: Element.Shape, words: String, style: com.letta.mobile.data.canvas.CanvasTextStyle?): Element.Shape =
        shape.copy(
            text = words,
            textColor = parseHexColor(style?.textColor) ?: shape.textColor,
            textAlignment = when (style?.align) {
                "start" -> io.ak1.drawbox.domain.model.TextAlignment.LEFT
                "end" -> io.ak1.drawbox.domain.model.TextAlignment.RIGHT
                else -> shape.textAlignment
            },
            fontFamilyKey = style?.fontFamily ?: shape.fontFamilyKey,
            fontSize = style?.fontScale?.let { shape.fontSize * it } ?: shape.fontSize,
        )

    /** True when [element] is somewhere to type: a text element, or a shape that holds text. */
    fun holdsText(element: Element?): Boolean =
        element is Element.Text || (element is Element.Shape && element.canHoldText)

    /**
     * True when a finger at [screen] is on open board in the select tool: not on an element and not
     * on the current selection (whose handles reach just outside it). That is where a one-finger
     * drag pans on a phone instead of starting a marquee.
     */
    fun isOpenBoard(state: DrawBoxState, screen: Offset, tolerance: Float): Boolean {
        if (state.mode != io.ak1.drawbox.domain.model.Mode.SELECT) return false
        val world = state.viewport.screenToWorld(screen)
        val slack = tolerance * HANDLE_SLACK / state.viewport.scale
        if (state.elements.any { it.id in state.selectedIds && it.bounds().inflate(slack).contains(world) }) return false
        return elementAt(state, world, tolerance / state.viewport.scale) == null
    }

    /**
     * Where quick-create targets go, on screen: around the one selected shape that holds text, or
     * the active note when nothing drawn is selected. None while typing, when they would crowd
     * the text, and none for lines, strokes or a shape's legacy label.
     */
    fun quickCreateAnchor(params: QuickCreateAnchorParams): Rect? {
        if (params.editing) return null
        val state = params.state
        val shape = state.elements.singleOrNull { it.id in state.selectedIds }
        if (state.selectedIds.size == 1 && shape is Element.Shape && shape.canHoldText) {
            return selectionScreenRect(state.elements, state.selectedIds, emptyList(), state.viewport)
        }
        if (state.selectedIds.isNotEmpty()) return null
        val note = params.activeNote ?: return null
        if (CanvasShapeLabels.shapeIdOf(note.id) != null) return null
        val frame = note.frame ?: return null
        return selectionScreenRect(emptyList(), emptySet(), listOf(frame.toRect()), state.viewport)
    }

    /**
     * The topmost element under [world], by DrawBox's own hit test (geometry, z-order, and inside
     * hollow shapes when the board picks there), so the board agrees with what a press selects.
     */
    fun elementAt(state: DrawBoxState, world: Offset, tolerance: Float): Element? =
        io.ak1.drawbox.domain.model.topmostHit(state.elements, world, tolerance, state.selectInsideHollowShapes)

    /**
     * The closed shape a gesture just drew, if it drew one: new since the press ([idsAtPress]), and
     * large enough to be a shape rather than a stray tap. Drawing a shape is how you say you want
     * one with something in it, so the board opens its text straight away.
     */
    fun shapeJustDrawn(state: DrawBoxState, idsAtPress: Set<String>): Element? {
        if (idsAtPress.isEmpty() && state.elements.isEmpty()) return null
        val drawn = state.elements.lastOrNull { it.id !in idsAtPress && CanvasShapeLabels.canLabel(it) } ?: return null
        val bounds = drawn.bounds()
        return drawn.takeIf { bounds.width >= MIN_DRAWN_SHAPE && bounds.height >= MIN_DRAWN_SHAPE }
    }

    /**
     * Where on screen the selection bar should float: the drawn selection and the selected notes,
     * or the active note - and for a shape's label, the shape itself, so the bar clears the shape
     * rather than sitting on its outline. Null with nothing to float on.
     */
    fun barAnchor(params: BarAnchorParams): Rect? {
        val state = params.state
        val labelShapeId = params.activeNote?.id?.let(CanvasShapeLabels::shapeIdOf)
            ?.takeIf { id -> state.elements.any { it.id == id } }
        val notes = when {
            params.selectedNoteIds.isNotEmpty() -> params.documents.filter { it.id in params.selectedNoteIds }
            params.activeNote != null && labelShapeId == null -> listOf(params.activeNote)
            else -> emptyList()
        }
        val offset = if (params.selectedNoteIds.isNotEmpty()) params.groupOffset else Offset.Zero
        return selectionScreenRect(
            elements = state.elements,
            selectedIds = state.selectedIds + listOfNotNull(labelShapeId),
            noteRects = noteRects(notes, offset),
            viewport = state.viewport,
        )
    }

    suspend fun reconcileShapeLabels(params: ReconcileShapeLabelsParams) {
        val s = params.session ?: return
        val work = CanvasShapeLabels.reconcile(params.elements, params.liveDocuments, s.labelOwners())
        if (work.moved.isNotEmpty()) runCatching { s.moveDocuments(work.moved) }
        if (!params.initialLoadDone || params.sessionDoc?.revision != params.importedRevision) return
        params.recordDeletion {
            work.orphaned.forEach { id ->
                params.onClearActiveNoteIf(id)
                runCatching { s.removeDocument(id) }
                runCatching { s.setLabelOwner(id, null) }
            }
        }
    }
}

/** Smaller than this (in board units) a "shape" is a tap that slipped, not a shape to type into. */
private const val MIN_DRAWN_SHAPE = 16f

/** How far past a selection its handles reach, in multiples of the pick tolerance. */
private const val HANDLE_SLACK = 3f

/** This frame as a board rectangle. */
internal fun com.letta.mobile.data.canvas.CanvasDocumentFrame.toRect(): Rect = Rect(x, y, x + width, y + height)
