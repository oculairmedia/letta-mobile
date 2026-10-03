package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.compose.ElementBounds
import com.letta.mobile.data.canvas.compose.Slot
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The notes and drawn elements a connect can name, read once from the board a batch has left so far.
 * An endpoint is a framed note or a boxed shape; the arrow joins the midpoints of the sides that
 * face each other ([ConnectSides]). A shape is measured by its unrotated box.
 */
internal class ConnectBoard private constructor(
    private val documents: List<CanvasSceneDocument>,
    private val elements: List<JsonObject>,
) {
    fun resolve(at: ConnectOpLabel, input: ConnectInput): ConnectResolve {
        if (input.from == input.to) return refuse(at, FROM, "connect needs two different endpoints")
        val from = when (val read = endpoint(input.from)) {
            is EndpointRead.Found -> read.endpoint
            is EndpointRead.No -> return refuse(at, FROM, read.detail)
        }
        val to = when (val read = endpoint(input.to)) {
            is EndpointRead.Found -> read.endpoint
            is EndpointRead.No -> return refuse(at, TO, read.detail)
        }
        if (ConnectSides.overlap(from.slot, to.slot)) {
            return refuse(at, FROM, "connect endpoints overlap or touch ('${input.from.raw}' and '${input.to.raw}'); leave a gap between them")
        }
        if (taken(input.id)) return refuse(at, ID, "'${input.id.raw}' is already on the board")
        val facing = ConnectSides.of(from.slot, to.slot)
        return ConnectResolve.Yes(ConnectArrow(input, from.at(facing.from), to.at(facing.to)))
    }

    private fun endpoint(id: ConnectId): EndpointRead {
        documents.firstOrNull { it.id == id.raw }?.let { return note(id, it) }
        val element = elements.firstOrNull { it.text(ID_KEY) == id.raw }
            ?: return EndpointRead.No("'${id.raw}' is not a note or a shape on the board")
        return shape(id, element)
    }

    private fun note(id: ConnectId, document: CanvasSceneDocument): EndpointRead {
        val frame = document.frame
            ?: return EndpointRead.No("note '${id.raw}' has no frame yet; give it one with set_document {frame}")
        return EndpointRead.Found(ConnectEndpoint.Note(id, Slot(frame.x, frame.y, frame.width, frame.height)))
    }

    private fun shape(id: ConnectId, element: JsonObject): EndpointRead {
        val type = element.text(TYPE_KEY)
        if (type != SHAPE) return EndpointRead.No("'${id.raw}' is a ${type ?: "drawn"} element, not a note or a shape")
        val shapeType = element.text(SHAPE_TYPE_KEY)
        if (shapeType in LINES) {
            return EndpointRead.No("'${id.raw}' is a ${shapeType.orEmpty().lowercase()}; connect joins notes and boxed shapes, not lines or arrows")
        }
        return EndpointRead.Found(ConnectEndpoint.Shape(id, ElementBounds(element, conservative = false).bounds()))
    }

    private fun taken(id: ConnectId): Boolean =
        documents.any { it.id == id.raw } || elements.any { it.text(ID_KEY) == id.raw }

    private fun refuse(at: ConnectOpLabel, field: ConnectField, detail: String) =
        ConnectResolve.No(CanvasConnect.refusal(at, field, ConnectDetail(detail)))

    companion object {
        private val ID = ConnectField("id")
        private val FROM = ConnectField("from")
        private val TO = ConnectField("to")
        private const val ID_KEY = "id"
        private const val TYPE_KEY = "type"
        private const val SHAPE_TYPE_KEY = "shapeType"
        private const val SHAPE = "Shape"
        private val LINES = setOf("LINE", "ARROW")
        private val json = Json { ignoreUnknownKeys = true }

        fun of(sceneJson: String): ConnectBoard = ConnectBoard(CanvasOpProjector.documentsOf(sceneJson), elementsOf(sceneJson))

        private fun elementsOf(sceneJson: String): List<JsonObject> {
            if (sceneJson.isBlank()) return emptyList()
            val root = runCatching { json.parseToJsonElement(sceneJson) }.getOrNull() as? JsonObject ?: return emptyList()
            return (root["elements"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}

private sealed interface EndpointRead {
    data class Found(val endpoint: ConnectEndpoint) : EndpointRead
    data class No(val detail: String) : EndpointRead
}

internal sealed interface ConnectResolve {
    data class Yes(val arrow: ConnectArrow) : ConnectResolve
    data class No(val message: String) : ConnectResolve
}

/** A connect resolved against the board: the arrow's id, ends, label, style and points. */
internal class ConnectArrow(input: ConnectInput, val from: ConnectEndpoint, val to: ConnectEndpoint) {
    val id: ConnectId = input.id
    val label: String? = input.label
    val style: ConnectStyle = input.style
    val start: ConnectPoint = from.anchor()
    val end: ConnectPoint = to.anchor()
}

internal data class ConnectPoint(val x: Float, val y: Float) {
    /** `x,y` as DrawBox writes a point, a whole number with its `.0`. */
    fun text(): String = "${coord(x)},${coord(y)}"

    private fun coord(value: Float): String =
        if (value.toInt().toFloat() == value) "${value.toInt()}.0" else value.toString()
}

internal sealed interface ConnectEndpoint {
    val slot: Slot
    val side: String

    fun documentBinding(): CanvasEndBinding?
    fun elementBinding(): ConnectId?
    fun at(side: String): ConnectEndpoint

    fun anchor(): ConnectPoint {
        val (x, y) = CanvasSnap.anchorOn(CanvasDocumentFrame(slot.x, slot.y, slot.width, slot.height), side)
        return ConnectPoint(x, y)
    }

    data class Note(val id: ConnectId, override val slot: Slot, override val side: String = "") : ConnectEndpoint {
        override fun documentBinding(): CanvasEndBinding = CanvasEndBinding(id.raw, side)
        override fun elementBinding(): ConnectId? = null
        override fun at(side: String): ConnectEndpoint = copy(side = side)
    }

    data class Shape(val id: ConnectId, override val slot: Slot, override val side: String = "") : ConnectEndpoint {
        override fun documentBinding(): CanvasEndBinding? = null
        override fun elementBinding(): ConnectId = id
        override fun at(side: String): ConnectEndpoint = copy(side = side)
    }
}

/**
 * Which sides face: centre to centre, mostly horizontal (`|dx| >= |dy|`) joins right to left (or
 * left to right), otherwise bottom to top (or top to bottom). Frames that overlap or touch have no
 * gap for an arrow.
 */
internal object ConnectSides {
    data class Facing(val from: String, val to: String)

    fun overlap(a: Slot, b: Slot): Boolean =
        a.x <= b.x + b.width && b.x <= a.x + a.width && a.y <= b.y + b.height && b.y <= a.y + a.height

    fun of(from: Slot, to: Slot): Facing {
        val dx = centreX(to) - centreX(from)
        val dy = centreY(to) - centreY(from)
        return if (abs(dx) >= abs(dy)) horizontal(dx) else vertical(dy)
    }

    private fun horizontal(dx: Float): Facing =
        if (dx >= 0f) Facing(CanvasSnapAnchor.RIGHT, CanvasSnapAnchor.LEFT) else Facing(CanvasSnapAnchor.LEFT, CanvasSnapAnchor.RIGHT)

    private fun vertical(dy: Float): Facing =
        if (dy >= 0f) Facing(CanvasSnapAnchor.BOTTOM, CanvasSnapAnchor.TOP) else Facing(CanvasSnapAnchor.TOP, CanvasSnapAnchor.BOTTOM)

    private fun centreX(slot: Slot): Float = slot.x + slot.width / 2f

    private fun centreY(slot: Slot): Float = slot.y + slot.height / 2f
}
