package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Text inside a shape: the label of a box on a whiteboard. A shape's text is part of the shape,
 * so it moves, resizes, rotates, undoes, serialises and exports with it, with no second element
 * to keep in step.
 */

/** Em size, in world units, of text typed into a shape that has not been given one. */
const val DEFAULT_SHAPE_FONT_SIZE: Float = 20f

/** Whether this shape has an inside to hold text: rectangles, circles and triangles. */
val Element.Shape.canHoldText: Boolean
    get() = shapeType == ShapeType.RECTANGLE ||
        shapeType == ShapeType.CIRCLE ||
        shapeType == ShapeType.TRIANGLE

/** The colour the shape's text is drawn in: its own, or the stroke's when it has none. */
val Element.Shape.resolvedTextColor: Color get() = textColor ?: strokeColor

/**
 * Where the shape's text is laid out, in the shape's own unrotated world space: the widest box
 * that sits inside the shape with some room to spare. Text is wrapped to its width and centred
 * on it vertically.
 *
 * - Rectangle: the rectangle, inset from the stroke.
 * - Circle: the square inscribed in it.
 * - Triangle: the lower middle, where the triangle is wide enough to write in.
 */
fun Element.Shape.textBox(): Rect {
    val b = bounds()
    return when (shapeType) {
        ShapeType.CIRCLE -> {
            val half = min(b.width, b.height) * INSCRIBED_HALF
            Rect(b.center.x - half, b.center.y - half, b.center.x + half, b.center.y + half)
        }
        ShapeType.TRIANGLE -> Rect(
            left = b.left + b.width * TRIANGLE_SIDE,
            top = b.top + b.height * TRIANGLE_TOP,
            right = b.right - b.width * TRIANGLE_SIDE,
            bottom = b.bottom - b.height * TRIANGLE_BOTTOM,
        )
        else -> {
            val inset = min(TEXT_INSET + strokeWidth * 0.5f, min(b.width, b.height) * MAX_INSET_FRACTION)
            b.deflate(inset)
        }
    }.let { box -> if (box.width < MIN_BOX) Rect(Offset(box.center.x - MIN_BOX / 2, box.top), box.bottomRight.copy(x = box.center.x + MIN_BOX / 2)) else box }
}

/** Where a block of text [height] tall is drawn so it sits centred in [textBox]. */
fun Element.Shape.textTopLeft(height: Float): Offset {
    val box = textBox()
    return Offset(box.left, box.center.y - max(height, 0f) / 2f)
}

private const val INSCRIBED_HALF = 0.3535f // half the side of the square inscribed in the circle
private const val TRIANGLE_SIDE = 0.25f
private const val TRIANGLE_TOP = 0.45f
private const val TRIANGLE_BOTTOM = 0.08f
private const val TEXT_INSET = 12f
private const val MAX_INSET_FRACTION = 0.2f
private const val MIN_BOX = 24f
