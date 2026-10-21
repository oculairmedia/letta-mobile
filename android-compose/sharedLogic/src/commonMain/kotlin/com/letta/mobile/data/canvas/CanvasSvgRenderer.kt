package com.letta.mobile.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Portable drawing export. Unsupported board content is rejected, never silently omitted. */
internal object CanvasSvgRenderer {
    private const val PADDING = 20.0

    fun render(sceneJson: String): String {
        val original = Json.parseToJsonElement(sceneJson).jsonObject
        require(original["elements"] is JsonArray) { "Scene has no elements array" }
        for (key in listOf("_documents", "_pluginElements")) {
            val content = original[key]
            require(content == null || content.toString() in listOf("[]", "{}", "null")) {
                "SVG export does not support $key yet"
            }
        }
        val clean = CanvasOpProjector.stripMetadataForDrawBox(sceneJson)
        val checked = CanvasSceneValidator.scene(clean)
        require(checked is CanvasSceneCheck.Valid) {
            (checked as CanvasSceneCheck.Invalid).message
        }
        val root = Json.parseToJsonElement(checked.json).jsonObject
        val elements = (root["elements"] as JsonArray).map { it.jsonObject }.sortedBy { it.number("zIndex", 0.0) }
        val rendered = elements.map(::element)
        val bounds = rendered.map { it.bounds }
        val left = (bounds.minOfOrNull { it.left } ?: 0.0) - PADDING
        val top = (bounds.minOfOrNull { it.top } ?: 0.0) - PADDING
        val right = (bounds.maxOfOrNull { it.right } ?: 1000.0) + PADDING
        val bottom = (bounds.maxOfOrNull { it.bottom } ?: 1000.0) + PADDING
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="$left $top ${right - left} ${bottom - top}">
<rect x="$left" y="$top" width="${right - left}" height="${bottom - top}" fill="${xml(root.string("bgColor"))}"/>
${rendered.joinToString("\n") { it.svg }}
</svg>"""
    }

    private data class Point(val x: Double, val y: Double)
    private data class Bounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val x get() = (left + right) / 2
        val y get() = (top + bottom) / 2
    }
    private data class Drawing(val svg: String, val bounds: Bounds)

    private fun element(e: JsonObject): Drawing {
        val points = (e["points"] as JsonArray).map { point(it.jsonPrimitive.content) }
        val type = e.string("type")
        val drawing = when (type) {
            "Shape" -> shape(e, points)
            "Text" -> text(e)
            "Path" -> path(e)
            "Image" -> image(e, points)
            else -> error("Unsupported SVG element: $type")
        }
        val rotation = e.number("rotation", 0.0)
        if (rotation == 0.0) return drawing
        val b = drawing.bounds
        val radians = rotation * kotlin.math.PI / 180
        val corners = listOf(Point(b.left, b.top), Point(b.right, b.top), Point(b.left, b.bottom), Point(b.right, b.bottom))
            .map {
                val dx = it.x - b.x
                val dy = it.y - b.y
                Point(b.x + dx * cos(radians) - dy * sin(radians), b.y + dx * sin(radians) + dy * cos(radians))
            }
        return Drawing("""<g transform="rotate($rotation ${b.x} ${b.y})">${drawing.svg}</g>""", bounds(corners))
    }

    private fun shape(e: JsonObject, points: List<Point>): Drawing {
        val first = points.first()
        val last = points.last()
        val width = e.number("strokeWidth", 2.0)
        val stroke = if (e["strokeEnabled"]?.jsonPrimitive?.booleanOrNull == false) "none" else e.string("strokeColor")
        val style = """stroke="${xml(stroke)}" stroke-width="$width" fill="${xml(e.string("fillColor", "none"))}"${dash(e, width)}"""
        var b = bounds(points, width / 2)
        val kind = e.string("shapeType")
        val body = when (kind) {
            "RECTANGLE" -> """<rect x="${min(first.x, last.x)}" y="${min(first.y, last.y)}" """ +
                """width="${abs(last.x - first.x)}" height="${abs(last.y - first.y)}" rx="${e.number("cornerRadius", 0.0)}" $style/>"""
            "CIRCLE" -> {
                val radius = sqrt((last.x - first.x) * (last.x - first.x) + (last.y - first.y) * (last.y - first.y)) / 2
                val cx = (first.x + last.x) / 2
                val cy = (first.y + last.y) / 2
                b = Bounds(cx - radius - width / 2, cy - radius - width / 2, cx + radius + width / 2, cy + radius + width / 2)
                """<circle cx="$cx" cy="$cy" r="$radius" $style/>"""
            }
            "TRIANGLE" -> {
                val vertices = "${(first.x + last.x) / 2},${min(first.y, last.y)} " +
                    "${min(first.x, last.x)},${maxOf(first.y, last.y)} ${maxOf(first.x, last.x)},${maxOf(first.y, last.y)}"
                """<polygon points="$vertices" $style/>"""
            }
            "LINE", "ARROW" -> {
                require(e["bend"] == null && e["startHandle"] == null && e["endHandle"] == null) {
                    "SVG export does not support curved connectors yet"
                }
                val line = """<line x1="${first.x}" y1="${first.y}" x2="${last.x}" y2="${last.y}" $style/>"""
                if (kind == "ARROW") {
                    val angle = kotlin.math.atan2(last.y - first.y, last.x - first.x)
                    val size = maxOf(width * 2, 10.0)
                    val a = Point(last.x - size * cos(angle - kotlin.math.PI / 6), last.y - size * sin(angle - kotlin.math.PI / 6))
                    val c = Point(last.x - size * cos(angle + kotlin.math.PI / 6), last.y - size * sin(angle + kotlin.math.PI / 6))
                    b = bounds(points + listOf(a, c), width / 2)
                    line + """<polygon points="${last.x},${last.y} ${a.x},${a.y} ${c.x},${c.y}" fill="${xml(stroke)}"/>"""
                } else line
            }
            else -> error("Unsupported SVG shape: $kind")
        }
        val label = e.string("text", "")
        val labelSvg = if (label.isEmpty()) "" else {
            textBlock(e, Point(b.x, b.y), label, "middle", e.string("textColor", e.string("strokeColor")))
        }
        return Drawing(body + labelSvg, b)
    }

    private fun text(e: JsonObject): Drawing {
        val start = point(e.string("textTopLeft"))
        val size = e.number("fontSize", 24.0)
        val width = e.number("wrapWidth", 320.0)
        val words = e.string("text")
        val anchor = when (e.string("alignment", "LEFT")) { "CENTER" -> "middle"; "RIGHT" -> "end"; else -> "start" }
        val x = start.x + when (anchor) { "middle" -> width / 2; "end" -> width; else -> 0.0 }
        val height = words.split('\n').size * size * 1.25
        return Drawing(textBlock(e, Point(x, start.y + size), words, anchor, e.string("strokeColor")),
            Bounds(start.x, start.y, start.x + width, start.y + height))
    }

    private fun textBlock(e: JsonObject, at: Point, words: String, anchor: String, color: String): String {
        val size = e.number("fontSize", 24.0)
        val lines = words.split('\n').mapIndexed { index, line ->
            """<tspan x="${at.x}" dy="${if (index == 0) 0.0 else size * 1.25}">${xml(line)}</tspan>"""
        }.joinToString("")
        val family = when (e.string("fontFamilyKey", "sans")) { "serif" -> "serif"; "mono" -> "monospace"; else -> "sans-serif" }
        return """<text x="${at.x}" y="${at.y}" font-size="$size" font-family="$family" """ +
            """text-anchor="$anchor" fill="${xml(color)}" opacity="${e.number("opacity", 1.0)}">$lines</text>"""
    }

    private fun path(e: JsonObject): Drawing {
        val samples = (e["samples"] as JsonArray).map { it.jsonPrimitive.content.split(',').map(String::toDouble) }
        require(samples.isNotEmpty()) { "Cannot export an empty path" }
        val positions = samples.map { Point(it[0], it[1]) }
        val svg = samples.zipWithNext().joinToString("") { (a, b) ->
            val width = (a[2] + b[2]) / 2
            """<line x1="${a[0]}" y1="${a[1]}" x2="${b[0]}" y2="${b[1]}" stroke="${xml(e.string("strokeColor"))}" """ +
                """stroke-width="$width" stroke-linecap="round" opacity="${e.number("alpha", 1.0)}"${dash(e, width)}/>"""
        }
        val content = if (samples.size == 1) {
            val sample = samples.single()
            """<circle cx="${sample[0]}" cy="${sample[1]}" r="${sample[2] / 2}" """ +
                """fill="${xml(e.string("strokeColor"))}" opacity="${e.number("alpha", 1.0)}"/>"""
        } else svg
        return Drawing(content, bounds(positions, samples.maxOf { it[2] } / 2))
    }

    private fun image(e: JsonObject, points: List<Point>): Drawing {
        val data = e.string("imageData", "")
        require(data.isNotEmpty()) { "SVG export requires embedded imageData; imageRef assets are not resolved" }
        require(data.all { it.isLetterOrDigit() || it in "+/=" }) { "Invalid imageData" }
        val mime = e.string("imageMediaType", "image/png")
        require(mime in listOf("image/png", "image/jpeg", "image/gif", "image/webp")) { "Unsupported image media type" }
        val b = bounds(points)
        val svg = """<image x="${b.left}" y="${b.top}" width="${b.right - b.left}" height="${b.bottom - b.top}" """ +
            """href="data:$mime;base64,$data" opacity="${e.number("opacity", 1.0)}"/>"""
        return Drawing(svg, b)
    }

    private fun dash(e: JsonObject, width: Double): String = when (e.string("strokeStyle", "SOLID")) {
        "DASHED" -> """ stroke-dasharray="${width * 4},${width * 2}""""
        "DOTTED" -> """ stroke-dasharray="${width * 0.5},${width * 2}""""
        else -> ""
    }

    private fun bounds(points: List<Point>, pad: Double = 0.0): Bounds = Bounds(
        points.minOf { it.x } - pad, points.minOf { it.y } - pad,
        points.maxOf { it.x } + pad, points.maxOf { it.y } + pad,
    )

    private fun point(value: String): Point {
        val parts = value.split(',').map(String::toDouble)
        require(parts.size == 2 && parts.all { it.isFinite() }) { "Invalid point" }
        return Point(parts[0], parts[1])
    }

}

private fun JsonObject.string(key: String, default: String? = null): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.content != "null" }?.content ?: default ?: error("Missing $key")

private fun JsonObject.number(key: String, default: Double): Double =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.also { require(it.isFinite()) } ?: default

private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
