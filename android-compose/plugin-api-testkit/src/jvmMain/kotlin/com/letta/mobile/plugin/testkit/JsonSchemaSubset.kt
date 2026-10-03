package com.letta.mobile.plugin.testkit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A JSON pointer (RFC 6901) into the value being checked; the root is the empty pointer. */
@JvmInline
internal value class JsonPointer(private val path: String) {
    fun child(segment: Any): JsonPointer = JsonPointer("$path/$segment")

    override fun toString(): String = path.ifEmpty { "(root)" }

    companion object {
        val ROOT = JsonPointer("")
    }
}

/** The numeric keywords of the subset, each read from a schema as a bound. */
internal enum class SchemaBound(val keyword: String) {
    MINIMUM("minimum"),
    MAXIMUM("maximum"),
    MIN_LENGTH("minLength"),
    MAX_LENGTH("maxLength"),
    MAX_ITEMS("maxItems"),
    ;

    fun of(schema: JsonObject): Double? = (schema[keyword] as? JsonPrimitive)?.doubleOrNull
}

/** One value under the schema it must hold, at [at]. */
internal class SchemaNode(val schema: JsonObject, val value: JsonElement, val at: JsonPointer) {
    val type: SchemaType? get() = SchemaType.of((schema["type"] as? JsonPrimitive)?.content)

    val properties: JsonObject get() = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())

    val required: List<String> get() = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    val closed: Boolean get() = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false

    fun bound(bound: SchemaBound): Double? = bound.of(schema)

    fun problem(what: String): String = "$at: $what"
}

/**
 * The JSON-Schema vocabulary manifests use for action inputs and element props (`type`, `enum`,
 * `properties`, `required`, `additionalProperties: false`, `items`, `minimum`/`maximum`,
 * `minLength`/`maxLength`, `maxItems`), enough for the kit to check a value and to build a valid
 * sample. Problems are `<pointer>: <what>` lines.
 */
internal object JsonSchemaSubset {
    fun problems(schema: JsonObject, value: JsonElement, at: JsonPointer = JsonPointer.ROOT): List<String> =
        problems(SchemaNode(schema, value, at))

    /** A value valid against [schema]: required fields filled in, the first `enum` value, numbers and lengths in bounds. */
    fun sample(schema: JsonObject): JsonElement = sampleOf(SchemaNode(schema, JsonNull, JsonPointer.ROOT))

    private fun problems(node: SchemaNode): List<String> {
        enumProblem(node)?.let { return listOf(it) }
        val type = node.type ?: return emptyList()
        if (!type.matches(node.value)) return listOf(node.problem("expected $type"))
        return when (type) {
            SchemaType.OBJECT -> objectProblems(node)
            SchemaType.ARRAY -> arrayProblems(node)
            SchemaType.STRING -> stringProblems(node)
            SchemaType.INTEGER, SchemaType.NUMBER -> numberProblems(node)
            SchemaType.BOOLEAN, SchemaType.NULL -> emptyList()
        }
    }

    private fun sampleOf(node: SchemaNode): JsonElement {
        (node.schema["enum"] as? JsonArray)?.firstOrNull()?.let { return it }
        return when (node.type) {
            SchemaType.OBJECT -> JsonObject(node.properties.filterKeys { it in node.required }.mapValues { (name, child) -> sampleOf(node.child(name, child)) })
            SchemaType.ARRAY -> JsonArray(emptyList())
            SchemaType.STRING -> JsonPrimitive("x".repeat(node.bound(SchemaBound.MIN_LENGTH)?.toInt() ?: 1))
            SchemaType.INTEGER -> JsonPrimitive(node.bound(SchemaBound.MINIMUM)?.toLong() ?: 0L)
            SchemaType.NUMBER -> JsonPrimitive(node.bound(SchemaBound.MINIMUM) ?: 0.0)
            SchemaType.BOOLEAN -> JsonPrimitive(false)
            SchemaType.NULL, null -> JsonNull
        }
    }

    private fun SchemaNode.child(name: String, childSchema: JsonElement): SchemaNode =
        SchemaNode(childSchema as? JsonObject ?: JsonObject(emptyMap()), (value as? JsonObject)?.get(name) ?: JsonNull, at.child(name))

    private fun enumProblem(node: SchemaNode): String? {
        val allowed = node.schema["enum"] as? JsonArray ?: return null
        return node.problem("${node.value} is not one of $allowed").takeIf { node.value !in allowed }
    }

    private fun objectProblems(node: SchemaNode): List<String> {
        val value = node.value as JsonObject
        val missing = node.required.filter { it !in value }.map { node.at.child(it).toString() + ": is required" }
        val unknown = if (node.closed) value.keys.filter { it !in node.properties }.map { "${node.at.child(it)}: is not a declared field" } else emptyList()
        val nested = value.keys.filter { it in node.properties }.flatMap { name -> problems(node.child(name, node.properties.getValue(name))) }
        return missing + unknown + nested
    }

    private fun arrayProblems(node: SchemaNode): List<String> {
        val value = node.value as JsonArray
        val tooMany = node.bound(SchemaBound.MAX_ITEMS)?.let { max -> node.problem("more than ${max.toInt()} items").takeIf { value.size > max } }
        val items = node.schema["items"] as? JsonObject
        val nested = items?.let { itemSchema -> value.flatMapIndexed { index, item -> problems(SchemaNode(itemSchema, item, node.at.child(index))) } }
        return listOfNotNull(tooMany) + nested.orEmpty()
    }

    private fun stringProblems(node: SchemaNode): List<String> {
        val length = node.value.jsonPrimitive.content.length
        return listOfNotNull(
            node.bound(SchemaBound.MAX_LENGTH)?.let { max -> node.problem("longer than ${max.toInt()}").takeIf { length > max } },
            node.bound(SchemaBound.MIN_LENGTH)?.let { min -> node.problem("shorter than ${min.toInt()}").takeIf { length < min } },
        )
    }

    private fun numberProblems(node: SchemaNode): List<String> {
        val number = node.value.jsonPrimitive.doubleOrNull ?: return emptyList()
        return listOfNotNull(
            node.bound(SchemaBound.MAXIMUM)?.let { max -> node.problem("above $max").takeIf { number > max } },
            node.bound(SchemaBound.MINIMUM)?.let { min -> node.problem("below $min").takeIf { number < min } },
        )
    }
}

/** The schema `type`s of the subset, and whether a value has one. */
internal enum class SchemaType(val word: String) {
    OBJECT("object") {
        override fun matches(value: JsonElement): Boolean = value is JsonObject
    },
    ARRAY("array") {
        override fun matches(value: JsonElement): Boolean = value is JsonArray
    },
    STRING("string") {
        override fun matches(value: JsonElement): Boolean = value.scalar()?.isString == true
    },
    BOOLEAN("boolean") {
        override fun matches(value: JsonElement): Boolean = value.nonString()?.booleanOrNull != null
    },
    INTEGER("integer") {
        override fun matches(value: JsonElement): Boolean = value.nonString()?.longOrNull != null
    },
    NUMBER("number") {
        override fun matches(value: JsonElement): Boolean = value.nonString()?.doubleOrNull != null
    },
    NULL("null") {
        override fun matches(value: JsonElement): Boolean = value is JsonNull
    },
    ;

    abstract fun matches(value: JsonElement): Boolean

    override fun toString(): String = word

    companion object {
        fun of(word: String?): SchemaType? = entries.firstOrNull { it.word == word }

        private fun JsonElement.scalar(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

        private fun JsonElement.nonString(): JsonPrimitive? = scalar()?.takeIf { !it.isString }
    }
}
