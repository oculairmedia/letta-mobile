package com.letta.mobile.data.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A value that holds a schema of [JsonSchemaCheck]'s vocabulary, built from the schema alone, so a
 * refusal can show what a right answer looks like (`{"status":"idle","progress":0}`) without
 * anyone keeping an example beside the schema. Every declared field is filled in, the first `enum`
 * value is taken, and numbers and lengths keep to their bounds.
 */
object JsonSchemaExample {
    fun of(schema: JsonObject): JsonElement {
        schema["enum"]?.jsonArray?.firstOrNull()?.let { return it }
        schema["anyOf"]?.jsonArray?.firstOrNull()?.let { return of(it.jsonObject) }
        return when (schema["type"]?.jsonPrimitive?.content) {
            "object" -> objectOf(schema)
            "array" -> arrayOf(schema)
            "string" -> stringOf(schema)
            "integer" -> JsonPrimitive(lowest(schema).toLong())
            "number" -> JsonPrimitive(lowest(schema))
            "boolean" -> JsonPrimitive(false)
            else -> JsonNull
        }
    }

    private fun objectOf(schema: JsonObject): JsonObject {
        val properties = schema["properties"]?.jsonObject ?: return JsonObject(emptyMap())
        return JsonObject(properties.mapValues { (_, child) -> of(child.jsonObject) })
    }

    private fun arrayOf(schema: JsonObject): JsonArray {
        val items = schema["items"]?.jsonObject ?: return JsonArray(emptyList())
        val count = (schema["minItems"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return JsonArray(List(count) { of(items) })
    }

    private fun stringOf(schema: JsonObject): JsonPrimitive {
        val max = (schema["maxLength"] as? JsonPrimitive)?.content?.toIntOrNull() ?: SAMPLE.length
        val min = (schema["minLength"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        return JsonPrimitive(SAMPLE.padEnd(min, 'x').take(max.coerceAtLeast(min)))
    }

    private fun lowest(schema: JsonObject): Double = (schema["minimum"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0

    private const val SAMPLE = "text"
}
