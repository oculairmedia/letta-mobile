package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.compose.ElementBounds
import com.letta.mobile.data.canvas.compose.Slot
import kotlin.jvm.JvmInline
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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

    fun read(sceneJson: String, opsJson: JsonElement): CanvasOpsRead = readBoard(SceneText(sceneJson), opsJson)

    private fun readBoard(scene: SceneText, opsJson: JsonElement): CanvasOpsRead {
        val array = opsJson as? JsonArray ?: throw IllegalArgumentException("ops must be an array of op objects")
        val scratch = Scratch(scene)
        val ops = mutableListOf<CanvasOp>()
        array.forEachIndexed { index, element ->
            when (val step = one(scratch.scene, OpIndex(index), element)) {
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

    private fun one(scene: SceneText, index: OpIndex, element: JsonElement): Step {
        val obj = element as? JsonObject ?: return Step.Keep(decode(element))
        if (obj.text(FieldName("type")) != "connect") return Step.Keep(decode(element))
        return expand(scene, index, obj)
    }

    private fun expand(scene: SceneText, index: OpIndex, op: JsonObject): Step {
        val parsed = parse(index, op)
        if (parsed is Parse.Refused) return Step.Refused(parsed.message)
        return when (val resolved = resolve(scene, index, (parsed as Parse.Ok).input)) {
            is Resolve.No -> Step.Refused(resolved.message)
            is Resolve.Yes -> Step.Expanded(listOf(arrow(resolved.input), binding(resolved.input)))
        }
    }

    private fun resolve(scene: SceneText, index: OpIndex, input: Input): Resolve {
        if (input.from == input.to) return Resolve.No(refuse(index, FieldName("from"), Detail("connect needs two different endpoints")))
        val from = endpoint(scene, input.from) ?: return Resolve.No(missing(index, FieldName("from"), input.from))
        val to = endpoint(scene, input.to) ?: return Resolve.No(missing(index, FieldName("to"), input.to))
        if (overlaps(from.slot, to.slot)) {
            return Resolve.No(refuse(index, FieldName("from"), Detail("connect endpoints overlap ('${input.from.raw}' and '${input.to.raw}')")))
        }
        if (taken(scene, input.id)) return Resolve.No(refuse(index, FieldName("id"), Detail("'${input.id.raw}' is already on the board")))
        val facing = sides(from.slot, to.slot)
        return Resolve.Yes(placed(input, from.at(facing.from), to.at(facing.to)))
    }

    private fun placed(input: Input, from: Endpoint, to: Endpoint): Resolved {
        val start = CanvasSnap.anchorOn(from.slot.frame(), from.side.raw)
        val end = CanvasSnap.anchorOn(to.slot.frame(), to.side.raw)
        return Resolved(input.id, from, to, input.label, input.style, start, end)
    }

    private fun arrow(input: Resolved): CanvasOp {
        val json = buildJsonObject {
            put("id", input.id.raw)
            put("type", "Shape")
            put("shapeType", "ARROW")
            put("points", JsonArray(listOf(JsonPrimitive(point(input.start)), JsonPrimitive(point(input.end)))))
            input.label?.let { label ->
                put("text", label.raw)
                put("fontSize", LABEL_SIZE)
            }
            input.from.elementBinding()?.let { put("startBinding", it.raw) }
            input.to.elementBinding()?.let { put("endBinding", it.raw) }
            input.style.write(this)
        }
        return CanvasOp.AddElementOp("", "", 0L, input.id.raw, json.toString())
    }

    private fun binding(input: Resolved): CanvasOp = CanvasOp.SetArrowBindingOp(
        opId = "",
        actorId = "",
        lamport = 0L,
        elementId = input.id.raw,
        binding = CanvasArrowBinding(start = input.from.documentBinding(), end = input.to.documentBinding()),
    )

    private fun parse(index: OpIndex, op: JsonObject): Parse {
        val id = op.boardId(FieldName("id")) ?: return Parse.Refused(refuse(index, FieldName("id"), Detail("expected a string, the new arrow's id")))
        val from = op.boardId(FieldName("from")) ?: return Parse.Refused(refuse(index, FieldName("from"), Detail("expected a string, a note id or a shape id")))
        val to = op.boardId(FieldName("to")) ?: return Parse.Refused(refuse(index, FieldName("to"), Detail("expected a string, a note id or a shape id")))
        val label = labelOf(op) ?: return Parse.Refused(refuse(index, FieldName("label"), Detail("expected a string")))
        val style = when (val read = ConnectStyle.read(index, op["style"])) {
            is StyleRead.Ok -> read.style
            is StyleRead.Refused -> return Parse.Refused(read.message)
        }
        return Parse.Ok(Input(id, from, to, label.text, style))
    }

    /** Null when the field is absent. A [Label.Bad] when it is present and not a string. */
    private fun labelOf(op: JsonObject): Label? {
        val value = op[FieldName("label").raw] ?: return Label.None
        val text = value.text() ?: return null
        return Label.Text(BoardId(text))
    }

    private fun missing(index: OpIndex, field: FieldName, id: BoardId) =
        refuse(index, field, Detail("'${id.raw}' is not a note or a shape on the board"))

    private fun refuse(index: OpIndex, field: FieldName, detail: Detail) =
        "op ${index.raw} field ${field.raw}: ${detail.raw}. Expected $EXAMPLE. Nothing in this batch was published."

    private fun endpoint(scene: SceneText, id: BoardId): Endpoint? {
        val document = CanvasOpProjector.documentsOf(scene.raw).firstOrNull { it.id == id.raw }
        document?.frame?.let { return Endpoint.Note(id, Slot(it.x, it.y, it.width, it.height)) }
        if (document != null) return null
        val element = elements(scene).firstOrNull { it.text(FieldName("id")) == id.raw } ?: return null
        if (element.text(FieldName("type")) != "Shape") return null
        return Endpoint.Shape(id, ElementBounds(element, conservative = false).bounds())
    }

    private fun taken(scene: SceneText, id: BoardId): Boolean =
        CanvasOpProjector.documentsOf(scene.raw).any { it.id == id.raw } || elements(scene).any { it.text(FieldName("id")) == id.raw }

    private fun elements(scene: SceneText): List<JsonObject> {
        val root = runCatching { json.parseToJsonElement(scene.raw) }.getOrNull() as? JsonObject ?: return emptyList()
        return (root["elements"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }

    private fun overlaps(a: Slot, b: Slot): Boolean =
        a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height

    private fun sides(from: Slot, to: Slot): Sides = facing(Centre(from.centerX(), from.centerY()), Centre(to.centerX(), to.centerY()))

    private fun facing(from: Centre, to: Centre): Sides {
        val delta = Delta(to.x - from.x, to.y - from.y)
        return if (delta.mostlyHorizontal()) horizontal(delta) else vertical(delta)
    }

    private fun horizontal(delta: Delta): Sides =
        if (delta.dx >= 0f) Sides(SideName(CanvasSnapAnchor.RIGHT), SideName(CanvasSnapAnchor.LEFT))
        else Sides(SideName(CanvasSnapAnchor.LEFT), SideName(CanvasSnapAnchor.RIGHT))

    private fun vertical(delta: Delta): Sides =
        if (delta.dy >= 0f) Sides(SideName(CanvasSnapAnchor.BOTTOM), SideName(CanvasSnapAnchor.TOP))
        else Sides(SideName(CanvasSnapAnchor.TOP), SideName(CanvasSnapAnchor.BOTTOM))

    private fun point(at: Pair<Float, Float>): String = "${coord(WorldCoord(at.first))},${coord(WorldCoord(at.second))}"

    private fun coord(value: WorldCoord): String =
        if (value.raw.toInt().toFloat() == value.raw) "${value.raw.toInt()}.0" else value.raw.toString()

    private fun decode(element: JsonElement): CanvasOp = HostCanvasToolInputs.ops(JsonArray(listOf(element))).single()

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.text(key: FieldName): String? = this[key.raw]?.text()

    private fun JsonObject.boardId(key: FieldName): BoardId? = text(key)?.let(::BoardId)

    private fun Slot.centerX(): Float = x + width / 2f

    private fun Slot.centerY(): Float = y + height / 2f

    private fun Slot.frame(): CanvasDocumentFrame = CanvasDocumentFrame(x, y, width, height)

    private class Scratch(scene: SceneText) {
        var scene: SceneText = scene
            private set
        private var lamport = CanvasOpProjector.maxLamport(scene.raw)

        fun apply(op: CanvasOp) {
            lamport += 1
            scene = SceneText(CanvasOpProjector.project(scene.raw, listOf(op.withStamp("connect-scratch", lamport))))
        }
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
}

@JvmInline
private value class SceneText(val raw: String)

@JvmInline
private value class BoardId(val raw: String)

@JvmInline
private value class FieldName(val raw: String)

@JvmInline
private value class OpIndex(val raw: Int)

@JvmInline
private value class SideName(val raw: String)

@JvmInline
private value class WorldCoord(val raw: Float)

@JvmInline
private value class Detail(val raw: String)

private data class Centre(val x: Float, val y: Float)

private data class Delta(val dx: Float, val dy: Float) {
    fun mostlyHorizontal(): Boolean = abs(dx) >= abs(dy)
}

/** `style` on a connect: only the arrow fields that already exist. */
private data class ConnectStyle(val strokeColor: String?, val strokeWidth: Float?, val dashed: Boolean?) {
    fun write(json: JsonObjectBuilder) {
        strokeColor?.let { json.put("strokeColor", it) }
        strokeWidth?.let { json.put("strokeWidth", it) }
        if (dashed == true) json.put("strokeStyle", "DASHED")
    }

    companion object {
        private val KEYS = setOf("strokeColor", "strokeWidth", "dashed")
        private val COLOR = Regex("""#[0-9a-fA-F]{8}""")
        private val EMPTY = ConnectStyle(null, null, null)

        fun read(index: OpIndex, value: JsonElement?): StyleRead = when (val parsed = asObject(value)) {
            ObjectRead.Empty -> StyleRead.Ok(EMPTY)
            ObjectRead.NotObject -> StyleRead.Refused(styleRefuse(index, FieldName("style"), Detail("expected an object of strokeColor, strokeWidth and dashed")))
            is ObjectRead.Obj -> fields(index, parsed.obj)
        }

        private fun fields(index: OpIndex, obj: JsonObject): StyleRead {
            unknown(obj)?.let { key ->
                return StyleRead.Refused(styleRefuse(index, FieldName("style.${key.raw}"), Detail("unknown style key '${key.raw}'")))
            }
            return painted(index, obj)
        }

        private fun painted(index: OpIndex, obj: JsonObject): StyleRead {
            val color = parseColor(obj)
            if (color is Parsed.Bad) return StyleRead.Refused(styleRefuse(index, FieldName("style.strokeColor"), color.detail))
            val width = parseWidth(obj)
            if (width is Parsed.Bad) return StyleRead.Refused(styleRefuse(index, FieldName("style.strokeWidth"), width.detail))
            val dashed = parseDashed(obj)
            if (dashed is Parsed.Bad) return StyleRead.Refused(styleRefuse(index, FieldName("style.dashed"), dashed.detail))
            return StyleRead.Ok(ConnectStyle((color as Parsed.Ok).value, (width as Parsed.Ok).value, (dashed as Parsed.Ok).value))
        }

        private fun unknown(obj: JsonObject): FieldName? = obj.keys.firstOrNull { it !in KEYS }?.let(::FieldName)

        private fun parseColor(obj: JsonObject): Parsed<String> {
            val color = obj["strokeColor"] ?: return Parsed.Ok(null)
            val text = (color as? JsonPrimitive)?.contentOrNull
            if (text == null || !COLOR.matches(text)) return Parsed.Bad(Detail("expected a colour #rrggbbaa"))
            return Parsed.Ok(text)
        }

        private fun parseWidth(obj: JsonObject): Parsed<Float> {
            val width = obj["strokeWidth"] ?: return Parsed.Ok(null)
            val number = (width as? JsonPrimitive)?.floatOrNull
            if (number == null || number <= 0f) return Parsed.Bad(Detail("expected a number greater than 0"))
            return Parsed.Ok(number)
        }

        private fun parseDashed(obj: JsonObject): Parsed<Boolean> {
            val flag = obj["dashed"] ?: return Parsed.Ok(null)
            val primitive = flag as? JsonPrimitive
            if (primitive == null || primitive.isString) return Parsed.Bad(Detail("expected true or false"))
            return Parsed.Ok(primitive.content == "true")
        }

        private fun asObject(value: JsonElement?): ObjectRead = when (value) {
            null -> ObjectRead.Empty
            is JsonObject -> ObjectRead.Obj(value)
            else -> ObjectRead.NotObject
        }

        private fun styleRefuse(index: OpIndex, field: FieldName, detail: Detail) =
            "op ${index.raw} field ${field.raw}: ${detail.raw}. Expected ${CanvasConnect.EXAMPLE}. Nothing in this batch was published."
    }
}

private sealed interface ObjectRead {
    data object Empty : ObjectRead
    data object NotObject : ObjectRead
    data class Obj(val obj: JsonObject) : ObjectRead
}

private sealed interface Parsed<T> {
    class Ok<T>(val value: T?) : Parsed<T>
    class Bad<T>(val detail: Detail) : Parsed<T>
}

private sealed interface StyleRead {
    data class Ok(val style: ConnectStyle) : StyleRead
    data class Refused(val message: String) : StyleRead
}

private sealed interface Label {
    data object None : Label
    data class Text(val id: BoardId) : Label

    val text: BoardId? get() = (this as? Text)?.id
}

private data class Input(val id: BoardId, val from: BoardId, val to: BoardId, val label: BoardId?, val style: ConnectStyle)

private data class Sides(val from: SideName, val to: SideName)

private data class Resolved(
    val id: BoardId,
    val from: Endpoint,
    val to: Endpoint,
    val label: BoardId?,
    val style: ConnectStyle,
    val start: Pair<Float, Float>,
    val end: Pair<Float, Float>,
)

private sealed interface Endpoint {
    val slot: Slot
    val side: SideName

    fun documentBinding(): CanvasEndBinding?
    fun elementBinding(): BoardId?

    data class Note(val id: BoardId, override val slot: Slot, override val side: SideName = SideName("")) : Endpoint {
        override fun documentBinding(): CanvasEndBinding = CanvasEndBinding(id.raw, side.raw)
        override fun elementBinding(): BoardId? = null
    }

    data class Shape(val id: BoardId, override val slot: Slot, override val side: SideName = SideName("")) : Endpoint {
        override fun documentBinding(): CanvasEndBinding? = null
        override fun elementBinding(): BoardId = id
    }

    fun at(side: SideName): Endpoint = when (this) {
        is Note -> copy(side = side)
        is Shape -> copy(side = side)
    }
}

private sealed interface Resolve {
    data class Yes(val input: Resolved) : Resolve
    data class No(val message: String) : Resolve
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
