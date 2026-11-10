package com.letta.mobile.data.canvas.compose

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

/*
 * The data kinds drawn from the board's own elements rather than written as a note (STAT,
 * PROGRESS, CHART): a frame (a rounded rectangle) with Text, Shape and Path elements inside it,
 * laid out in world units from the content alone. Every client that draws a board already draws
 * these elements, so nothing new is needed to show them; only the compiling host knows the kind.
 *
 * The geometry is fixed when compiled (no auto-fit): every text is placed at the height
 * [WorstCaseWrap] books for it, an overestimate by construction, so texts never overlap however a
 * target measures them; a long label leaves space rather than running into the next line.
 * Coordinates are whole numbers, so the frames are exact on every target.
 *
 * Written as functions over the items, not as types: the package is at its type budget.
 */

/**
 * One board element of a drawn item: the part it is under the item (null for the item's frame,
 * which carries the item's own board id) and its DrawBox element without id or provenance; the
 * emitter adds both.
 */
internal typealias DrawnPart = Pair<String?, JsonObject>

/** A drawn item's inner padding, and the space between stacked texts. */
internal const val DRAWING_PADDING = 16f
internal const val DRAWING_GAP = 4f

/**
 * Stacking inside a drawn item, each its own level: DrawBox orders elements of one zIndex by id,
 * so an element meant to cover another (a progress fill over its track) must sit higher.
 */
internal const val DRAWING_FRAME_Z = 1
internal const val DRAWING_BACK_Z = 2
internal const val DRAWING_MARK_Z = 3
internal const val DRAWING_TEXT_Z = 4

internal const val ALIGN_CENTER = "CENTER"
internal const val ALIGN_RIGHT = "RIGHT"

private const val FRAME_RADIUS = 12.0
private const val EDGE_WIDTH = 1.0
private const val MARK_RADIUS = 5f
private const val AXIS_WIDTH = 1.0
private const val LINE_WIDTH = 3.0
private const val DOT_RADIUS = 4f
private const val HUNDREDTHS = 100L
private const val TENS = 10L
private const val DIGIT_GROUP = 3

/** The size [item] (a STAT, PROGRESS or CHART) is placed at: its kind's width and the height its content needs. */
internal fun drawnSize(item: ComposeItem): Pair<Float, Float> = when (item) {
    is ComposeItem.Stat -> CanvasComposeContract.STAT_WIDTH to statHeight(item)
    is ComposeItem.Progress -> CanvasComposeContract.PROGRESS_WIDTH to progressHeight(item)
    is ComposeItem.Chart -> CanvasComposeContract.CHART_WIDTH to chartHeight(item)
    else -> error("${item.kind} is not drawn")
}

/** The elements of [item] placed in [slot]: the frame first, then marks, then texts. */
internal fun drawnParts(item: ComposeItem, slot: Slot): List<DrawnPart> = when (item) {
    is ComposeItem.Stat -> statParts(item, slot)
    is ComposeItem.Progress -> progressParts(item, slot)
    is ComposeItem.Chart -> chartParts(item, slot)
    else -> error("${item.kind} is not drawn")
}

/** The frame every drawn item sits in: white, a light edge, rounded, under everything else in it. */
internal fun drawnFrame(slot: Slot): DrawnPart {
    val body = rectangle(slot, CanvasComposeColors.DRAWING_FILL, FRAME_RADIUS, DRAWING_FRAME_Z)
    return null to JsonObject(body + ("strokeColor" to JsonPrimitive(CanvasComposeColors.DRAWING_STROKE)))
}

/** A filled bar edged in its own colour, its corners rounded to at most half its short side. */
internal fun drawnBar(part: String, box: Slot, color: String, z: Int): DrawnPart {
    val radius = minOf(MARK_RADIUS, box.height / 2, box.width / 2).toDouble()
    return part to rectangle(box, color, radius, z)
}

private fun rectangle(box: Slot, color: String, radius: Double, z: Int): JsonObject = buildJsonObject {
    put("type", "Shape")
    put("shapeType", "RECTANGLE")
    put("points", buildJsonArray { add(JsonPrimitive(box.topLeftPoint())); add(JsonPrimitive(box.bottomRightPoint())) })
    put("strokeColor", color)
    put("strokeWidth", EDGE_WIDTH)
    put("fillColor", color)
    put("cornerRadius", radius)
    put("zIndex", z)
}

/** A text element set at [box]'s top-left, wrapping at its width, in [look], aligned as [alignment] says. */
internal fun drawnText(part: String, box: Slot, look: TextLook, alignment: String = CanvasComposeCompiler.TEXT_ALIGNMENT): DrawnPart =
    part to buildJsonObject {
        put("type", "Text")
        put("text", look.text)
        put("textTopLeft", box.topLeftPoint())
        put("wrapWidth", box.width.toDouble())
        put("fontSize", look.font)
        put("alignment", alignment)
        put("fontFamilyKey", CanvasComposeCompiler.TEXT_FONT_FAMILY)
        put("strokeColor", look.color)
        put("zIndex", look.z)
    }

/** A drawn item's text in [color] at [font], above its marks. */
internal fun drawnLook(text: String, font: Double, color: String): TextLook = TextLook(text, font, color, DRAWING_TEXT_Z)

/** A chart's baseline, a thin grey line from [from] to [to]. */
internal fun drawnAxis(part: String, from: Pair<Float, Float>, to: Pair<Float, Float>): DrawnPart = part to buildJsonObject {
    put("type", "Shape")
    put("shapeType", "LINE")
    put("points", buildJsonArray { add(JsonPrimitive(drawBoxPoint(from))); add(JsonPrimitive(drawBoxPoint(to))) })
    put("strokeColor", CanvasComposeColors.AXIS)
    put("strokeWidth", AXIS_WIDTH)
    put("zIndex", DRAWING_MARK_Z)
}

/** A line chart's series: a polyline through [points] in the accent colour. */
internal fun drawnPath(part: String, points: List<Pair<Float, Float>>): DrawnPart = part to buildJsonObject {
    put("type", "Path")
    val pressure = drawBoxNumber(LINE_WIDTH.toFloat())
    put("samples", buildJsonArray { points.forEach { add(JsonPrimitive(drawBoxPoint(it) + "," + pressure)) } })
    put("strokeColor", CanvasComposeColors.ACCENT)
    put("strokeWidth", LINE_WIDTH)
    put("zIndex", DRAWING_MARK_Z)
}

/** A line chart's point: a small accent dot centred on [centre]. */
internal fun drawnDot(part: String, centre: Pair<Float, Float>): DrawnPart = part to buildJsonObject {
    put("type", "Shape")
    put("shapeType", "CIRCLE")
    val (x, y) = centre
    val ends = listOf(x - DOT_RADIUS to y, x + DOT_RADIUS to y)
    put("points", buildJsonArray { ends.forEach { add(JsonPrimitive(drawBoxPoint(it))) } })
    put("strokeColor", CanvasComposeColors.ACCENT)
    put("strokeWidth", EDGE_WIDTH)
    put("fillColor", CanvasComposeColors.ACCENT)
    put("zIndex", DRAWING_MARK_Z)
}

/** The radius of a line chart's dots, which value labels clear. */
internal fun drawnDotRadius(): Float = DOT_RADIUS

/** The height [text] takes at [font] wrapped at [width]: its worst-case lines at the reserve's line height. */
internal fun textHeight(text: String, font: Double, width: Float): Float =
    ceil(WorstCaseWrap(width.toDouble(), font).lines(text) * CanvasComposeReserve.LINE_HEIGHT_EM * font).toFloat()

/** Whether [text] fits on one line at [font] in [width], booked at worst case. */
internal fun fitsOneLine(text: String, font: Double, width: Float): Boolean = WorstCaseWrap(width.toDouble(), font).lines(text) == 1

/** The height of [looks] stacked in a column [width] wide, [DRAWING_GAP] apart, each as tall as it books. */
internal fun stackHeight(looks: List<TextLook>, width: Float): Float =
    looks.sumOf { textHeight(it.text, it.font, width).toDouble() }.toFloat() + (looks.size - 1).coerceAtLeast(0) * DRAWING_GAP

/** [texts] (part and look) stacked as [stackHeight] measures them, the column's top-left at [topLeft]. */
internal fun stackParts(texts: List<Pair<String, TextLook>>, width: Float, topLeft: Pair<Float, Float>): List<DrawnPart> {
    var top = topLeft.second
    return texts.map { (part, look) ->
        val height = textHeight(look.text, look.font, width)
        drawnText(part, Slot(topLeft.first, top, width, height), look).also { top += height + DRAWING_GAP }
    }
}

/**
 * A number as a chart prints it, the same on every target (Kotlin/JS would print `3` where the
 * JVM prints `3.0`): at most two decimals, trailing zeros dropped, thousands grouped with commas,
 * then [unit].
 */
internal fun formatChartValue(value: Double, unit: String? = null): String {
    val scaled = round(value * HUNDREDTHS).toLong()
    val magnitude = abs(scaled)
    val whole = (magnitude / HUNDREDTHS).toString().reversed().chunked(DIGIT_GROUP).joinToString(",").reversed()
    val fraction = magnitude % HUNDREDTHS
    val decimals = when {
        fraction == 0L -> ""
        fraction % TENS == 0L -> "." + (fraction / TENS)
        else -> "." + fraction.toString().padStart(2, '0')
    }
    val sign = if (scaled < 0) "-" else ""
    return sign + whole + decimals + unit.orEmpty()
}

/** DrawBox's `"x,y"` of a point, written as [topLeftPoint] writes one. */
internal fun drawBoxPoint(point: Pair<Float, Float>): String = "${drawBoxNumber(point.first)},${drawBoxNumber(point.second)}"
