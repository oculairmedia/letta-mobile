package com.letta.mobile.data.a2ui

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

@Immutable
data class A2uiAction(
    val name: String,
    val surfaceId: String,
    val context: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = buildJsonObject {
        put("actionName", name)
        put("name", name)
        put("surfaceId", surfaceId)
        put("context", context)
    },
)

fun resolveA2uiActionContext(
    context: JsonElement?,
    dataModel: A2uiDataModel,
): JsonObject = when (context) {
    null -> JsonObject(emptyMap())
    is JsonObject -> resolveContextObject(context, dataModel)
    is JsonArray -> resolveContextPairs(context, dataModel)
    else -> JsonObject(emptyMap())
}

private fun resolveContextObject(
    context: JsonObject,
    dataModel: A2uiDataModel,
): JsonObject = buildJsonObject {
    context.forEach { (key, value) ->
        put(key, resolveContextValue(value, dataModel))
    }
}

private fun resolveContextPairs(
    context: JsonArray,
    dataModel: A2uiDataModel,
): JsonObject = buildJsonObject {
    context.forEach { element ->
        val pair = element as? JsonObject ?: return@forEach
        val key = pair.stringValue("key", "name")?.takeIf { it.isNotBlank() } ?: return@forEach
        val value = pair["value"]
            ?: pair["binding"]
            ?: pair["path"]?.let { buildJsonObject { put("path", it) } }
            ?: pair["literalString"]?.let { buildJsonObject { put("literalString", it) } }
            ?: pair["literal"]?.let { buildJsonObject { put("literal", it) } }
            ?: JsonNull
        put(key, resolveContextValue(value, dataModel))
    }
}

private fun resolveContextValue(
    value: JsonElement,
    dataModel: A2uiDataModel,
): JsonElement = when (value) {
    is JsonObject -> when {
        value.isBindingObject() -> when (val resolved = A2uiBindingResolver.resolve(value, dataModel)) {
            A2uiResolvedBinding.Missing -> JsonNull
            is A2uiResolvedBinding.Value -> resolved.value
        }
        else -> buildJsonObject {
            value.forEach { (key, nestedValue) ->
                put(key, resolveContextValue(nestedValue, dataModel))
            }
        }
    }
    is JsonArray -> JsonArray(value.map { resolveContextValue(it, dataModel) })
    else -> value
}

private fun JsonObject.isBindingObject(): Boolean =
    "path" in this || "literalString" in this || "literal" in this || "value" in this

private fun JsonObject.stringValue(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull
    }
