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
        .sortedBy { it.number("zIndex", 0f) }
        .map(::svgElement)
    val extent = drawings.map { it.second }.let { rects ->
        if (rects.isEmpty()) CanvasPreviewRect(0f, 0f, 1000f, 1000f) else rects.reduce(::union)
    }.padded(SVG_PADDING)
    val (x, y, w, h) = listOf(extent.left, extent.top, extent.right - extent.left, extent.bottom - extent.top)
    return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="$x $y $w $h">
<rect x="$x" y="$y" width="$w" height="$h" fill="${xml(root.string("bgColor"))}"/>
${drawings.joinToString("\n") { it.first }}
</svg>"""
}

private fun svgElement(e: JsonObject): SvgDrawing {
    val drawing = when (val type = e.string("type")) {
        "Shape" -> svgShape(e)
        "Text" -> svgText(e)
        "Path" -> svgPath(e)
        "Image" -> svgImage(e)
        else -> error("Unsupported SVG element: $type")
    }
    val rotation = e.number("rotation", 0f)
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
    val points = e.points()
    val first = points.first()
    val last = points.last()
    val width = e.number("strokeWidth", 2f)
    val stroke = e.strokeOrNone()
    val style = """stroke="${xml(stroke)}" stroke-width="$width" fill="${xml(e.string("fillColor", "none"))}"${dash(e, width)}"""
    val (body, extent) = when (val kind = e.string("shapeType")) {
        "RECTANGLE" -> """<rect x="${min(first.x, last.x)}" y="${min(first.y, last.y)}" """ +
            """width="${abs(last.x - first.x)}" height="${abs(last.y - first.y)}" rx="${e.number("cornerRadius", 0f)}" $style/>""" to
            extentOf(points).padded(width / 2)
        "CIRCLE" -> svgCircle(first, last, style).let { (svg, radius) ->
            val cx = (first.x + last.x) / 2
            val cy = (first.y + last.y) / 2
            svg to CanvasPreviewRect(cx - radius, cy - radius, cx + radius, cy + radius).padded(width / 2)
        }
        "TRIANGLE" -> {
            val vertices = "${(first.x + last.x) / 2},${min(first.y, last.y)} " +
                "${min(first.x, last.x)},${max(first.y, last.y)} ${max(first.x, last.x)},${max(first.y, last.y)}"
            """<polygon points="$vertices" $style/>""" to extentOf(points).padded(width / 2)
        }
        "LINE", "ARROW" -> svgConnector(e, kind == "ARROW", style)
        else -> error("Unsupported SVG shape: $kind")
    }
    val label = e.string("text", "")
    if (label.isEmpty()) return body to extent
    val center = ConnectPoint((extent.left + extent.right) / 2, (extent.top + extent.bottom) / 2)
    return body + svgTextBlock(e, center, label, e.string("textColor", e.string("strokeColor"))) to extent
}

private fun svgCircle(first: ConnectPoint, last: ConnectPoint, style: String): Pair<String, Float> {
    val radius = sqrt((last.x - first.x) * (last.x - first.x) + (last.y - first.y) * (last.y - first.y)) / 2
    return """<circle cx="${(first.x + last.x) / 2}" cy="${(first.y + last.y) / 2}" r="$radius" $style/>""" to radius
}

private fun svgConnector(e: JsonObject, arrow: Boolean, style: String): SvgDrawing {
    require(e["bend"] == null && e["startHandle"] == null && e["endHandle"] == null) {
        "SVG export does not support curved connectors yet"
    }
    val points = e.points()
    val first = points.first()
    val last = points.last()
    val width = e.number("strokeWidth", 2f)
    val line = """<line x1="${first.x}" y1="${first.y}" x2="${last.x}" y2="${last.y}" $style/>"""
    if (!arrow) return line to extentOf(points).padded(width / 2)
    val angle = atan2(last.y - first.y, last.x - first.x)
    val size = max(width * 2, 10f)
    val wing = PI.toFloat() / 6
    val a = ConnectPoint(last.x - size * cos(angle - wing), last.y - size * sin(angle - wing))
    val c = ConnectPoint(last.x - size * cos(angle + wing), last.y - size * sin(angle + wing))
    val head = """<polygon points="${last.x},${last.y} ${a.x},${a.y} ${c.x},${c.y}" fill="${xml(e.strokeOrNone())}"/>"""
    return line + head to extentOf(points + listOf(a, c)).padded(width / 2)
}

private fun svgText(e: JsonObject): SvgDrawing {
    val start = point(e.string("textTopLeft"))
    val size = e.number("fontSize", 24f)
    val width = e.number("wrapWidth", 320f)
    val words = e.string("text")
    val x = start.x + when (textAnchor(e)) { "middle" -> width / 2; "end" -> width; else -> 0f }
    val height = words.split('\n').size * size * LINE_HEIGHT
    val svg = svgTextBlock(e, ConnectPoint(x, start.y + size), words, e.string("strokeColor"))
    return svg to CanvasPreviewRect(start.x, start.y, start.x + width, start.y + height)
}

/** A shape's label is centred on the shape; a text element follows its own alignment. */
private fun textAnchor(e: JsonObject): String =
    if (e.string("type") == "Shape") "middle" else when (e.string("alignment", "LEFT")) {
        "CENTER" -> "middle"
        "RIGHT" -> "end"
        else -> "start"
    }

private fun svgTextBlock(e: JsonObject, at: ConnectPoint, words: String, color: String): String {
    val size = e.number("fontSize", 24f)
    val lines = words.split('\n').mapIndexed { index, line ->
        """<tspan x="${at.x}" dy="${if (index == 0) 0f else size * LINE_HEIGHT}">${xml(line)}</tspan>"""
    }.joinToString("")
    val family = when (e.string("fontFamilyKey", "sans")) { "serif" -> "serif"; "mono" -> "monospace"; else -> "sans-serif" }
    return """<text x="${at.x}" y="${at.y}" font-size="$size" font-family="$family" """ +
        """text-anchor="${textAnchor(e)}" fill="${xml(color)}" opacity="${e.number("opacity", 1f)}">$lines</text>"""
}

private fun svgPath(e: JsonObject): SvgDrawing {
    val samples = (e["samples"] as JsonArray).map { it.jsonPrimitive.content.split(',').map(String::toFloat) }
    require(samples.isNotEmpty()) { "Cannot export an empty path" }
    // Composite the stroke once: overlapping round caps must not darken translucent joints.
    val color = e.string("strokeColor")
    val opaque = xml(color.take(7))
    val colorAlpha = color.takeIf { it.length == 9 }?.takeLast(2)?.toInt(16)?.div(255f) ?: 1f
    val content = if (samples.size == 1) {
        val (x, y, w) = samples.single()
        """<circle cx="$x" cy="$y" r="${w / 2}" fill="$opaque"/>"""
    } else {
        samples.zipWithNext().joinToString("") { (a, b) ->
            val width = (a[2] + b[2]) / 2
            """<line x1="${a[0]}" y1="${a[1]}" x2="${b[0]}" y2="${b[1]}" stroke="$opaque" """ +
                """stroke-width="$width" stroke-linecap="round"${dash(e, width)}/>"""
        }
    }
    val group = """<g opacity="${e.number("alpha", 1f) * colorAlpha}">$content</g>"""
    return group to extentOf(samples.map { ConnectPoint(it[0], it[1]) }).padded(samples.maxOf { it[2] } / 2)
}

private fun svgImage(e: JsonObject): SvgDrawing {
    val data = e.string("imageData", "")
    require(data.isNotEmpty()) { "SVG export requires embedded imageData; imageRef assets are not resolved" }
    require(data.all { it.isLetterOrDigit() || it in "+/=" }) { "Invalid imageData" }
    val mime = e.string("imageMediaType", "image/png")
    require(mime in listOf("image/png", "image/jpeg", "image/gif", "image/webp")) { "Unsupported image media type" }
    val b = extentOf(e.points())
    val svg = """<image x="${b.left}" y="${b.top}" width="${b.right - b.left}" height="${b.bottom - b.top}" """ +
        """href="data:$mime;base64,$data" opacity="${e.number("opacity", 1f)}"/>"""
    return svg to b
}

private fun dash(e: JsonObject, width: Float): String = when (e.string("strokeStyle", "SOLID")) {
    "DASHED" -> """ stroke-dasharray="${width * 4},${width * 2}""""
    "DOTTED" -> """ stroke-dasharray="${width * 0.5f},${width * 2}""""
    else -> ""
}

private fun JsonObject.strokeOrNone(): String =
    if (this["strokeEnabled"]?.jsonPrimitive?.booleanOrNull == false) "none" else string("strokeColor")

private fun JsonObject.points(): List<ConnectPoint> = (this["points"] as JsonArray).map { point(it.jsonPrimitive.content) }

private fun extentOf(points: List<ConnectPoint>): CanvasPreviewRect = CanvasPreviewRect(
    points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y },
)

private fun CanvasPreviewRect.padded(pad: Float) = CanvasPreviewRect(left - pad, top - pad, right + pad, bottom + pad)

private fun union(a: CanvasPreviewRect, b: CanvasPreviewRect) =
    CanvasPreviewRect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))

private fun point(value: String): ConnectPoint {
    val parts = value.split(',').map(String::toFloat)
    require(parts.size == 2 && parts.all { it.isFinite() }) { "Invalid point" }
    return ConnectPoint(parts[0], parts[1])
}

private fun JsonObject.string(key: String, default: String? = null): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.content != "null" }?.content ?: default ?: error("Missing $key")

private fun JsonObject.number(key: String, default: Float): Float =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.toFloat()?.also { require(it.isFinite()) } ?: default

private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
