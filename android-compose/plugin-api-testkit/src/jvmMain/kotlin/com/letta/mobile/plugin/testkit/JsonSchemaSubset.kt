package com.letta.mobile.plugin.testkit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The JSON-Schema vocabulary manifests use for action inputs and element props (`type`, `enum`,
 * `properties`, `required`, `additionalProperties: false`, `items`, `minimum`/`maximum`,
 * `minLength`/`maxLength`, `maxItems`), enough for the kit to check a value and to build a valid
 * sample. Problems are `<pointer>: <what>` lines.
 */
internal object JsonSchemaSubset {
    fun problems(schema: JsonObject, value: JsonElement, path: String = ""): List<String> {
        enumProblem(schema, value, path)?.let { return listOf(it) }
        val type = typeOf(schema) ?: return emptyList()
        if (!matchesType(type, value)) return listOf("${where(path)}: expected $type")
        return when (type) {
            "object" -> objectProblems(schema, value.jsonObject, path)
            "array" -> arrayProblems(schema, value.jsonArray, path)
            "string" -> lengthProblems(schema, value.jsonPrimitive.content.length, path)
            "integer", "number" -> rangeProblems(schema, value.jsonPrimitive.doubleOrNull ?: 0.0, path)
            else -> emptyList()
        }
    }

    /** A value valid against [schema]: required fields filled in, the first `enum` value, numbers and lengths in bounds. */
    fun sample(schema: JsonObject): JsonElement {
        schema["enum"]?.jsonArray?.firstOrNull()?.let { return it }
        return when (typeOf(schema)) {
            "object" -> JsonObject(properties(schema).filterKeys { it in required(schema) }.mapValues { sample(it.value.jsonObject) })
            "array" -> JsonArray(emptyList())
            "string" -> JsonPrimitive("x".repeat(number(schema, "minLength")?.toInt() ?: 1))
            "integer" -> JsonPrimitive(number(schema, "minimum")?.toLong() ?: 0L)
            "number" -> JsonPrimitive(number(schema, "minimum") ?: 0.0)
            "boolean" -> JsonPrimitive(false)
            else -> JsonNull
        }
    }

    private fun enumProblem(schema: JsonObject, value: JsonElement, path: String): String? {
        val allowed = schema["enum"]?.jsonArray ?: return null
        return "${where(path)}: $value is not one of $allowed".takeIf { value !in allowed }
    }

    /** The pointer, with the root (the empty pointer) spelled out. */
    private fun where(path: String): String = path.ifEmpty { "(root)" }

    private fun typeOf(schema: JsonObject): String? = (schema["type"] as? JsonPrimitive)?.content

    private fun matchesType(type: String, value: JsonElement): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "null" -> value is JsonNull
        else -> value is JsonPrimitive && value !is JsonNull && matchesScalar(type, value)
    }

    private fun matchesScalar(type: String, value: JsonPrimitive): Boolean = when (type) {
        "string" -> value.isString
        "boolean" -> !value.isString && value.booleanOrNull != null
        "integer" -> !value.isString && value.longOrNull != null
        "number" -> !value.isString && value.doubleOrNull != null
        else -> true
    }

    private fun objectProblems(schema: JsonObject, value: JsonObject, path: String): List<String> {
        val properties = properties(schema)
        val missing = required(schema).filter { it !in value }.map { "$path/$it: is required" }
        val closed = schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false
        val unknown = if (closed) value.keys.filter { it !in properties }.map { "$path/$it: is not a declared field" } else emptyList()
        val nested = value.entries.flatMap { (name, child) ->
            properties[name]?.let { problems(it.jsonObject, child, "$path/$name") }.orEmpty()
        }
        return missing + unknown + nested
    }

    private fun arrayProblems(schema: JsonObject, value: JsonArray, path: String): List<String> {
        val tooMany = number(schema, "maxItems")?.let { max -> "$path: more than ${max.toInt()} items".takeIf { value.size > max } }
        val items = schema["items"] as? JsonObject
        val nested = items?.let { itemSchema -> value.flatMapIndexed { index, item -> problems(itemSchema, item, "$path/$index") } }
        return listOfNotNull(tooMany) + nested.orEmpty()
    }

    private fun lengthProblems(schema: JsonObject, length: Int, path: String): List<String> = listOfNotNull(
        number(schema, "maxLength")?.let { max -> "$path: longer than ${max.toInt()}".takeIf { length > max } },
        number(schema, "minLength")?.let { min -> "$path: shorter than ${min.toInt()}".takeIf { length < min } },
    )

    private fun rangeProblems(schema: JsonObject, value: Double, path: String): List<String> = listOfNotNull(
        number(schema, "maximum")?.let { max -> "$path: above $max".takeIf { value > max } },
        number(schema, "minimum")?.let { min -> "$path: below $min".takeIf { value < min } },
    )

    private fun properties(schema: JsonObject): JsonObject = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())

    private fun required(schema: JsonObject): List<String> =
        (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    private fun number(schema: JsonObject, key: String): Double? = (schema[key] as? JsonPrimitive)?.doubleOrNull
}
