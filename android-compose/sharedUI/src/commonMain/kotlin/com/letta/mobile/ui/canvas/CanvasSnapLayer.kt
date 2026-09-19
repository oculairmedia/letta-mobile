package com.letta.mobile.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasArrowBinding
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasEndBinding
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.data.canvas.CanvasSnap
import com.letta.mobile.data.canvas.CanvasSnapAnchor
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.Viewport
import io.ak1.drawbox.domain.model.bounds
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Connector snapping on the board: the anchors a line or arrow end can snap to (edge midpoints
 * and centres of drawn rectangles, circles and triangles, and of note and text frames), the
 * indicator drawn while an end is near one, and the maths that keeps a bound end on a moved
 * note. Shape bindings themselves are DrawBox's (`startBinding`/`endBinding` plus
 * `FinalizeArrowBindings`); document bindings are the session's `arrowBindings`.
 */
internal object CanvasSnapping {
    /** Snap radius on screen; divided by the viewport scale to compare in world units. */
    const val RADIUS_PX = 12f

    fun isConnector(element: Element): Boolean =
        element is Element.Shape && (element.shapeType == ShapeType.LINE || element.shapeType == ShapeType.ARROW)

    private fun isSnapTarget(element: Element): Boolean =
        element is Element.Shape && (
            element.shapeType == ShapeType.RECTANGLE || element.shapeType == ShapeType.CIRCLE || element.shapeType == ShapeType.TRIANGLE
        )

    /** Every anchor on the board except those of [excludeId] (the connector being drawn). */
    fun anchors(elements: List<Element>, documents: List<CanvasSceneDocument>, excludeId: String?): List<CanvasSnapAnchor> {
        val shapes = elements.filter { it.id != excludeId && isSnapTarget(it) }.flatMap { shape ->
            val r = shape.bounds()
            CanvasSnap.anchorsOf(r.left, r.top, r.width, r.height, shape.id, isDocument = false)
        }
        val notes = documents.mapNotNull { doc -> doc.frame?.let { CanvasSnap.anchorsOf(it, doc.id) } }.flatten()
        return shapes + notes
    }

    fun nearest(world: Offset, anchors: List<CanvasSnapAnchor>, scale: Float): CanvasSnapAnchor? =
        CanvasSnap.nearest(world.x, world.y, anchors, RADIUS_PX / scale.coerceAtLeast(0.01f))

    /** The connector most recently added, the one a release just finished drawing. */
    fun latestConnector(elements: List<Element>): Element.Shape? =
        elements.filterIsInstance<Element.Shape>().filter(::isConnector).maxByOrNull { it.createdAt }

    /** What a snapped connector becomes: its new points, and which ends now bind to documents. */
    data class Snapped(val points: List<Offset>, val binding: CanvasArrowBinding, val boundToShape: Boolean)

    /** Snaps both ends of [connector] to the nearest anchors within radius; null when neither end is near one. */
    fun snap(connector: Element.Shape, anchors: List<CanvasSnapAnchor>, scale: Float): Snapped? {
        if (connector.points.size < 2) return null
        val start = nearest(connector.points.first(), anchors, scale)
        val end = nearest(connector.points.last(), anchors, scale)
        if (start == null && end == null) return null
        val points = connector.points.toMutableList()
        start?.let { points[0] = Offset(it.x, it.y) }
        end?.let { points[points.lastIndex] = Offset(it.x, it.y) }
        val binding = CanvasArrowBinding(
            start = start?.takeIf { it.isDocument }?.let { CanvasEndBinding(it.targetId, it.side) },
            end = end?.takeIf { it.isDocument }?.let { CanvasEndBinding(it.targetId, it.side) },
        )
        val boundToShape = (start != null && !start.isDocument) || (end != null && !end.isDocument)
        return Snapped(points, binding, boundToShape)
    }

    /** [connector]'s points with every end bound to [documentId] moved onto [frame]. */
    fun follow(connector: Element.Shape, binding: CanvasArrowBinding, documentId: String, frame: CanvasDocumentFrame): List<Offset>? {
        if (connector.points.size < 2) return null
        var changed = false
        val points = connector.points.toMutableList()
        binding.start?.takeIf { it.documentId == documentId }?.let { end ->
            val (x, y) = CanvasSnap.anchorOn(frame, end.side)
            points[0] = Offset(x, y); changed = true
        }
        binding.end?.takeIf { it.documentId == documentId }?.let { end ->
            val (x, y) = CanvasSnap.anchorOn(frame, end.side)
            points[points.lastIndex] = Offset(x, y); changed = true
        }
        return points.takeIf { changed }
    }
}

/** A ring where a connector end will snap, drawn over the board in screen space. */
@Composable
internal fun CanvasSnapIndicator(anchor: CanvasSnapAnchor, viewport: Viewport, modifier: Modifier = Modifier) {
    val color = androidx.compose.material3.MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.fillMaxSize().semantics { contentDescription = "Snap ${anchor.side} of ${anchor.targetId}" }) {
        val centre = viewport.worldToScreen(Offset(anchor.x, anchor.y))
        drawCircle(color, radius = RING_RADIUS.toPx(), center = centre, style = Stroke(width = RING_STROKE.toPx()))
        drawCircle(color, radius = DOT_RADIUS.toPx(), center = centre)
    }
}

private val RING_RADIUS = LettaDimens.Space.sm
private val RING_STROKE = LettaDimens.Space.hair
private val DOT_RADIUS = LettaDimens.Space.hair
