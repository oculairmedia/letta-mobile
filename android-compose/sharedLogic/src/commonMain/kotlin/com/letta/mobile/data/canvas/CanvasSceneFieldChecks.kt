package com.letta.mobile.data.canvas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** This value as a JSON string's content; null when absent or not a string. */
internal fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Whether an element field's value is one DrawBox's decoder reads. [strict] is what a writer is
 * held to (the host, for an agent); lenient is only what would make the decoder throw, which is
 * what the apps check before drawing a scene already in the log.
 */
internal object CanvasSceneFieldChecks {
    fun accepts(kind: CanvasFieldKind, value: JsonElement, strict: Boolean): Boolean = when (kind) {
        CanvasFieldKind.STRING -> value.string() != null
        CanvasFieldKind.NUMBER -> value.number() != null
        CanvasFieldKind.INTEGER -> value.literal()?.toIntOrNull() != null
        CanvasFieldKind.LONG -> value.literal()?.toLongOrNull() != null
        CanvasFieldKind.BOOLEAN -> value.literal() in BOOLEANS
        CanvasFieldKind.COLOR -> value.string()?.let { color(it, strict) } == true
        CanvasFieldKind.POINT -> value.string()?.let { tuple(it, sizes = POINT, strict = strict) } == true
        CanvasFieldKind.POINT_LIST -> list(value) { tuple(it, sizes = POINT, strict = strict) }
        CanvasFieldKind.SAMPLE_LIST -> list(value) { tuple(it, sizes = SAMPLE, strict = strict) }
    }

    /** A colour as DrawBox parses it: `#rrggbbaa`. Lenient accepts any other length, drawn black. */
    private fun color(value: String, strict: Boolean): Boolean {
        val hex = value.removePrefix("#")
        if (hex.length != COLOR_DIGITS) return !strict
        return (strict.not() || value.startsWith("#")) && hex.all { it.isHexDigit() }
    }

    /**
     * Comma-separated numbers. DrawBox parses every part of a tuple of an expected size, throwing on
     * a bad one; a tuple of another size is read as the origin, which only [strict] refuses.
     */
    private fun tuple(value: String, sizes: IntRange, strict: Boolean): Boolean {
        val parts = value.split(",")
        if (parts.size !in sizes) return !strict
        return parts.withIndex().all { (index, part) -> part.toFloatOrNull() != null || isOptionalTilt(sizes, index) }
    }

    /** A path sample's tilt and azimuth are read leniently (`_` or anything unparsable is absent). */
    private fun isOptionalTilt(sizes: IntRange, index: Int): Boolean = sizes == SAMPLE && index >= TILT

    private fun list(value: JsonElement, item: (String) -> Boolean): Boolean =
        (value as? JsonArray)?.all { element -> element.string()?.let(item) == true } == true

    private fun JsonElement.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** An unquoted, non-null literal: DrawBox's decoder takes numbers only as such. */
    private fun JsonElement.literal(): String? = (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content

    private fun JsonElement.number(): Double? = literal()?.toDoubleOrNull()

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private const val COLOR_DIGITS = 8
    private const val TILT = 3
    private val POINT = 2..2
    private val SAMPLE = 2..5
    private val BOOLEANS = setOf("true", "false")
}
