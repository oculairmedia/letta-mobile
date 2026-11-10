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
        val fields = InputObject(input)
        fields.onlyKeys(setOf(InputKey.ACTION, InputKey.OPS, InputKey.DRY_RUN))
        val action = fields.action()
        val request = if (action == ShellLayoutAction.APPLY) fields.applyRequest() else fields.plainRequest(action)
        return ShellLayoutReading(request.takeIf { fields.errors.isEmpty() }, fields.errors.toList())
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

/** A field of the input, by its JSON name. */
private enum class InputKey(val wire: String) {
    ACTION(ShellLayoutToolInput.ACTION),
    OPS(ShellLayoutToolInput.OPS),
    DRY_RUN(ShellLayoutToolInput.DRY_RUN),
    OP(ShellLayoutToolInput.OP),
    LANE(ShellLayoutToolInput.LANE),
    TO_LANE(ShellLayoutToolInput.TO_LANE),
    VIEW(ShellLayoutToolInput.VIEW),
    OBJECT(ShellLayoutToolInput.OBJECT),
    INDEX(ShellLayoutToolInput.INDEX),
    SLOTS(ShellLayoutToolInput.SLOTS),
}

/** The fields an add, remove or move always allows. */
private val laneSlotKeys = setOf(InputKey.OP, InputKey.LANE, InputKey.VIEW, InputKey.OBJECT)

/**
 * One JSON object of the input, read field by field: [op] is the op it is in (null for the input itself),
 * and every problem found is added to [errors], which all objects of one input share.
 */
private class InputObject(
    private val obj: JsonObject,
    private val op: Int? = null,
    val errors: MutableList<ShellLayoutEditError> = mutableListOf(),
) {
    fun problem(detail: String) {
        errors += ShellLayoutEditError(op, ShellLayoutEditRule.MALFORMED, detail)
    }

    operator fun get(key: InputKey): JsonElement? = obj[key.wire]

    fun has(key: InputKey): Boolean = key.wire in obj

    fun onlyKeys(allowed: Set<InputKey>) {
        val names = allowed.map { it.wire }.toSet()
        (obj.keys - names).forEach { problem("Unknown field $it; allowed: ${names.sorted().joinToString()}.") }
    }

    /** [key] as a string, or null (and a problem) when it is missing. */
    fun required(key: InputKey): String? {
        if (!has(key)) problem("${key.wire} is required.")
        return optional(key)
    }

    /** [key] as a string, or null when it is missing (or, with a problem, not a string). */
    fun optional(key: InputKey): String? = typed(key, "a string") { value -> value.takeIf { it.isString }?.content }

    /** [key] as true or false; false when it is missing (or, with a problem, not a boolean). */
    fun flag(key: InputKey): Boolean = typed(key, "true or false") { value -> value.takeUnless { it.isString }?.booleanOrNull } ?: false

    /** [key] as an integer, or null when it is missing (or, with a problem, not an integer). */
    fun int(key: InputKey): Int? = typed(key, "an integer") { value -> value.takeUnless { it.isString }?.intOrNull }

    /** The object [child] in this one, read as part of the same op. */
    fun nested(child: JsonObject): InputObject = InputObject(child, op, errors)

    /** One entry of the ops, read as that op, or null (and a problem) when it is not an object. */
    fun opAt(entry: IndexedValue<JsonElement>): InputObject? {
        val opObject = entry.value as? JsonObject
        if (opObject == null) errors += ShellLayoutEditError(entry.index, ShellLayoutEditRule.MALFORMED, "Each op must be an object.")
        return opObject?.let { InputObject(it, entry.index, errors) }
    }

    /** [key] read by [read] when present, or null and a problem saying it must be [expected]. */
    private fun <T : Any> typed(key: InputKey, expected: String, read: (JsonPrimitive) -> T?): T? {
        val value = get(key) ?: return null
        return (value as? JsonPrimitive)?.let(read) ?: null.also { problem("${key.wire} must be $expected.") }
    }
}

private fun InputObject.action(): ShellLayoutAction? {
    val name = required(InputKey.ACTION) ?: return null
    return ShellLayoutAction.entries.firstOrNull { it.wire == name }
        ?: null.also { problem("action must be one of ${ShellLayoutAction.entries.joinToString { it.wire }}.") }
}

private fun InputObject.applyRequest(): ShellLayoutRequest = ShellLayoutRequest(ShellLayoutAction.APPLY, ops(), flag(InputKey.DRY_RUN))

/** The request of any [action] but apply (none when it is null), refusing the fields only apply takes. */
private fun InputObject.plainRequest(action: ShellLayoutAction?): ShellLayoutRequest? {
    val hasApplyFields = has(InputKey.OPS) || has(InputKey.DRY_RUN)
    if (hasApplyFields) problem("ops and dry_run belong to action apply only.")
    return action?.let { ShellLayoutRequest(it) }
}

private fun InputObject.ops(): List<ShellLayoutOp> {
    val array = get(InputKey.OPS) as? JsonArray
        ?: return emptyList<ShellLayoutOp>().also { problem("ops must be an array of op objects.") }
    return array.withIndex().mapNotNull { opAt(it)?.op() }
}

private fun InputObject.op(): ShellLayoutOp? = when (val name = required(InputKey.OP)) {
    null -> null
    ShellLayoutToolInput.SET_LANE -> setLane()
    ShellLayoutToolInput.ADD -> add()
    ShellLayoutToolInput.REMOVE -> remove()
    ShellLayoutToolInput.MOVE -> move()
    ShellLayoutToolInput.RESET -> ShellLayoutOp.Reset.also { onlyKeys(setOf(InputKey.OP)) }
    else -> null.also { problem("Unknown op $name; ops are ${ShellLayoutToolInput.opNames.joinToString()}.") }
}

private fun InputObject.setLane(): ShellLayoutOp? {
    onlyKeys(setOf(InputKey.OP, InputKey.LANE, InputKey.SLOTS))
    val lane = required(InputKey.LANE)
    val slots = slotList()
    return if (lane == null || slots == null) null else ShellLayoutOp.SetLane(lane, slots)
}

/** The slots of a set_lane op, or null when the array or any slot in it has a problem. */
private fun InputObject.slotList(): List<ModuleSlot>? {
    val array = get(InputKey.SLOTS) as? JsonArray ?: return null.also { problem("slots must be an array of {view, object?}.") }
    val slots = array.map { slotIn(it) }
    return if (null in slots) null else slots.filterNotNull()
}

/** One slot of a set_lane op's array, or null (and a problem) when it is not a valid slot object. */
private fun InputObject.slotIn(element: JsonElement): ModuleSlot? =
    (element as? JsonObject)?.let { nested(it).slotObject() } ?: null.also { problem("Each slot must be an object {view, object?}.") }

/** This object as a slot {view, object?} with no other fields. */
private fun InputObject.slotObject(): ModuleSlot? {
    onlyKeys(setOf(InputKey.VIEW, InputKey.OBJECT))
    return slot()
}

private fun InputObject.add(): ShellLayoutOp? {
    val (lane, slot) = laneSlot(setOf(InputKey.INDEX)) ?: return null
    return ShellLayoutOp.Add(lane, slot, int(InputKey.INDEX))
}

private fun InputObject.remove(): ShellLayoutOp? = laneSlot(emptySet())?.let { (lane, slot) -> ShellLayoutOp.Remove(lane, slot) }

private fun InputObject.move(): ShellLayoutOp? {
    val (lane, slot) = laneSlot(setOf(InputKey.TO_LANE, InputKey.INDEX)) ?: return null
    val toLane = optional(InputKey.TO_LANE) ?: lane
    return ShellLayoutOp.Move(lane, slot, toLane, int(InputKey.INDEX))
}

/** The lane and slot an add, remove or move names, allowing its [extra] fields too. */
private fun InputObject.laneSlot(extra: Set<InputKey>): Pair<String, ModuleSlot>? {
    onlyKeys(laneSlotKeys + extra)
    val lane = required(InputKey.LANE)
    val slot = slot()
    return if (lane == null || slot == null) null else lane to slot
}

/** The slot {view, object?} this object names. */
private fun InputObject.slot(): ModuleSlot? {
    val view = required(InputKey.VIEW)
    val ref = optional(InputKey.OBJECT)
    if (view?.isBlank() == true) problem("view must not be blank.")
    return view?.takeIf { it.isNotBlank() }?.let { ModuleSlot(RuntimeViewId(it), ref) }
}
