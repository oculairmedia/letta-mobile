package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.layout.ModuleSlot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** The `shell_layout` tool's actions. */
enum class ShellLayoutAction(val wire: String) {
    /** Read the current lanes and the catalog. */
    GET("get"),

    /** Apply a list of ops, or check them with dry_run. */
    APPLY("apply"),

    /** Step back one change. */
    UNDO("undo"),

    /** Go back to the default layout. */
    RESET("reset"),
}

/**
 * One `shell_layout` call, read from its JSON input.
 *
 * @property action what the call asks for.
 * @property ops the ops of an [ShellLayoutAction.APPLY] call, empty otherwise.
 * @property dryRun true to check the ops without applying them.
 */
data class ShellLayoutRequest(val action: ShellLayoutAction, val ops: List<ShellLayoutOp> = emptyList(), val dryRun: Boolean = false)

/**
 * A `shell_layout` input as read: the request, or (when [request] is null) every problem with it.
 *
 * @property request the call the input makes, or null when it has problems.
 * @property errors each problem, empty when [request] is set.
 */
data class ShellLayoutReading(val request: ShellLayoutRequest?, val errors: List<ShellLayoutEditError>)

/**
 * Reads a `shell_layout` input strictly: unknown fields, wrong types and missing fields are each
 * reported (as [ShellLayoutEditRule.MALFORMED], with the op they are in) rather than ignored.
 */
object ShellLayoutToolInput {
    /** The op names the input accepts. */
    val opNames: List<String> = listOf(SET_LANE, ADD, REMOVE, MOVE, RESET)

    /** The call [input] makes, or every problem with it. */
    fun read(input: JsonObject): ShellLayoutReading {
        val reader = Reader()
        reader.onlyKeys(input, setOf(ACTION, OPS, DRY_RUN), null)
        val actionName = reader.string(input, ACTION, null, required = true)
        val action = ShellLayoutAction.entries.firstOrNull { it.wire == actionName }
        if (actionName != null && action == null) {
            reader.problem(null, "action must be one of ${ShellLayoutAction.entries.joinToString { it.wire }}.")
        }
        val isApply = action == ShellLayoutAction.APPLY
        if (!isApply && (OPS in input || DRY_RUN in input)) reader.problem(null, "ops and dry_run belong to action apply only.")
        val ops = if (isApply) reader.ops(input[OPS]) else emptyList()
        val dryRun = if (isApply) reader.boolean(input, DRY_RUN) else false
        val request = ShellLayoutRequest(action ?: ShellLayoutAction.GET, ops, dryRun).takeIf { reader.errors.isEmpty() && action != null }
        return ShellLayoutReading(request, reader.errors.toList())
    }

    internal const val ACTION = "action"
    internal const val OPS = "ops"
    internal const val DRY_RUN = "dry_run"
    internal const val OP = "op"
    internal const val LANE = "lane"
    internal const val TO_LANE = "to_lane"
    internal const val VIEW = "view"
    internal const val OBJECT = "object"
    internal const val INDEX = "index"
    internal const val SLOTS = "slots"
    internal const val SET_LANE = "set_lane"
    internal const val ADD = "add"
    internal const val REMOVE = "remove"
    internal const val MOVE = "move"
    internal const val RESET = "reset"
}

/** Collects every problem while reading one input. */
private class Reader {
    val errors = mutableListOf<ShellLayoutEditError>()

    fun problem(op: Int?, detail: String) {
        errors += ShellLayoutEditError(op, ShellLayoutEditRule.MALFORMED, detail)
    }

    fun onlyKeys(obj: JsonObject, allowed: Set<String>, op: Int?) {
        (obj.keys - allowed).forEach { problem(op, "Unknown field $it; allowed: ${allowed.sorted().joinToString()}.") }
    }

    fun string(obj: JsonObject, key: String, op: Int?, required: Boolean = false): String? {
        val value = obj[key]
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (value != null && text == null) problem(op, "$key must be a string.")
        if (value == null && required) problem(op, "$key is required.")
        return text
    }

    fun boolean(obj: JsonObject, key: String): Boolean {
        val value = obj[key] ?: return false
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: false.also { problem(null, "$key must be true or false.") }
    }

    private fun index(obj: JsonObject, op: Int): Int? {
        val value = obj[ShellLayoutToolInput.INDEX] ?: return null
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: null.also { problem(op, "index must be an integer.") }
    }

    fun ops(value: JsonElement?): List<ShellLayoutOp> {
        val array = value as? JsonArray ?: return emptyList<ShellLayoutOp>().also { problem(null, "ops must be an array of op objects.") }
        return array.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null.also { problem(index, "Each op must be an object.") }
            op(obj, index)
        }
    }

    private fun op(obj: JsonObject, index: Int): ShellLayoutOp? = when (
        val name = string(obj, ShellLayoutToolInput.OP, index, required = true)
    ) {
        null -> null
        ShellLayoutToolInput.SET_LANE -> setLane(obj, index)
        ShellLayoutToolInput.ADD -> add(obj, index)
        ShellLayoutToolInput.REMOVE -> remove(obj, index)
        ShellLayoutToolInput.MOVE -> move(obj, index)
        ShellLayoutToolInput.RESET -> ShellLayoutOp.Reset.also { onlyKeys(obj, setOf(ShellLayoutToolInput.OP), index) }
        else -> null.also { problem(index, "Unknown op $name; ops are ${ShellLayoutToolInput.opNames.joinToString()}.") }
    }

    private fun setLane(obj: JsonObject, index: Int): ShellLayoutOp? {
        onlyKeys(obj, setOf(ShellLayoutToolInput.OP, ShellLayoutToolInput.LANE, ShellLayoutToolInput.SLOTS), index)
        val lane = string(obj, ShellLayoutToolInput.LANE, index, required = true)
        val array = obj[ShellLayoutToolInput.SLOTS] as? JsonArray
        if (array == null) problem(index, "slots must be an array of {view, object?}.")
        val slots = array.orEmpty().map { element ->
            (element as? JsonObject)?.let { slot(it, index, setOf(ShellLayoutToolInput.VIEW, ShellLayoutToolInput.OBJECT)) }
                ?: null.also { problem(index, "Each slot must be an object {view, object?}.") }
        }
        return if (lane == null || array == null || null in slots) null else ShellLayoutOp.SetLane(lane, slots.filterNotNull())
    }

    private fun add(obj: JsonObject, index: Int): ShellLayoutOp? {
        val fields = laneSlot(obj, index, setOf(ShellLayoutToolInput.INDEX)) ?: return null
        return ShellLayoutOp.Add(fields.first, fields.second, index(obj, index))
    }

    private fun remove(obj: JsonObject, index: Int): ShellLayoutOp? =
        laneSlot(obj, index, emptySet())?.let { (lane, slot) -> ShellLayoutOp.Remove(lane, slot) }

    private fun move(obj: JsonObject, index: Int): ShellLayoutOp? {
        val (lane, slot) = laneSlot(obj, index, setOf(ShellLayoutToolInput.TO_LANE, ShellLayoutToolInput.INDEX)) ?: return null
        val toLane = string(obj, ShellLayoutToolInput.TO_LANE, index) ?: lane
        return ShellLayoutOp.Move(lane, slot, toLane, index(obj, index))
    }

    /** The lane and slot an add, remove or move names, reading [extra] fields as allowed too. */
    private fun laneSlot(obj: JsonObject, index: Int, extra: Set<String>): Pair<String, ModuleSlot>? {
        val base = setOf(ShellLayoutToolInput.OP, ShellLayoutToolInput.LANE, ShellLayoutToolInput.VIEW, ShellLayoutToolInput.OBJECT)
        onlyKeys(obj, base + extra, index)
        val lane = string(obj, ShellLayoutToolInput.LANE, index, required = true)
        val slot = slot(obj, index, null)
        return if (lane == null || slot == null) null else lane to slot
    }

    /** The slot {view, object?} in [obj]; with [allowed], other fields are refused. */
    private fun slot(obj: JsonObject, index: Int, allowed: Set<String>?): ModuleSlot? {
        allowed?.let { onlyKeys(obj, it, index) }
        val view = string(obj, ShellLayoutToolInput.VIEW, index, required = true)
        val ref = string(obj, ShellLayoutToolInput.OBJECT, index)
        if (view != null && view.isBlank()) problem(index, "view must not be blank.")
        return view?.takeIf { it.isNotBlank() }?.let { ModuleSlot(RuntimeViewId(it), ref) }
    }
}
