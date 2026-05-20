package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.Tool
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Build a chat-composer-ready template for invoking [tool]. When [Tool.jsonSchema]
 * carries a non-empty `properties` object (OpenAI tool-calling shape), the
 * template enumerates each property as a typed empty placeholder so the user
 * only fills in values. Otherwise falls back to a flat one-liner.
 *
 * Lives in feature-chat (not designsystem) because the template is chat-domain
 * logic — how a user expresses a tool call — not a reusable visual primitive.
 */
fun buildToolCallTemplate(tool: Tool): String {
    val properties = tool.jsonSchema
        ?.get("properties")
        ?.let { it as? JsonObject }
        ?.takeIf { it.isNotEmpty() }
        ?: return "Call tool: ${tool.name} with parameters: "

    return buildString {
        append("Call tool: ").append(tool.name).append('\n')
        append("Arguments: {")
        properties.entries.forEachIndexed { i, (key, value) ->
            append(if (i == 0) "\n" else ",\n")
            append("  \"").append(key).append("\": ")
            append(placeholderForSchemaValue(value))
        }
        append('\n').append('}')
    }
}

/**
 * Render a placeholder literal for a JSON-schema property descriptor.
 * `{ "type": "string" }` → `""`, `"number" | "integer"` → `0`,
 * `"boolean"` → `false`, `"array"` → `[]`, `"object"` → `{}`,
 * anything else (including missing/non-string `type`) → `""`.
 */
private fun placeholderForSchemaValue(value: JsonElement): String {
    val type = (value as? JsonObject)
        ?.get("type")
        ?.let { it as? JsonPrimitive }
        ?.takeIf { it.isString }
        ?.content
    return when (type) {
        "number", "integer" -> "0"
        "boolean" -> "false"
        "array" -> "[]"
        "object" -> "{}"
        else -> "\"\""
    }
}
