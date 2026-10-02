package com.letta.mobile.data.canvas.plugin

import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.schema.JsonSchemaCheck
import com.letta.mobile.data.schema.JsonSchemaExample
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A plugin element kind at one schema version: `ext:letta.example/widget` v2. */
data class PluginKindRef(val type: String, val v: Int)

/**
 * The kind of each plugin element a batch writes to, for a write that names no type (a props
 * update): what the board holds, overlaid with the types the batch itself writes. [None] knows
 * no element, so such a write is held to the envelope alone.
 */
fun interface PluginBoardKinds {
    fun kindOf(elementId: String): PluginKindRef?

    companion object {
        val None: PluginBoardKinds = PluginBoardKinds { null }

        private val json = Json { ignoreUnknownKeys = true }

        /** The kinds on [sceneJson] once [ops] have written theirs. */
        fun of(sceneJson: String, ops: List<CanvasOp>): PluginBoardKinds {
            val root = runCatching { json.parseToJsonElement(sceneJson) }.getOrNull() as? JsonObject
            val onBoard = root?.let(CanvasPluginElements::typedEntries).orEmpty().mapNotNull(::kindOfEntry).toMap()
            val written = flatten(ops).filterIsInstance<CanvasOp.SetPluginElementOp>()
                .mapNotNull { op -> op.elementType?.let { op.elementId to PluginKindRef(it, op.v ?: 1) } }
            val kinds = onBoard + written
            return PluginBoardKinds { kinds[it] }
        }

        private fun kindOfEntry(entry: JsonObject): Pair<String, PluginKindRef>? {
            val id = (entry["id"] as? JsonPrimitive)?.content ?: return null
            val type = (entry["type"] as? JsonPrimitive)?.content ?: return null
            return id to PluginKindRef(type, (entry["v"] as? JsonPrimitive)?.intOrNull ?: 1)
        }

        private fun flatten(ops: List<CanvasOp>): List<CanvasOp> =
            ops.flatMap { if (it is CanvasOp.BatchOp) flatten(it.ops) else listOf(it) }
    }
}

/**
 * A plugin element write's props held to its kind's schema (plan section 4.3, step 2), after the
 * envelope ([CanvasPluginElementSchema]) has passed.
 *
 * The kind is the one the write names, else the one the element has ([PluginBoardKinds]). A kind
 * [PluginKindCatalog] knows has its props checked, each problem at its `/props/...` pointer, the
 * first with an example of the kind's props. A kind it does not know is accepted with a WARN
 * (D11): an unknown plugin, or a known plugin at a version this host has no schema for.
 */
object PluginKindProps {
    const val UNKNOWN_KIND_EVENT: String = "pluginElement.unknownKind"

    fun check(op: CanvasOp.SetPluginElementOp, kinds: PluginKindCatalog, board: PluginBoardKinds): List<CanvasPluginProblem> {
        val kind = op.elementType?.let { PluginKindRef(it, op.v ?: 1) } ?: board.kindOf(op.elementId) ?: return emptyList()
        val schema = kinds.schemaFor(kind.type, kind.v)
        if (schema == null) {
            if (op.elementType != null) reportUnknown(op.elementId, kind, kinds)
            return emptyList()
        }
        return op.props?.let { props(kind, it, schema) }.orEmpty()
    }

    /** Every place [props] break [schema], at `/props/...`; empty when they hold. */
    fun props(kind: PluginKindRef, props: JsonObject, schema: JsonObject): List<CanvasPluginProblem> {
        val problems = JsonSchemaCheck(schema).check(props, path = CanvasPluginElementSchema.PROPS.path)
        if (problems.isEmpty()) return emptyList()
        val example = "; ${kind.type} v${kind.v} props look like ${JsonSchemaExample.of(schema)}"
        return problems.mapIndexed { index, problem ->
            CanvasPluginProblem(problem.path, problem.message + if (index == 0) example else "")
        }
    }

    private fun reportUnknown(elementId: String, kind: PluginKindRef, kinds: PluginKindCatalog) {
        val pluginId = kind.type.removePrefix(CanvasPluginElement.TYPE_PREFIX).substringBefore('/')
        val known = kinds.knows(pluginId)
        Telemetry.event(
            "Canvas", UNKNOWN_KIND_EVENT,
            "elementId" to elementId,
            "type" to kind.type,
            "v" to kind.v,
            "plugin" to if (known) "known" else "unknown",
            "detail" to if (known) "no props schema for this version here; accepted on the envelope alone"
            else "no props schema for this kind here; accepted on the envelope alone",
            level = Telemetry.Level.WARN,
        )
    }
}
