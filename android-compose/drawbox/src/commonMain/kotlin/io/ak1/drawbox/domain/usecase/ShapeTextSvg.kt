package io.ak1.drawbox.domain.usecase

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.BuiltinFontFamilyKeys
import io.ak1.drawbox.domain.model.CONNECTOR_CHIP_PAD
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.connectorLabelCentre
import io.ak1.drawbox.domain.model.connectorLabelMaxWidth
import io.ak1.drawbox.domain.model.connectorLabelOnChip
import io.ak1.drawbox.domain.model.resolvedTextColor
import io.ak1.drawbox.domain.model.textBox

/**
 * A shape's text in the SVG export, where the canvas draws it. Unturned; [SvgExporter] turns it
 * with the shape. Sized by the per-character estimate the export wraps all text with.
 */

/** A closed shape's text: a block in its text box, centred top to bottom. */
internal fun boxedShapeTextToSvg(shape: Element.Shape): String {
    val box = shape.textBox()
    val lines = SvgExporter.wrapTextForSvg(shape.text, box.width, shape.fontSize, shape.fontFamilyKey).size
    val height = svgTextHeight(shape, lines)
    return SvgExporter.textToSvg(shapeTextBlock(shape, Offset(box.left, box.center.y - height / 2f), box.width, height))
}

/**
 * A connector's label, placed by the canvas's rule (`ConnectorLabel.kt`): on the curve's midpoint
 * over a chip of the board's [bgColor] when the shaft has room, beside the shaft otherwise. With
 * no board colour there is no chip to paint, so only the text is written.
 */
internal fun connectorLabelToSvg(shape: Element.Shape, bgColor: Color?): String {
    val maxWidth = shape.connectorLabelMaxWidth()
    val lines = SvgExporter.wrapTextForSvg(shape.text, maxWidth, shape.fontSize, shape.fontFamilyKey)
    val charWidth = shape.fontSize * charWidthMultiplier(shape.fontFamilyKey)
    // A hair over the longest line, so the export's own wrap does not break it again.
    val width = (lines.maxOf { it.length } * charWidth).coerceAtMost(maxWidth) + LABEL_WIDTH_SLACK
    val height = svgTextHeight(shape, lines.size)
    val centre = shape.connectorLabelCentre(width, height)
    val topLeft = Offset(centre.x - width / 2f, centre.y - height / 2f)
    val text = SvgExporter.textToSvg(shapeTextBlock(shape, topLeft, width, height))
    if (bgColor == null || !shape.connectorLabelOnChip(width, height)) return text
    val pad = CONNECTOR_CHIP_PAD
    val fill = SvgExporter.colorToHex(bgColor)
    val chip = """<rect x="${topLeft.x - pad}" y="${topLeft.y - pad}" width="${width + pad * 2}" """ +
        """height="${height + pad * 2}" rx="$pad" ry="$pad" fill="$fill"/>"""
    return "$chip\n  $text"
}

/** Average glyph width as a share of the em, by family: mono glyphs are wider. */
internal fun charWidthMultiplier(fontFamilyKey: String): Float = when (fontFamilyKey) {
    BuiltinFontFamilyKeys.MONO -> MONO_CHAR_EMS
    else -> PROPORTIONAL_CHAR_EMS
}

private fun svgTextHeight(shape: Element.Shape, lines: Int): Float =
    shape.fontSize + (lines - 1) * shape.fontSize * LINE_HEIGHT_EMS

private fun shapeTextBlock(shape: Element.Shape, topLeft: Offset, width: Float, height: Float) = Element.Text(
    id = shape.id,
    text = shape.text,
    fontFamilyKey = shape.fontFamilyKey,
    fontSize = shape.fontSize,
    color = shape.resolvedTextColor,
    alignment = shape.textAlignment,
    topLeft = topLeft,
    wrapWidth = width,
    measuredHeight = height,
)

private const val LABEL_WIDTH_SLACK = 0.01f
private const val LINE_HEIGHT_EMS = 1.25f
private const val MONO_CHAR_EMS = 0.6f
private const val PROPORTIONAL_CHAR_EMS = 0.55f
