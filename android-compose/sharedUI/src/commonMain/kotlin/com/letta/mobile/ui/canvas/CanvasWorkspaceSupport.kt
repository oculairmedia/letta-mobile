package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
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

internal object CanvasWorkspaceSupport {
    internal const val DUPLICATE_OFFSET = 20f

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
        if (snapped.boundToShape) controller.onIntent(Intent.FinalizeArrowBindings(connector.id))
        if (session != null && (snapped.binding.start != null || snapped.binding.end != null)) {
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
}
