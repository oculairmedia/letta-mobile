package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.mount.AuthorizedCanvasMounts
import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A params field, or the params object itself; [wire] is the only text a refusal names. */
internal enum class CanvasMountField(val wire: String) {
    PARAMS("params"),
    AGENT(CanvasMountWire.AGENT_ID),
    CONVERSATION(CanvasMountWire.CONVERSATION_ID),
    CANVAS(CanvasMountWire.CANVAS_ID),
}

/** Fixed invalid-input codes. */
internal enum class CanvasMountInputProblem(val wire: String) {
    MISSING("missing"),
    NOT_STRING("not_string"),
    BLANK("blank"),
    TOO_LONG("too_long"),

    /** A control or invisible formatting character (bidirectional overrides, zero-width characters). */
    MALFORMED("malformed"),

    /** The params carry a field the route does not define; its name is never echoed. */
    UNKNOWN_FIELD("unknown_field"),
}

/** The six routes: wire method, exact params, and whether the route mutates. */
internal enum class CanvasMountRoute(val method: String, val fields: List<CanvasMountField>, val mutates: Boolean) {
    RESOLVE(CanvasMountWire.RESOLVE, ADDRESS_FIELDS, mutates = false),
    BIND(CanvasMountWire.BIND, BINDING_FIELDS, mutates = true),
    REPLACE(CanvasMountWire.REPLACE, BINDING_FIELDS, mutates = true),
    SET_DEFAULT(CanvasMountWire.SET_DEFAULT, listOf(CanvasMountField.AGENT, CanvasMountField.CANVAS), mutates = true),
    CLEAR_DEFAULT(CanvasMountWire.CLEAR_DEFAULT, listOf(CanvasMountField.AGENT), mutates = true),
    CANDIDATES(CanvasMountWire.CANDIDATES, ADDRESS_FIELDS, mutates = false),
    ;

    /**
     * Strict parse: params must be an object holding exactly this route's fields (unknown fields are refused, so a
     * spoofed principal, host or actor field can never be read), each a non-blank string of at most
     * [AuthorizedCanvasMounts.MAX_ID_BYTES] UTF-8 bytes without control or invisible formatting characters.
     */
    fun parse(params: JsonObject?): CanvasMountParsed {
        if (params == null) return CanvasMountOutcome.InvalidInput(CanvasMountField.PARAMS, CanvasMountInputProblem.MISSING)
        val known = fields.mapTo(mutableSetOf()) { it.wire }
        if (!known.containsAll(params.keys)) {
            return CanvasMountOutcome.InvalidInput(CanvasMountField.PARAMS, CanvasMountInputProblem.UNKNOWN_FIELD)
        }
        fields.forEach { field ->
            problemOf(params[field.wire])?.let { return CanvasMountOutcome.InvalidInput(field, it) }
        }
        return CanvasMountInput(fields.associateWith { (params.getValue(it.wire) as JsonPrimitive).content })
    }
}

private val ADDRESS_FIELDS = listOf(CanvasMountField.AGENT, CanvasMountField.CONVERSATION)

private val BINDING_FIELDS = listOf(CanvasMountField.AGENT, CanvasMountField.CONVERSATION, CanvasMountField.CANVAS)

/** Either validated params or the refusal that replaces them. */
internal sealed interface CanvasMountParsed

/** Validated params of one route; each accessor is valid only for routes that define the field. */
internal class CanvasMountInput(private val values: Map<CanvasMountField, String>) : CanvasMountParsed {
    val agent: AgentId get() = AgentId(values.getValue(CanvasMountField.AGENT))

    val conversation: ConversationId get() = ConversationId(values.getValue(CanvasMountField.CONVERSATION))

    val canvas: CanvasId get() = CanvasId(values.getValue(CanvasMountField.CANVAS))
}

private fun problemOf(element: JsonElement?): CanvasMountInputProblem? {
    if (element == null) return CanvasMountInputProblem.MISSING
    val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return CanvasMountInputProblem.NOT_STRING
    return textProblem(text)
}

private fun textProblem(text: String): CanvasMountInputProblem? = when {
    text.isBlank() -> CanvasMountInputProblem.BLANK
    text.encodeToByteArray().size > AuthorizedCanvasMounts.MAX_ID_BYTES -> CanvasMountInputProblem.TOO_LONG
    text.any(::isHiddenOrControl) -> CanvasMountInputProblem.MALFORMED
    else -> null
}

private fun isHiddenOrControl(char: Char): Boolean =
    char.isISOControl() || Character.getType(char) == Character.FORMAT.toInt()
