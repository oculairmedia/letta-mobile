package com.letta.mobile.data.canvas

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put

/** `style` on a connect: only the arrow fields that already exist. */
internal data class ConnectStyle(val strokeColor: String?, val strokeWidth: Float?, val dashed: Boolean?) {
    fun write(json: JsonObjectBuilder) {
        strokeColor?.let { json.put(STROKE_COLOR, it) }
        strokeWidth?.let { json.put(STROKE_WIDTH, it) }
        if (dashed == true) json.put("strokeStyle", "DASHED")
    }

    companion object {
        private const val STROKE_COLOR = "strokeColor"
        private const val STROKE_WIDTH = "strokeWidth"
        private const val DASHED = "dashed"
        private val KEYS = setOf(STROKE_COLOR, STROKE_WIDTH, DASHED)

        /** `#rrggbbaa` after [CanvasSceneColors.withAlpha] has made a `#rrggbb` opaque, as add_element does. */
        private val COLOR = Regex("""#[0-9a-fA-F]{8}""")
        private val EMPTY = ConnectStyle(null, null, null)

        fun read(at: ConnectOpLabel, value: JsonElement?): ConnectStyleRead = when (value) {
            null, JsonNull -> ConnectStyleRead.Ok(EMPTY)
            is JsonObject -> fields(at, value)
            else -> refused(at, ConnectField("style"), "expected an object of strokeColor, strokeWidth and dashed")
        }

        private fun fields(at: ConnectOpLabel, obj: JsonObject): ConnectStyleRead {
            obj.keys.firstOrNull { it !in KEYS }?.let { key -> return refused(at, ConnectField("style.$key"), "unknown style key '$key'") }
            val color = color(obj[STROKE_COLOR])
                ?: return refused(at, ConnectField("style.$STROKE_COLOR"), "expected a colour #rrggbb or #rrggbbaa")
            val width = width(obj[STROKE_WIDTH])
                ?: return refused(at, ConnectField("style.$STROKE_WIDTH"), "expected a number greater than 0")
            val dashed = dashed(obj[DASHED])
                ?: return refused(at, ConnectField("style.$DASHED"), "expected true or false")
            return ConnectStyleRead.Ok(ConnectStyle(color.value, width.value, dashed.value))
        }

        /** Null when present and not a colour; a [Field] holding null when absent. */
        private fun color(value: JsonElement?): Field<String>? {
            if (value == null || value is JsonNull) return Field(null)
            val text = (CanvasSceneColors.withAlpha(STROKE_COLOR, value) as? JsonPrimitive)?.takeIf { it.isString }?.content
            return text?.takeIf { COLOR.matches(it) }?.let { Field(it) }
        }

        private fun width(value: JsonElement?): Field<Float>? {
            if (value == null || value is JsonNull) return Field(null)
            val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull
            return number?.takeIf { it > 0f }?.let { Field(it) }
        }

        private fun dashed(value: JsonElement?): Field<Boolean>? {
            if (value == null || value is JsonNull) return Field(null)
            val primitive = (value as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
            return when (primitive.content) {
                "true" -> Field(true)
                "false" -> Field(false)
                else -> null
            }
        }

        private fun refused(at: ConnectOpLabel, field: ConnectField, detail: String) =
            ConnectStyleRead.Refused(CanvasConnect.refusal(at, field, ConnectDetail(detail)))
    }

    /** A style field that was read: [value] null when the agent left it out. */
    private class Field<T>(val value: T?)
}

internal sealed interface ConnectStyleRead {
    data class Ok(val style: ConnectStyle) : ConnectStyleRead
    data class Refused(val message: String) : ConnectStyleRead
}
