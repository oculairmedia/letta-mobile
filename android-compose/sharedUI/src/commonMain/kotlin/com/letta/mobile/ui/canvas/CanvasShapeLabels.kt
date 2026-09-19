package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasSceneDocument
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.bounds

/**
 * Text inside a shape.
 *
 * A label is an ordinary transparent text document whose id names the shape it belongs to, so it
 * inherits everything a note already has — the block editor, persistence through the op log, undo,
 * the agent seeing it as text — and needs no binding of its own in the scene. Arrow bindings are a
 * schema; this is a naming convention, and it buys the same thing for a fraction of the surface.
 *
 * The label follows its shape because the board re-frames it whenever the shape's bounds change,
 * and goes with the shape when the shape is deleted.
 */
internal object CanvasShapeLabels {

    private const val PREFIX = "label-"

    /** Shapes that can hold a label: the closed ones. A line has no inside to write in. */
    fun canLabel(element: Element): Boolean =
        element is Element.Shape && (
            element.shapeType == ShapeType.RECTANGLE ||
                element.shapeType == ShapeType.CIRCLE ||
                element.shapeType == ShapeType.TRIANGLE
            )

    fun labelIdOf(shapeId: String): String = "$PREFIX$shapeId"

    fun shapeIdOf(documentId: String): String? = documentId.removePrefix(PREFIX).takeIf { it != documentId }

    /**
     * Where a label sits inside [bounds]: the shape's box, inset so the text does not touch the
     * stroke, and never smaller than something you can still type into.
     */
    fun frameFor(bounds: Rect): CanvasDocumentFrame {
        val inset = (minOf(bounds.width, bounds.height) * INSET_FRACTION).coerceAtMost(MAX_INSET)
        val width = (bounds.width - inset * 2).coerceAtLeast(MIN_SIDE)
        val height = (bounds.height - inset * 2).coerceAtLeast(MIN_SIDE)
        return CanvasDocumentFrame(
            x = bounds.center.x - width / 2f,
            y = bounds.center.y - height / 2f,
            width = width,
            height = height,
        )
    }

    /** The shape under [world], topmost first, that can take a label. */
    fun shapeAt(elements: List<Element>, world: Offset): Element.Shape? =
        elements.asReversed().filterIsInstance<Element.Shape>().firstOrNull { shape ->
            canLabel(shape) && shape.bounds().contains(world)
        }

    /**
     * The label re-frames that [elements] now call for: every label document whose shape has moved
     * or been resized away from it, and every label whose shape is gone.
     */
    fun reconcile(
        elements: List<Element>,
        documents: List<CanvasSceneDocument>,
    ): Reconciliation {
        val shapesById = elements.filterIsInstance<Element.Shape>().associateBy { it.id }
        val moved = mutableMapOf<String, CanvasDocumentFrame>()
        val orphaned = mutableListOf<String>()
        documents.forEach { doc ->
            val shapeId = shapeIdOf(doc.id) ?: return@forEach
            val shape = shapesById[shapeId]
            if (shape == null) {
                orphaned += doc.id
                return@forEach
            }
            val want = frameFor(shape.bounds())
            if (doc.frame != want) moved[doc.id] = want
        }
        return Reconciliation(moved, orphaned)
    }

    /** [moved] are labels to re-frame onto their shape; [orphaned] are labels whose shape is gone. */
    data class Reconciliation(
        val moved: Map<String, CanvasDocumentFrame>,
        val orphaned: List<String>,
    )

    private const val INSET_FRACTION = 0.12f
    private const val MAX_INSET = 16f
    private const val MIN_SIDE = 24f
}
