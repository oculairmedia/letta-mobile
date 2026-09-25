package com.letta.mobile.data.canvas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What is wrong with one element, measured against [CanvasSceneSchema]; null when nothing is. */
internal object CanvasSceneElementFaults {
    /** Why [element], of [spec], would not draw as its writer meant it: the bar an agent's writes are held to. */
    fun of(element: JsonObject, spec: CanvasElementSpec): String? =
        missing(element, spec.required) ?: badField(element, strict = true) ?: badChoice(element) ?: tooFewPoints(element, spec)

    /**
     * Why DrawBox's decoder would throw on [element] (taking the whole scene down with it) or draw
     * nothing for it: what an app checks of a scene already in the log before drawing it.
     */
    fun undecodable(element: JsonObject): String? {
        missing(element, CanvasSceneSchema.wireRequired)?.let { return it }
        val type = (element["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type !in CanvasSceneSchema.allowedTypes) return "has unknown type '$type'"
        return badField(element, strict = false)
    }

    private fun missing(element: JsonObject, required: List<String>): String? =
        required.firstOrNull { element[it] == null || element[it] is JsonNull }?.let { "is missing required field '$it'" }

    private fun badField(element: JsonObject, strict: Boolean): String? = element.entries.firstNotNullOfOrNull { (key, value) ->
        val kind = CanvasSceneSchema.fields[key]?.kind
        val wrong = kind != null && value !is JsonNull && !CanvasSceneFieldChecks.accepts(kind, value, strict)
        if (wrong && kind != null) "has field '$key' that is not ${phrase(kind)} (got $value)" else null
    }

    private fun badChoice(element: JsonObject): String? = element.entries.firstNotNullOfOrNull { (key, value) ->
        val allowed = CanvasSceneSchema.fields[key]?.allowed.orEmpty()
        val chosen = (value as? JsonPrimitive)?.content
        if (allowed.isNotEmpty() && chosen !in allowed) "has $key '$chosen', not one of ${allowed.joinToString("|")}" else null
    }

    private fun tooFewPoints(element: JsonObject, spec: CanvasElementSpec): String? {
        val count = (element["points"] as? JsonArray)?.size ?: 0
        return if (count < spec.minPoints) "needs at least ${spec.minPoints} \"x,y\" points, has $count" else null
    }

    private fun phrase(kind: CanvasFieldKind): String = when (kind) {
        CanvasFieldKind.STRING -> "a string"
        CanvasFieldKind.NUMBER -> "a number"
        CanvasFieldKind.INTEGER, CanvasFieldKind.LONG -> "a whole number"
        CanvasFieldKind.BOOLEAN -> "true or false"
        CanvasFieldKind.COLOR -> "a colour \"#rrggbbaa\""
        CanvasFieldKind.POINT -> "an \"x,y\" string"
        CanvasFieldKind.POINT_LIST -> "an array of \"x,y\" strings"
        CanvasFieldKind.SAMPLE_LIST -> "an array of \"x,y,width\" strings"
    }
}
