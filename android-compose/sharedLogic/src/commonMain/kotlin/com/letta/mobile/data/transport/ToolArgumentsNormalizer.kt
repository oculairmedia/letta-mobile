package com.letta.mobile.data.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Strips the extra encoding layer some producers put on a tool call's arguments.
 *
 * `tool_call_message` frames have been observed carrying the argument object JSON-encoded twice -
 * `"{\"command\":\"ls\"}"` rather than `{"command":"ls"}` - while the approval-shaped frames for
 * the same tools carry it once. Every consumer that treats arguments as an object then falls back
 * to printing the raw string, which is how a tool row ends up titled with escaped JSON instead of
 * a summary (letta-mobile-s5vf9).
 *
 * One layer comes off, and only when doing so is unambiguous: the text has to parse as a JSON
 * string whose own value parses as an object or an array. A tool whose argument payload is
 * genuinely a string, and a half-accumulated streaming fragment that does not parse yet, both come
 * back untouched.
 */
internal object ToolArgumentsNormalizer {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun normalize(arguments: String?): String? {
        if (arguments.isNullOrBlank()) return arguments
        if (arguments.trimStart().firstOrNull() != '"') return arguments
        val unwrapped = runCatching { json.parseToJsonElement(arguments) }.getOrNull()
            ?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString }
            ?.content
            ?: return arguments
        if (unwrapped.trimStart().firstOrNull() !in setOf('{', '[')) return arguments
        return if (runCatching { json.parseToJsonElement(unwrapped) }.isSuccess) unwrapped else arguments
    }
}
