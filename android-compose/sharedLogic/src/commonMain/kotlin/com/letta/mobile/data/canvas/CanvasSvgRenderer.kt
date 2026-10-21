package com.letta.mobile.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val SVG_PADDING = 20f
private const val LINE_HEIGHT = 1.25f

/** A text element's SVG anchor for its board alignment; anything else starts at the left. */
private val TEXT_ANCHORS = mapOf<String?, String>("CENTER" to "middle", "RIGHT" to "end")

/** How far across the wrap width each anchor sits. */
private val ANCHOR_SHARE = mapOf("start" to 0f, "middle" to 0.5f, "end" to 1f)

private val FONT_FAMILIES = mapOf<String?, String>("serif" to "serif", "mono" to "monospace")

/** One drawn element: its SVG markup and its world-space extent, rotation included. */
private typealias SvgDrawing = Pair<String, CanvasPreviewRect>

/**
 * Portable drawing export of a stored scene. Unsupported board content (Cascade notes, plugin
 * elements, background patterns, curved connectors, unresolved image assets) is rejected, never
 * silently omitted.
 */
internal fun renderCanvasSvg(sceneJson: String): String {
    val original = Json.parseToJsonElement(sceneJson).jsonObject
    require(original["elements"] is JsonArray) { "Scene has no elements array" }
    for (key in listOf("_documents", "_pluginElements")) {
        val content = original[key]
        require(content == null || content.toString() in listOf("[]", "{}", "null")) { "SVG export does not support $key yet" }
    }
    // Checked before metadata stripping, which drops the pattern along with the sync fields.
    require(original["_bgPattern"] == null) { "SVG export does not support background patterns" }
    val checked = CanvasSceneValidator.scene(CanvasOpProjector.stripMetadataForDrawBox(sceneJson))
    require(checked is CanvasSceneCheck.Valid) { (checked as CanvasSceneCheck.Invalid).message }
    val root = Json.parseToJsonElement(checked.json).jsonObject
    val drawings = (root["elements"] as JsonArray).map { it.jsonObject }
        .sortedBy { it.number("zIndex") ?: 0f }
        .map(::svgElement)
    val extent = drawings.map { it.second }.let { rects ->
        if (rects.isEmpty()) CanvasPreviewRect(0f, 0f, 1000f, 1000f) else rects.reduce(::union)
    }.padded(SVG_PADDING)
    val x = extent.left
    val y = extent.top
    val w = extent.right - extent.left
    val h = extent.bottom - extent.top
    return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="$x $y $w $h">
<rect x="$x" y="$y" width="$w" height="$h" fill="${root.need("bgColor").xml()}"/>
${drawings.joinToString("\n") { it.first }}
</svg>"""
}

private fun svgElement(e: JsonObject): SvgDrawing {
    val drawing = when (val type = e.need("type")) {
        "Shape" -> svgShape(e)
        "Text" -> svgText(e)
        "Path" -> svgPath(e)
        "Image" -> svgImage(e)
        else -> error("Unsupported SVG element: $type")
    }
    val rotation = e.number("rotation") ?: 0f
    if (rotation == 0f) return drawing
    val b = drawing.second
    val cx = (b.left + b.right) / 2
    val cy = (b.top + b.bottom) / 2
    val radians = rotation * PI.toFloat() / 180
    val corners = listOf(b.left to b.top, b.right to b.top, b.left to b.bottom, b.right to b.bottom)
        .map { (x, y) ->
            val dx = x - cx
            val dy = y - cy
            ConnectPoint(cx + dx * cos(radians) - dy * sin(radians), cy + dx * sin(radians) + dy * cos(radians))
        }
    return """<g transform="rotate($rotation $cx $cy)">${drawing.first}</g>""" to extentOf(corners)
}

private fun svgShape(e: JsonObject): SvgDrawing {
    val points = (e["points"] as JsonArray).map { it.jsonPrimitive.content.toConnectPoint() }
    val first = points.first()
    val last = points.last()
    val width = e.number("strokeWidth") ?: 2f
    val style = e.shapeStyle()
    val (body, extent) = when (val kind = e.need("shapeType")) {
        "RECTANGLE" -> """<rect x="${min(first.x, last.x)}" y="${min(first.y, last.y)}" """ +
            """width="${abs(last.x - first.x)}" height="${abs(last.y - first.y)}" rx="${e.number("cornerRadius") ?: 0f}" $style/>""" to
            extentOf(points).padded(width / 2)
        "CIRCLE" -> {
            val radius = sqrt((last.x - first.x) * (last.x - first.x) + (last.y - first.y) * (last.y - first.y)) / 2
            val cx = (first.x + last.x) / 2
            val cy = (first.y + last.y) / 2
            """<circle cx="$cx" cy="$cy" r="$radius" $style/>""" to
                CanvasPreviewRect(cx - radius, cy - radius, cx + radius, cy + radius).padded(width / 2)
        }
        "TRIANGLE" -> {
            val vertices = "${(first.x + last.x) / 2},${min(first.y, last.y)} " +
                "${min(first.x, last.x)},${max(first.y, last.y)} ${max(first.x, last.x)},${max(first.y, last.y)}"
            """<polygon points="$vertices" $style/>""" to extentOf(points).padded(width / 2)
        }
        "LINE", "ARROW" -> svgConnector(e, points)
        else -> error("Unsupported SVG shape: $kind")
    }
    if (e.text("text").isNullOrEmpty()) return body to extent
    val center = ConnectPoint((extent.left + extent.right) / 2, (extent.top + extent.bottom) / 2)
    return body + svgTextBlock(e, center) to extent
}

private fun svgConnector(e: JsonObject, points: List<ConnectPoint>): SvgDrawing {
    require(e["bend"] == null && e["startHandle"] == null && e["endHandle"] == null) {
        "SVG export does not support curved connectors yet"
    }
    val first = points.first()
    val last = points.last()
    val width = e.number("strokeWidth") ?: 2f
    val line = """<line x1="${first.x}" y1="${first.y}" x2="${last.x}" y2="${last.y}" ${e.shapeStyle()}/>"""
    if (e.need("shapeType") != "ARROW") return line to extentOf(points).padded(width / 2)
    val angle = atan2(last.y - first.y, last.x - first.x)
    val size = max(width * 2, 10f)
    val wing = PI.toFloat() / 6
    val a = ConnectPoint(last.x - size * cos(angle - wing), last.y - size * sin(angle - wing))
    val c = ConnectPoint(last.x - size * cos(angle + wing), last.y - size * sin(angle + wing))
    val head = """<polygon points="${last.x},${last.y} ${a.x},${a.y} ${c.x},${c.y}" fill="${e.strokeOrNone().xml()}"/>"""
    return line + head to extentOf(points + listOf(a, c)).padded(width / 2)
}

private fun svgText(e: JsonObject): SvgDrawing {
    val start = e.need("textTopLeft").toConnectPoint()
    val size = e.number("fontSize") ?: 24f
    val width = e.number("wrapWidth") ?: 320f
    val height = e.need("text").split('\n').size * size * LINE_HEIGHT
    val svg = svgTextBlock(e, ConnectPoint(start.x, start.y + size))
    return svg to CanvasPreviewRect(start.x, start.y, start.x + width, start.y + height)
}

/**
 * [e]'s text. A shape label is centred on [at] in the shape's text colour; a text element starts
 * its first baseline at [at] and follows its own alignment across its wrap width, in its stroke colour.
 */
private fun svgTextBlock(e: JsonObject, at: ConnectPoint): String {
    val size = e.number("fontSize") ?: 24f
    val label = e.need("type") == "Shape"
    val color = (if (label) e.text("textColor") else null) ?: e.need("strokeColor")
    val anchor = if (label) "middle" else TEXT_ANCHORS.getOrElse(e.text("alignment")) { "start" }
    val x = if (label) at.x else at.x + (e.number("wrapWidth") ?: 320f) * ANCHOR_SHARE.getValue(anchor)
    val lines = e.need("text").split('\n').mapIndexed { index, line ->
        // Every line after the first steps down one line height.
        """<tspan x="$x" dy="${size * LINE_HEIGHT * index.coerceAtMost(1)}">${line.xml()}</tspan>"""
    }.joinToString("")
    val family = FONT_FAMILIES.getOrElse(e.text("fontFamilyKey")) { "sans-serif" }
    return """<text x="$x" y="${at.y}" font-size="$size" font-family="$family" """ +
        """text-anchor="$anchor" fill="${color.xml()}" opacity="${e.number("opacity") ?: 1f}">$lines</text>"""
}

private fun svgPath(e: JsonObject): SvgDrawing {
    val samples = (e["samples"] as JsonArray).map { it.jsonPrimitive.content.split(',').map(String::toFloat) }
    require(samples.isNotEmpty()) { "Cannot export an empty path" }
    // Composite the stroke once: overlapping round caps must not darken translucent joints.
    val color = e.need("strokeColor")
    val opaque = color.take(7).xml()
    val colorAlpha = color.takeIf { it.length == 9 }?.takeLast(2)?.toInt(16)?.div(255f) ?: 1f
    val content = if (samples.size == 1) {
        val (x, y, w) = samples.single()
        """<circle cx="$x" cy="$y" r="${w / 2}" fill="$opaque"/>"""
    } else {
        samples.zipWithNext().joinToString("") { (a, b) ->
            val width = (a[2] + b[2]) / 2
            """<line x1="${a[0]}" y1="${a[1]}" x2="${b[0]}" y2="${b[1]}" stroke="$opaque" """ +
                """stroke-width="$width" stroke-linecap="round"${e.dash(width)}/>"""
        }
    }
    val group = """<g opacity="${(e.number("alpha") ?: 1f) * colorAlpha}">$content</g>"""
    return group to extentOf(samples.map { ConnectPoint(it[0], it[1]) }).padded(samples.maxOf { it[2] } / 2)
}

private fun svgImage(e: JsonObject): SvgDrawing {
    val data = e.text("imageData").orEmpty()
    require(data.isNotEmpty()) { "SVG export requires embedded imageData; imageRef assets are not resolved" }
    require(data.all { it.isLetterOrDigit() || it in "+/=" }) { "Invalid imageData" }
    val mime = e.text("imageMediaType") ?: "image/png"
    require(mime in listOf("image/png", "image/jpeg", "image/gif", "image/webp")) { "Unsupported image media type" }
    val b = extentOf((e["points"] as JsonArray).map { it.jsonPrimitive.content.toConnectPoint() })
    val svg = """<image x="${b.left}" y="${b.top}" width="${b.right - b.left}" height="${b.bottom - b.top}" """ +
        """href="data:$mime;base64,$data" opacity="${e.number("opacity") ?: 1f}"/>"""
    return svg to b
}

/** Stroke, width, fill and dash pattern of a shape's outline. */
private fun JsonObject.shapeStyle(): String {
    val width = number("strokeWidth") ?: 2f
    val fill = (text("fillColor") ?: "none").xml()
    return """stroke="${strokeOrNone().xml()}" stroke-width="$width" fill="$fill"${dash(width)}"""
}

private fun JsonObject.dash(width: Float): String = when (text("strokeStyle")) {
    "DASHED" -> """ stroke-dasharray="${width * 4},${width * 2}""""
    "DOTTED" -> """ stroke-dasharray="${width * 0.5f},${width * 2}""""
    else -> ""
}

private fun JsonObject.strokeOrNone(): String =
    if (this["strokeEnabled"]?.jsonPrimitive?.booleanOrNull == false) "none" else need("strokeColor")

private fun extentOf(points: List<ConnectPoint>): CanvasPreviewRect = CanvasPreviewRect(
    points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y },
)

private fun CanvasPreviewRect.padded(pad: Float) = CanvasPreviewRect(left - pad, top - pad, right + pad, bottom + pad)

private fun union(a: CanvasPreviewRect, b: CanvasPreviewRect) =
    CanvasPreviewRect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))

private fun String.toConnectPoint(): ConnectPoint {
    val parts = split(',').map(String::toFloat)
    require(parts.size == 2 && parts.all { it.isFinite() }) { "Invalid point" }
    return ConnectPoint(parts[0], parts[1])
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.content != "null" }?.content

private fun JsonObject.need(key: String): String = text(key) ?: error("Missing $key")

private fun JsonObject.number(key: String): Float? =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.toFloat()?.also { require(it.isFinite()) }

private fun String.xml(): String = replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
