package io.ak1.drawbox

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.canHoldText
import io.ak1.drawbox.domain.model.resolvedTextColor
import io.ak1.drawbox.domain.model.textBox
import io.ak1.drawbox.domain.model.textTopLeft

/**
 * A shape's text, wrapped to [textBox] and centred in it. Laid out through the same
 * [TextLayoutCache] as text elements, under the shape's id plus [SHAPE_TEXT_KEY].
 * An arrow or a line has no interior, so its label sits on the midpoint of its endpoints.
 */
internal fun DrawScope.drawShapeText(
    shape: Element.Shape,
    textCache: TextLayoutCache?,
    textMeasurer: TextMeasurer?,
) {
    if (shape.text.isEmpty()) return
    if (shape.shapeType == ShapeType.ARROW || shape.shapeType == ShapeType.LINE) {
        drawConnectorLabel(shape, textCache, textMeasurer)
        return
    }
    if (!shape.canHoldText) return
    if (textCache == null || textMeasurer == null) return
    val box = shape.textBox()
    val layout = textCache.layoutFor(
        id = shape.id + SHAPE_TEXT_KEY,
        text = shape.text,
        fontFamilyKey = shape.fontFamilyKey,
        fontSize = shape.fontSize,
        alignment = shape.textAlignment,
        wrapWidth = box.width.coerceAtLeast(1f),
        measurer = textMeasurer,
    )
    drawText(
        textLayoutResult = layout,
        color = shape.resolvedTextColor,
        topLeft = shape.textTopLeft(layout.size.height.toFloat()),
    )
}

/**
 * A connector has no interior ([canHoldText] is false), so its label is drawn on the
 * midpoint of the two endpoints, on a small white chip, instead of inside a box.
 */
private fun DrawScope.drawConnectorLabel(
    shape: Element.Shape,
    textCache: TextLayoutCache?,
    textMeasurer: TextMeasurer?,
) {
    if (textCache == null || textMeasurer == null) return
    if (shape.points.size < 2) return
    val layout = textCache.layoutFor(
        id = shape.id + SHAPE_TEXT_KEY,
        text = shape.text,
        fontFamilyKey = shape.fontFamilyKey,
        fontSize = shape.fontSize,
        alignment = shape.textAlignment,
        wrapWidth = connectorLabelWidth(shape),
        measurer = textMeasurer,
    )
    val start = shape.points.first()
    val end = shape.points.last()
    val mid = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
    val topLeft = Offset(mid.x - layout.size.width / 2f, mid.y - layout.size.height / 2f)
    val pad = 3f
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(topLeft.x - pad, topLeft.y - pad),
        size = Size(layout.size.width.toFloat() + pad * 2, layout.size.height.toFloat() + pad * 2),
        cornerRadius = CornerRadius(3f, 3f),
    )
    drawText(textLayoutResult = layout, color = shape.resolvedTextColor, topLeft = topLeft)
}

/** Wide enough for the glyphs, tight enough that the chip stays off the cards it joins. */
private fun connectorLabelWidth(shape: Element.Shape): Float =
    (shape.text.length * shape.fontSize * 0.72f + 4f).coerceAtLeast(shape.fontSize)
