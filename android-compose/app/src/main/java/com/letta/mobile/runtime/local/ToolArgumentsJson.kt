package com.letta.mobile.runtime.local

import com.google.ai.edge.litertlm.ToolCall
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
 * letta-mobile-by4vm.121.16: the boundary adapter between OpenAI tool calls (arguments as a JSON
 * string) and LiteRT-LM's [ToolCall], whose `arguments` the library types as `Map<String, Any?>` of
 * plain Kotlin values. Untyped values exist only inside this adapter, value for value; everything
 * outside it sees [OnDeviceToolCall] and JSON. LiteRT-LM's own converters are internal to the library.
 */
internal object ToolArgumentsJson {
    private val json = LettaJson.wire

    /** An OpenAI call (its `arguments` a JSON object string) as a LiteRT-LM call. */
    fun toLiteRt(call: OnDeviceToolCall, argumentsJson: String): ToolCall =
        ToolCall(call.name, json.parseToJsonElement(argumentsJson).jsonObject.mapValues { (_, value) -> value.toKotlin() })

    /** A LiteRT-LM call as an OpenAI call (no id yet: the bridge mints it). */
    fun fromLiteRt(call: ToolCall): OnDeviceToolCall = OnDeviceToolCall(
        id = null,
        name = call.name,
        argumentsJson = json.encodeToString(JsonElement.serializer(), call.arguments.toJsonElement()),
    )

    // LiteRT-LM's ToolCall.arguments is Map<String, Any?>: these two are the only untyped values.
    @Suppress("NoAnyType")
    private fun JsonElement.toKotlin(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
        is JsonObject -> mapValues { (_, value) -> value.toKotlin() }
        is JsonArray -> map { it.toKotlin() }
    }

    @Suppress("NoAnyType")
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
