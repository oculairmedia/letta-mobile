package com.letta.mobile.runtime.local

import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * letta-mobile-by4vm.121.16: tool-call arguments between OpenAI's JSON string and LiteRT-LM's
 * `ToolCall.arguments` (a `Map<String, Any?>` of plain Kotlin values), value for value. LiteRT-LM's
 * own converters are internal to the library.
 */
internal object ToolArgumentsJson {
    private val json = LettaJson.wire

    /** An OpenAI `arguments` string (a JSON object) as LiteRT-LM arguments. */
    fun toArguments(argumentsJson: String): Map<String, Any?> =
        json.parseToJsonElement(argumentsJson).jsonObject.mapValues { (_, value) -> value.toKotlin() }

    /** LiteRT-LM arguments as an OpenAI `arguments` string. */
    fun toJson(arguments: Map<String, Any?>): String = json.encodeToString(JsonElement.serializer(), arguments.toJsonElement())

    private fun JsonElement.toKotlin(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
        is JsonObject -> mapValues { (_, value) -> value.toKotlin() }
        is JsonArray -> map { it.toKotlin() }
    }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
        is Iterable<*> -> JsonArray(map { it.toJsonElement() })
        is Array<*> -> JsonArray(map { it.toJsonElement() })
        else -> JsonPrimitive(toString())
    }
}
