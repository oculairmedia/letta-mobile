package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.compose.ElementBounds
import com.letta.mobile.data.canvas.compose.Slot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs

/** Ops read from a tool call: ready to check, or refused before anything is published. */
internal sealed interface CanvasOpsRead {
    data class Ready(val ops: List<CanvasOp>) : CanvasOpsRead
    data class Refused(val message: String) : CanvasOpsRead
}

/**
 * `connect` is an agent input only (letta-mobile-i9yps.1). It becomes an arrow plus the existing
 * binding op before the batch is checked, logged or relayed, so the op log never sees `connect`.
 */
internal object CanvasConnect {
    const val EXAMPLE =
        """{"type":"connect","id":"arrow-1","from":"note-a","to":"note-b","label":"next","style":{"strokeColor":"#1f2937ff","strokeWidth":2,"dashed":false}}"""

    /** Em size of a connector label, small enough to sit in the gap between the notes it joins. */
    private const val LABEL_SIZE = 13.0

    fun read(sceneJson: String, opsJson: JsonElement): CanvasOpsRead {
        val array = opsJson as? JsonArray ?: throw IllegalArgumentException("ops must be an array of op objects")
        val scratch = Scratch(sceneJson)
        val ops = mutableListOf<CanvasOp>()
        array.forEachIndexed { index, element ->
            when (val step = one(scratch.scene, index, element)) {
                is Step.Keep -> {
                    ops += step.op
                    scratch.apply(step.op)
                }
                is Step.Expanded -> {
                    ops += step.ops
                    step.ops.forEach(scratch::apply)
                }
                is Step.Refused -> return CanvasOpsRead.Refused(step.message)
            }
        }
        return CanvasOpsRead.Ready(ops)
    }

    private fun one(scene: String, index: Int, element: JsonElement): Step {
        val obj = element as? JsonObject ?: return Step.Keep(decode(element))
        if (obj.text("type") != "connect") return Step.Keep(decode(element))
        return expand(scene, index, obj)
    }

    private fun expand(scene: String, index: Int, op: JsonObject): Step {
        val parsed = parse(index, op)
        if (parsed is Parse.Refused) return Step.Refused(parsed.message)
        val input = (parsed as Parse.Ok).input
        if (input.from == input.to) return Step.Refused(refuse(index, "from", "connect needs two different endpoints"))
        val from = endpoint(scene, input.from) ?: return Step.Refused(missing(index, "from", input.from))
        val to = endpoint(scene, input.to) ?: return Step.Refused(missing(index, "to", input.to))
        if (overlaps(from.slot, to.slot)) return Step.Refused(refuse(index, "from", "connect endpoints overlap ('${input.from}' and '${input.to}')"))
        if (taken(scene, input.id)) return Step.Refused(refuse(index, "id", "'${input.id}' is already on the board"))
        return Step.Expanded(listOf(arrow(input, from, to), binding(input, from, to)))
    }

    private fun arrow(input: Input, from: Endpoint, to: Endpoint): CanvasOp {
        val sides = sides(from.slot, to.slot)
        val start = CanvasSnap.anchorOn(from.slot.frame(), sides.from)
        val end = CanvasSnap.anchorOn(to.slot.frame(), sides.to)
        val json = buildJsonObject {
            put("id", input.id)
            put("type", "Shape")
            put("shapeType", "ARROW")
            put("points", JsonArray(listOf(JsonPrimitive(point(start)), JsonPrimitive(point(end)))))
            input.label?.let {
                put("text", it)
                put("fontSize", LABEL_SIZE)
            }
            from.elementBinding(sides.from)?.let { put("startBinding", it) }
            to.elementBinding(sides.to)?.let { put("endBinding", it) }
            input.style.write(this)
        }
        return CanvasOp.AddElementOp("", "", 0L, input.id, json.toString())
    }

    private fun binding(input: Input, from: Endpoint, to: Endpoint): CanvasOp {
        val sides = sides(from.slot, to.slot)
        return CanvasOp.SetArrowBindingOp(
            opId = "",
            actorId = "",
            lamport = 0L,
            elementId = input.id,
            binding = CanvasArrowBinding(start = from.documentBinding(sides.from), end = to.documentBinding(sides.to)),
        )
    }

    private fun parse(index: Int, op: JsonObject): Parse {
        val id = op.text("id") ?: return Parse.Refused(refuse(index, "id", "expected a string, the new arrow's id"))
        val from = op.text("from") ?: return Parse.Refused(refuse(index, "from", "expected a string, a note id or a shape id"))
        val to = op.text("to") ?: return Parse.Refused(refuse(index, "to", "expected a string, a note id or a shape id"))
        val label = op["label"]?.let { value ->
            value.text() ?: return Parse.Refused(refuse(index, "label", "expected a string"))
        }
        val style = when (val read = ConnectStyle.read(index, op["style"])) {
            is StyleRead.Ok -> read.style
            is StyleRead.Refused -> return Parse.Refused(read.message)
        }
        return Parse.Ok(Input(id, from, to, label, style))
    }

    private fun missing(index: Int, field: String, id: String) =
        refuse(index, field, "'$id' is not a note or a shape on the board")

    private fun refuse(index: Int, field: String, detail: String) =
        "op $index field $field: $detail. Expected $EXAMPLE. Nothing in this batch was published."

    private fun endpoint(scene: String, id: String): Endpoint? {
        val document = CanvasOpProjector.documentsOf(scene).firstOrNull { it.id == id }
        document?.frame?.let { return Endpoint.Note(id, Slot(it.x, it.y, it.width, it.height)) }
        if (document != null) return null
        val element = elements(scene).firstOrNull { it.text("id") == id } ?: return null
        if (element.text("type") != "Shape") return null
        return Endpoint.Shape(id, ElementBounds(element, conservative = false).bounds())
    }

    private fun taken(scene: String, id: String): Boolean =
        CanvasOpProjector.documentsOf(scene).any { it.id == id } || elements(scene).any { it.text("id") == id }

    private fun elements(scene: String): List<JsonObject> {
        val root = runCatching { json.parseToJsonElement(scene) }.getOrNull() as? JsonObject ?: return emptyList()
        return (root["elements"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }

    private fun overlaps(a: Slot, b: Slot): Boolean =
        a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height

    private fun sides(from: Slot, to: Slot): Sides {
        val dx = to.centerX() - from.centerX()
        val dy = to.centerY() - from.centerY()
        return if (abs(dx) >= abs(dy)) {
            if (dx >= 0f) Sides(CanvasSnapAnchor.RIGHT, CanvasSnapAnchor.LEFT) else Sides(CanvasSnapAnchor.LEFT, CanvasSnapAnchor.RIGHT)
        } else {
            if (dy >= 0f) Sides(CanvasSnapAnchor.BOTTOM, CanvasSnapAnchor.TOP) else Sides(CanvasSnapAnchor.TOP, CanvasSnapAnchor.BOTTOM)
        }
    }

    private fun point(at: Pair<Float, Float>): String = "${coord(at.first)},${coord(at.second)}"

    private fun coord(value: Float): String = if (value.toInt().toFloat() == value) "${value.toInt()}.0" else value.toString()

    private fun decode(element: JsonElement): CanvasOp = HostCanvasToolInputs.ops(JsonArray(listOf(element))).single()

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.text(key: String): String? = this[key]?.text()

    private fun Slot.centerX(): Float = x + width / 2f

    private fun Slot.centerY(): Float = y + height / 2f

    private fun Slot.frame(): CanvasDocumentFrame = CanvasDocumentFrame(x, y, width, height)

    private class Scratch(var scene: String) {
        private var lamport = CanvasOpProjector.maxLamport(scene)

        fun apply(op: CanvasOp) {
            lamport += 1
            scene = CanvasOpProjector.project(scene, listOf(op.withStamp("connect-scratch", lamport)))
        }
    }

    private data class Input(val id: String, val from: String, val to: String, val label: String?, val style: ConnectStyle)

    private data class Sides(val from: String, val to: String)

    private sealed interface Endpoint {
        val slot: Slot

        fun documentBinding(side: String): CanvasEndBinding?
        fun elementBinding(side: String): String?

        data class Note(val id: String, override val slot: Slot) : Endpoint {
            override fun documentBinding(side: String): CanvasEndBinding = CanvasEndBinding(id, side)
            override fun elementBinding(side: String): String? = null
        }

        data class Shape(val id: String, override val slot: Slot) : Endpoint {
            override fun documentBinding(side: String): CanvasEndBinding? = null
            override fun elementBinding(side: String): String? = id
        }
    }

    private sealed interface Step {
        data class Keep(val op: CanvasOp) : Step
        data class Expanded(val ops: List<CanvasOp>) : Step
        data class Refused(val message: String) : Step
    }

    private sealed interface Parse {
        data class Ok(val input: Input) : Parse
        data class Refused(val message: String) : Parse
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
}

/** `style` on a connect: only the arrow fields that already exist. */
private data class ConnectStyle(val strokeColor: String?, val strokeWidth: Float?, val dashed: Boolean?) {
    fun write(json: kotlinx.serialization.json.JsonObjectBuilder) {
        strokeColor?.let { json.put("strokeColor", it) }
        strokeWidth?.let { json.put("strokeWidth", it) }
        if (dashed == true) json.put("strokeStyle", "DASHED")
    }

    companion object {
        private val KEYS = setOf("strokeColor", "strokeWidth", "dashed")

        fun read(index: Int, value: JsonElement?): StyleRead {
            if (value == null) return StyleRead.Ok(ConnectStyle(null, null, null))
            val obj = value as? JsonObject ?: return StyleRead.Refused(styleRefuse(index, "style", "expected an object of strokeColor, strokeWidth and dashed"))
            val unknown = obj.keys.firstOrNull { it !in KEYS }
            if (unknown != null) return StyleRead.Refused(styleRefuse(index, "style.$unknown", "unknown style key '$unknown'"))
            val color = obj["strokeColor"]?.let { color ->
                val text = (color as? JsonPrimitive)?.contentOrNull
                if (text == null || !COLOR.matches(text)) {
                    return StyleRead.Refused(styleRefuse(index, "style.strokeColor", "expected a colour #rrggbbaa"))
                }
                text
            }
            val width = obj["strokeWidth"]?.let { width ->
                val number = (width as? JsonPrimitive)?.floatOrNull
                if (number == null || number <= 0f) return StyleRead.Refused(styleRefuse(index, "style.strokeWidth", "expected a number greater than 0"))
                number
            }
            val dashed = obj["dashed"]?.let { flag ->
                val primitive = flag as? JsonPrimitive
                if (primitive == null || primitive.isString) return StyleRead.Refused(styleRefuse(index, "style.dashed", "expected true or false"))
                primitive.content == "true"
            }
            return StyleRead.Ok(ConnectStyle(color, width, dashed))
        }

        private fun styleRefuse(index: Int, field: String, detail: String) =
            "op $index field $field: $detail. Expected ${CanvasConnect.EXAMPLE}. Nothing in this batch was published."

        private val COLOR = Regex("""#[0-9a-fA-F]{8}""")
    }
}

private sealed interface StyleRead {
    data class Ok(val style: ConnectStyle) : StyleRead
    data class Refused(val message: String) : StyleRead
}
