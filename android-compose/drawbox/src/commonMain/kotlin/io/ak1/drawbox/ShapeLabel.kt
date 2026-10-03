package io.ak1.drawbox

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import io.ak1.drawbox.domain.model.CONNECTOR_CHIP_PAD
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.canHoldText
import io.ak1.drawbox.domain.model.connectorLabelCentre
import io.ak1.drawbox.domain.model.connectorLabelMaxWidth
import io.ak1.drawbox.domain.model.connectorLabelOnChip
import io.ak1.drawbox.domain.model.isConnector
import io.ak1.drawbox.domain.model.resolvedTextColor
import io.ak1.drawbox.domain.model.textBox
import io.ak1.drawbox.domain.model.textTopLeft

/**
 * A shape's text. Laid out through the same [TextLayoutCache] as text elements, under the shape's
 * id plus [SHAPE_TEXT_KEY]. A closed shape's text is wrapped to its [textBox] and centred in it;
 * an arrow's or a line's is a label on its curve (see [drawConnectorLabel]).
 */
internal fun DrawScope.drawShapeText(
    shape: Element.Shape,
    textCache: TextLayoutCache?,
    textMeasurer: TextMeasurer?,
    chip: Color,
) {
    if (shape.text.isEmpty()) return
    if (textCache == null || textMeasurer == null) return
    if (shape.isConnector) {
        drawConnectorLabel(shape, textCache, textMeasurer, chip)
        return
    }
    if (!shape.canHoldText) return
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
 * A connector's label, placed by the rule in `ConnectorLabel.kt`: on the curve's midpoint over a
 * chip of [chip] (the board's own colour, so the chip only knocks the shaft and any pattern out
 * behind the text) when the shaft has room for it, otherwise beside the shaft with no chip. The
 * text is laid out as wide as it is, up to [connectorLabelMaxWidth], then wraps; its colour falls
 * back to the stroke's. All in world units, so it scales with the board.
 */
private fun DrawScope.drawConnectorLabel(
    shape: Element.Shape,
    textCache: TextLayoutCache,
    textMeasurer: TextMeasurer,
    chip: Color,
) {
    if (shape.points.size < 2) return
    val layout = textCache.layoutFor(
        id = shape.id + SHAPE_TEXT_KEY,
        text = shape.text,
        fontFamilyKey = shape.fontFamilyKey,
        fontSize = shape.fontSize,
        alignment = shape.textAlignment,
        wrapWidth = shape.connectorLabelMaxWidth(),
        measurer = textMeasurer,
        fitToText = true,
    )
    val width = layout.size.width.toFloat()
    val height = layout.size.height.toFloat()
    val centre = shape.connectorLabelCentre(width, height)
    val topLeft = Offset(centre.x - width / 2f, centre.y - height / 2f)
    if (shape.connectorLabelOnChip(width, height)) {
        drawRoundRect(
            color = chip,
            topLeft = Offset(topLeft.x - CONNECTOR_CHIP_PAD, topLeft.y - CONNECTOR_CHIP_PAD),
            size = Size(width + CONNECTOR_CHIP_PAD * 2, height + CONNECTOR_CHIP_PAD * 2),
            cornerRadius = CornerRadius(CONNECTOR_CHIP_PAD, CONNECTOR_CHIP_PAD),
        )
    }
    drawText(textLayoutResult = layout, color = shape.resolvedTextColor, topLeft = topLeft)
}

/**
 * How shape text is painted in one draw pass: whether it is hidden (an editor is showing it), and
 * the board colour a connector label's chip is painted with. Built once per board colour.
 */
internal data class ShapeTextPaint(val hidden: Boolean, val chip: Color)
