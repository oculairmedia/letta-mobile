package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasOpProjector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The bounds of a scene, read from its JSON: [CanvasComposePlacement.contentBounds] and [CanvasComposePlacement.occupiedBounds]. */
internal class SceneBounds(private val sceneJson: String) {
    fun content(): ComposeBounds? = boundsOf(conservative = false)

    fun occupied(): ComposeBounds? {
        val framed = boundsOf(conservative = true)
        val documents = CanvasOpProjector.documentsOf(sceneJson)
        val frameless = CanvasComposePlacement.placeFrameless(documents, content())
        return CanvasComposePlacement.union(framed, CanvasComposePlacement.union(frameless.values))
    }

    private fun boundsOf(conservative: Boolean): ComposeBounds? {
        if (sceneJson.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(sceneJson) }.getOrNull() as? JsonObject ?: return null
        val rects = mutableListOf<Slot>()
        (root["elements"] as? JsonArray)?.forEach { element ->
            (element as? JsonObject)?.let { rects += ElementBounds(it, conservative).bounds() }
        }
        CanvasOpProjector.documentsOf(sceneJson).forEach { document ->
            document.frame?.let { rects += Slot(it.x, it.y, it.width, it.height) }
        }
        return CanvasComposePlacement.union(rects)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * DrawBox `Element.bounds()` of one serialized element; when [conservative], grown to what it can
 * really cover (a rotated element's turned corners, a text element's wrapped lines rather than
 * DrawBox's one-line guess), as [CanvasComposePlacement.occupiedBounds] needs.
 */
internal class ElementBounds(private val element: JsonObject, private val conservative: Boolean) {
    fun bounds(): Slot {
        val box = when (element.string("type")) {
            "Text" -> textBounds()
            "Image" -> pointsBounds(element.offsets("points"))
            "Shape" -> shapeBounds()
            else -> pathBounds()
        }
        val rotation = element.float("rotation") ?: 0f
        return if (!conservative || rotation == 0f) box else box.rotatedBy(rotation)
    }

    /** A Path, and DrawBox reads any other type as one: samples, else legacy points. */
    private fun pathBounds(): Slot {
        val samples = (element["samples"] as? JsonArray)?.map(::sample)
        return pointsBounds(samples ?: element.offsets("points"))
    }

    private fun textBounds(): Slot {
        val fontSize = element.float("fontSize") ?: 24f
        val points = element.offsets("points")
        val topLeft = element.string("textTopLeft")?.let(::offset) ?: points.firstOrNull() ?: (0f to 0f)
        val wrapWidth = (element.float("wrapWidth") ?: pointsWidth(points)).coerceAtLeast(1f)
        // DrawBox's single-line guess until the renderer measures; the conservative box books the wrapped lines.
        val guess = Slot(topLeft.first, topLeft.second, wrapWidth, (fontSize * 1.2f).coerceAtLeast(fontSize))
        return if (conservative) withWrappedLines(guess, fontSize) else guess
    }

    private fun pointsWidth(points: List<Pair<Float, Float>>): Float =
        if (points.size >= 2) points.last().first - points.first().first else 240f

    /** [box] at least as tall as the element's text wraps to in [box]'s width. */
    private fun withWrappedLines(box: Slot, fontSize: Float): Slot {
        val text = element.string("text").orEmpty()
        val lines = WorstCaseWrap(box.width.toDouble(), fontSize.toDouble()).lines(text)
        return box.copy(height = maxOf(box.height, ceil(lines * CanvasComposeReserve.LINE_HEIGHT_EM * fontSize).toFloat()))
    }

    private fun shapeBounds(): Slot {
        val points = element.offsets("points")
        if (points.size < 2) return pointsBounds(points)
        return when (element.string("shapeType")) {
            "CIRCLE" -> circleBounds(points.first(), points.last())
            "LINE", "ARROW" -> lineBounds(points.first(), points.last())
            else -> pointsBounds(listOf(points.first(), points.last()))
        }
    }

    private fun circleBounds(start: Pair<Float, Float>, end: Pair<Float, Float>): Slot {
        val cx = (start.first + end.first) * 0.5f
        val cy = (start.second + end.second) * 0.5f
        val dx = end.first - start.first
        val dy = end.second - start.second
        val radius = sqrt(dx * dx + dy * dy) * 0.5f
        return spanning(cx - radius to cy - radius, cx + radius to cy + radius)
    }

    private fun lineBounds(start: Pair<Float, Float>, end: Pair<Float, Float>): Slot {
        val bend = element.string("bend")?.let(::offset) ?: (0f to 0f)
        if (bend.first == 0f && bend.second == 0f) return pointsBounds(listOf(start, end))
        val mid = (start.first + end.first) * 0.5f to (start.second + end.second) * 0.5f
        val control = mid.first + bend.first to mid.second + bend.second
        return pointsBounds(listOf(start, end, control))
    }
}

/** A rectangle from its corners, so right - left is computed the way DrawBox's `Rect` holds it. */
private fun spanning(topLeft: Pair<Float, Float>, bottomRight: Pair<Float, Float>) =
    Slot(topLeft.first, topLeft.second, bottomRight.first - topLeft.first, bottomRight.second - topLeft.second)

private fun pointsBounds(points: List<Pair<Float, Float>>): Slot {
    // DrawBox gives an element with no points the empty rectangle at the origin, and zoom-to-fit counts it.
    if (points.isEmpty()) return Slot(0f, 0f, 0f, 0f)
    return spanning(points.minOf { it.first } to points.minOf { it.second }, points.maxOf { it.first } to points.maxOf { it.second })
}

/** The axis-aligned box of this one turned by [degrees] about its centre. */
private fun Slot.rotatedBy(degrees: Float): Slot {
    val radians = degrees * PI / 180.0
    val c = abs(cos(radians))
    val s = abs(sin(radians))
    val w = width * c + height * s
    val h = width * s + height * c
    val cx = x + width / 2.0
    val cy = y + height / 2.0
    return Slot((cx - w / 2).toFloat(), (cy - h / 2).toFloat(), w.toFloat(), h.toFloat())
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull

private fun JsonObject.offsets(key: String): List<Pair<Float, Float>> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::offset) }.orEmpty()

/** DrawBox's `"x,y"`: anything that is not exactly two numbers reads as the origin. */
private fun offset(text: String): Pair<Float, Float> {
    val parts = text.split(",")
    if (parts.size != 2) return 0f to 0f
    return (parts[0].toFloatOrNull() ?: 0f) to (parts[1].toFloatOrNull() ?: 0f)
}

/** A path sample `"x,y[,w[,tilt[,azimuth]]]"`: its position. */
private fun sample(element: JsonElement): Pair<Float, Float> {
    val parts = (element as? JsonPrimitive)?.contentOrNull?.split(",") ?: return 0f to 0f
    if (parts.size !in 2..5) return 0f to 0f
    return (parts[0].toFloatOrNull() ?: 0f) to (parts[1].toFloatOrNull() ?: 0f)
}
