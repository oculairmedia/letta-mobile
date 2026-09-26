package com.letta.mobile.data.canvas

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Colours as a writer sends them, made what DrawBox reads: `#rrggbb` gets its alpha. */
internal object CanvasSceneColors {
    private const val BACKGROUND = "bgColor"
    private const val RGB_LENGTH = 7
    private const val OPAQUE = "ff"

    /** The background to publish: the default when there is none, null when it is not a colour. */
    fun background(value: JsonElement?): JsonElement? {
        if (value == null || value is JsonNull) return JsonPrimitive(CanvasSceneSchema.DEFAULT_BG_COLOR)
        return withAlpha(BACKGROUND, value).takeIf { CanvasSceneFieldChecks.accepts(CanvasFieldKind.COLOR, it, strict = true) }
    }

    /** [value] of field [key], with an opaque alpha when it is a colour written as `#rrggbb`. */
    fun withAlpha(key: String, value: JsonElement): JsonElement {
        if (!isColorField(key)) return value
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return value
        return if (isRgb(text)) JsonPrimitive(text + OPAQUE) else value
    }

    private fun isColorField(key: String): Boolean =
        key == BACKGROUND || CanvasSceneSchema.fields[key]?.kind == CanvasFieldKind.COLOR

    private fun isRgb(text: String): Boolean = text.length == RGB_LENGTH && text.startsWith("#")
}
