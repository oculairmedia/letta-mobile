package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Changing a shape into another after it is drawn, the way Miro's shape picker does on a
 * selection: a box becomes a circle or a triangle in place, keeping its text, colours, stroke,
 * rotation and the arrows joined to it.
 */
internal object CanvasReshape {
    /** The shapes a closed shape can become: the ones with an inside. */
    val Types: List<ShapeType> = listOf(ShapeType.RECTANGLE, ShapeType.CIRCLE, ShapeType.TRIANGLE)

    fun canReshape(element: Element): Boolean = element is Element.Shape && element.shapeType in Types

    /**
     * [shape] as a [type], centred where it was. Boxes and triangles span their bounds from
     * corner to corner; a circle's points are the ends of a diameter, so a box becomes the widest
     * circle inside it and a circle becomes the square around it.
     */
    @OptIn(ExperimentalTime::class)
    fun reshaped(shape: Element.Shape, type: ShapeType): Element.Shape {
        if (shape.shapeType == type || type !in Types || shape.shapeType !in Types) return shape
        val b = shape.bounds()
        val points = if (type == ShapeType.CIRCLE) {
            val r = min(b.width, b.height) / 2f
            listOf(Offset(b.center.x - r, b.center.y), Offset(b.center.x + r, b.center.y))
        } else {
            listOf(b.topLeft, b.bottomRight)
        }
        return shape.copy(shapeType = type, points = points, modifiedAt = Clock.System.now().toEpochMilliseconds())
    }

    /** Reshapes every selected closed shape to [type] as one undo step. */
    fun apply(controller: DrawBoxController, type: ShapeType) {
        val state = controller.state.value
        val changed = state.elements
            .filter { it.id in state.selectedIds }
            .filterIsInstance<Element.Shape>()
            .map { it to reshaped(it, type) }
            .filter { (before, after) -> before !== after }
        if (changed.isEmpty()) return
        controller.onIntent(Intent.BeginTransform)
        changed.forEach { (_, after) ->
            controller.onIntent(Intent.UpdateElement(after))
            // Same bounds, but it runs the binding pass, so joined arrows meet the new outline.
            controller.onIntent(Intent.SetElementBounds(after.id, after.bounds()))
        }
        controller.onIntent(Intent.EndTransform)
    }
}

/** What the selection bar offers for changing the selected shape's kind. */
class ShapeReshapeActions(
    val current: ShapeType?,
    val onPick: (ShapeType) -> Unit,
)
