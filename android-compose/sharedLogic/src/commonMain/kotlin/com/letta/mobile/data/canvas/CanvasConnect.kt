package com.letta.mobile.data.canvas

import kotlin.jvm.JvmInline
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Ops read from a tool call: ready to check, or refused before anything is published. */
internal sealed interface CanvasOpsRead {
    data class Ready(val ops: List<CanvasOp>) : CanvasOpsRead
    data class Refused(val message: String) : CanvasOpsRead
}

/**
 * `connect` is an agent input only (letta-mobile-i9yps.1). It becomes ONE [CanvasOp.BatchOp] of an
 * arrow and the existing binding op before the batch is checked, logged or relayed, so the op log
 * never sees `connect`, the arrow and its binding are one log entry, and every op after a connect
 * keeps the index the agent gave it (the batch's children are `2.0` and `2.1`). A connect inside a
 * `batch` op expands the same way, refused by its place (`1.2`).
 */
internal object CanvasConnect {
    const val EXAMPLE =
        """{"type":"connect","id":"arrow-1","from":"note-a","to":"note-b","label":"next","style":{"strokeColor":"#1f2937","strokeWidth":2,"dashed":false}}"""

    private const val TYPE = "type"
    private const val CONNECT = "connect"
    private const val BATCH = "batch"
    private const val OPS = "ops"

    fun read(sceneJson: String, opsJson: JsonElement): CanvasOpsRead {
        val array = opsJson as? JsonArray ?: throw IllegalArgumentException("ops must be an array of op objects")
        // Most batches hold no connect: read them as they are, without a scratch board.
        if (array.none(::holdsConnect)) return CanvasOpsRead.Ready(HostCanvasToolInputs.ops(array))
        return Walk(ConnectScratch(sceneJson)).list(array, parent = null)
    }

    fun refusal(at: ConnectOpLabel, field: ConnectField, detail: ConnectDetail): String =
        "op ${at.raw} field ${field.raw}: ${detail.raw}. Expected $EXAMPLE. Nothing in this batch was published."

    private fun holdsConnect(element: JsonElement): Boolean {
        val obj = element as? JsonObject ?: return false
        return when (obj.type()) {
            CONNECT -> true
            BATCH -> (obj[OPS] as? JsonArray)?.any(::holdsConnect) == true
            else -> false
        }
    }

    private fun JsonObject.type(): String? = (this[TYPE] as? JsonPrimitive)?.contentOrNull

    /** The ops in order, each applied to [scratch] before the next is read. */
    private class Walk(private val scratch: ConnectScratch) {
        fun list(array: JsonArray, parent: ConnectOpLabel?): CanvasOpsRead {
            val ops = mutableListOf<CanvasOp>()
            array.forEachIndexed { index, element ->
                when (val read = one(ConnectOpLabel.of(parent, index), element)) {
                    is CanvasOpsRead.Ready -> ops += read.ops
                    is CanvasOpsRead.Refused -> return read
                }
            }
            return CanvasOpsRead.Ready(ops)
        }

        private fun one(at: ConnectOpLabel, element: JsonElement): CanvasOpsRead {
            val obj = element as? JsonObject
            return when {
                obj == null || !holdsConnect(obj) -> kept(HostCanvasToolInputs.ops(JsonArray(listOf(element))).single())
                obj.type() == CONNECT -> connect(at, obj)
                else -> nested(at, obj.getValue(OPS) as JsonArray)
            }
        }

        private fun kept(op: CanvasOp): CanvasOpsRead {
            scratch.add(op)
            return CanvasOpsRead.Ready(listOf(op))
        }

        /** A `batch` op holding a connect: its children read in place, still one batch. */
        private fun nested(at: ConnectOpLabel, children: JsonArray): CanvasOpsRead = when (val read = list(children, at)) {
            is CanvasOpsRead.Refused -> read
            is CanvasOpsRead.Ready -> CanvasOpsRead.Ready(listOf(CanvasOp.BatchOp("", "", 0L, read.ops)))
        }

        private fun connect(at: ConnectOpLabel, op: JsonObject): CanvasOpsRead {
            val input = when (val parsed = ConnectInput.parse(at, op)) {
                is ConnectParse.Refused -> return CanvasOpsRead.Refused(parsed.message)
                is ConnectParse.Ok -> parsed.input
            }
            return when (val resolved = ConnectBoard.of(scratch.scene()).resolve(at, input)) {
                is ConnectResolve.No -> CanvasOpsRead.Refused(resolved.message)
                is ConnectResolve.Yes -> kept(CanvasOp.BatchOp("", "", 0L, listOf(arrow(resolved.arrow), binding(resolved.arrow))))
            }
        }
    }

    private fun arrow(input: ConnectArrow): CanvasOp {
        val json = buildJsonObject {
            put("id", input.id.raw)
            put("type", "Shape")
            put("shapeType", "ARROW")
            put("points", JsonArray(listOf(JsonPrimitive(input.start.text()), JsonPrimitive(input.end.text()))))
            input.label?.let { label ->
                put("text", label)
                put("fontSize", LABEL_SIZE)
            }
            input.from.elementBinding()?.let { put("startBinding", it.raw) }
            input.to.elementBinding()?.let { put("endBinding", it.raw) }
            input.style.write(this)
        }
        return CanvasOp.AddElementOp("", "", 0L, input.id.raw, json.toString())
    }

    private fun binding(input: ConnectArrow): CanvasOp = CanvasOp.SetArrowBindingOp(
        opId = "",
        actorId = "",
        lamport = 0L,
        elementId = input.id.raw,
        binding = CanvasArrowBinding(start = input.from.documentBinding(), end = input.to.documentBinding()),
    )

    /** Em size of a connector label, small enough to sit in the gap between the notes it joins. */
    private const val LABEL_SIZE = 13.0
}

/**
 * The board as a batch leaves it so far. Ops are projected only when a connect reads the board,
 * all that arrived since the last read in one pass, so a batch is not re-projected op by op.
 */
private class ConnectScratch(sceneJson: String) {
    private var projected = sceneJson
    private val pending = mutableListOf<CanvasOp>()
    private var lamport = CanvasOpProjector.maxLamport(sceneJson)

    fun add(op: CanvasOp) {
        lamport += 1
        pending += op.withStamp("connect-scratch", lamport)
    }

    fun scene(): String {
        if (pending.isNotEmpty()) {
            projected = CanvasOpProjector.project(projected, pending.toList())
            pending.clear()
        }
        return projected
    }
}

/** What a connect op asks for, read from its JSON. */
internal data class ConnectInput(
    val id: ConnectId,
    val from: ConnectId,
    val to: ConnectId,
    val label: String?,
    val style: ConnectStyle,
) {
    companion object {
        private val ID = ConnectField("id")
        private val FROM = ConnectField("from")
        private val TO = ConnectField("to")
        private val LABEL = ConnectField("label")

        fun parse(at: ConnectOpLabel, op: JsonObject): ConnectParse {
            val id = op.id(ID) ?: return refused(at, ID, "expected a string, the new arrow's id")
            val from = op.id(FROM) ?: return refused(at, FROM, "expected a string, a note id or a shape id")
            val to = op.id(TO) ?: return refused(at, TO, "expected a string, a note id or a shape id")
            val label = labelOf(op) ?: return refused(at, LABEL, "expected a string")
            val style = when (val read = ConnectStyle.read(at, op["style"])) {
                is ConnectStyleRead.Ok -> read.style
                is ConnectStyleRead.Refused -> return ConnectParse.Refused(read.message)
            }
            return ConnectParse.Ok(ConnectInput(id, from, to, label.text, style))
        }

        private fun refused(at: ConnectOpLabel, field: ConnectField, detail: String) =
            ConnectParse.Refused(CanvasConnect.refusal(at, field, ConnectDetail(detail)))

        /** [ConnectLabel.None] when absent or null; null when present and not a string. */
        private fun labelOf(op: JsonObject): ConnectLabel? {
            val value = op[LABEL.raw]
            if (value == null || value is JsonNull) return ConnectLabel.None
            val primitive = value as? JsonPrimitive
            if (primitive == null || !primitive.isString) return null
            return ConnectLabel.Text(primitive.content)
        }

        private fun JsonObject.id(field: ConnectField): ConnectId? =
            (this[field.raw] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::ConnectId)
    }
}

internal sealed interface ConnectParse {
    data class Ok(val input: ConnectInput) : ConnectParse
    data class Refused(val message: String) : ConnectParse
}

private sealed interface ConnectLabel {
    data object None : ConnectLabel
    data class Text(val value: String) : ConnectLabel

    val text: String? get() = (this as? Text)?.value
}

/** Where an op sat in the agent's input: `3`, or `1.2` inside a batch op. */
@JvmInline
internal value class ConnectOpLabel(val raw: String) {
    companion object {
        fun of(parent: ConnectOpLabel?, index: Int): ConnectOpLabel =
            ConnectOpLabel(if (parent == null) index.toString() else "${parent.raw}.$index")
    }
}

@JvmInline
internal value class ConnectId(val raw: String)

@JvmInline
internal value class ConnectField(val raw: String)

@JvmInline
internal value class ConnectDetail(val raw: String)
