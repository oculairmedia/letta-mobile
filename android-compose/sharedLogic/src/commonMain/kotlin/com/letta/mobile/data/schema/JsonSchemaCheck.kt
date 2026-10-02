package com.letta.mobile.data.schema

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

/** What a [SchemaProblem] is, for the problems the generic check finds itself. */
enum class SchemaProblemCode {
    WRONG_TYPE,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    TOO_MANY_ITEMS,
    TOO_FEW_ITEMS,
    TOO_LONG,
    TOO_SHORT,
    NOT_ALLOWED,
    OUT_OF_RANGE,
    BAD_PATTERN,
    NO_BRANCH,
}

/**
 * One place an instance breaks its schema: [path] is a JSON pointer (RFC 6901) into the instance,
 * `/items/2/markdown`. [code] is a [SchemaProblemCode] name, or a caller's own code when its
 * [JsonSchemaHooks] word a problem in its own terms.
 */
data class SchemaProblem(val path: String, val code: String, val message: String) {
    constructor(path: String, code: SchemaProblemCode, message: String) : this(path, code.name, message)
}

/** A [value] held to the [schema] it sits under, at [path] (a JSON pointer). */
class SchemaNode(val schema: JsonObject, val value: JsonElement, val path: String) {
    /** RFC 6901: `~` and `/` in a field name are escaped. */
    fun pointer(name: String): String = "$path/" + name.replace("~", "~0").replace("/", "~1")

    /** The field this node is, the last segment of [path]. */
    val field: String get() = path.substringAfterLast('/')
}

/**
 * Where a caller words two problems in its own terms: a string that does not match its `pattern`
 * (a colour, a key), and a discriminated `anyOf` branch nobody declared. The defaults are generic.
 */
interface JsonSchemaHooks {
    fun badPattern(node: SchemaNode, text: String): SchemaProblem =
        SchemaProblem(node.path, SchemaProblemCode.BAD_PATTERN, "'$text' does not match ${node.schema["pattern"]?.jsonPrimitive?.content}")

    fun unknownBranch(node: SchemaNode, discriminator: String, value: JsonElement, allowed: List<String>): SchemaProblem =
        SchemaProblem(node.pointer(discriminator), SchemaProblemCode.NO_BRANCH, "${JsonSchemaText.quoted(value)} is not a $discriminator; use ${JsonSchemaText.orList(allowed)}")

    companion object {
        val Default: JsonSchemaHooks = object : JsonSchemaHooks {}
    }
}

/**
 * A strict subset of JSON Schema, lifted from `canvas_compose` (letta-mobile-s416w.2) so every
 * contract built from schemas (compose requests, plugin kind props, plugin manifests) refuses with
 * JSON-pointer paths the same way.
 *
 * The vocabulary is: `type` (object, array, string, boolean, integer, number, null), `properties`,
 * `required`, `additionalProperties: false`, `enum`, `pattern`, `minLength`/`maxLength`,
 * `minItems`/`maxItems`, `items`, `minimum`/`maximum`, and `anyOf`. An `anyOf` is told apart by
 * [discriminator], a field each branch fixes to one value with a one-value `enum` (`kind`), so a
 * problem is reported against the branch that was meant; without one, the instance must match
 * some branch. Keywords outside the vocabulary are ignored.
 */
class JsonSchemaCheck(
    private val schema: JsonObject,
    private val discriminator: String? = null,
    private val hooks: JsonSchemaHooks = JsonSchemaHooks.Default,
) {
    /** Every place [instance] breaks the schema, in document order; empty when it holds. */
    fun check(instance: JsonElement, path: String = ""): List<SchemaProblem> =
        mutableListOf<SchemaProblem>().also { Walk(it).visit(SchemaNode(schema, instance, path)) }

    private inner class Walk(private val out: MutableList<SchemaProblem>) {
        fun visit(node: SchemaNode) {
            val branches = node.schema["anyOf"]?.jsonArray?.map(JsonElement::jsonObject)
            if (branches != null) return visitBranches(node, branches)
            if (!typeMatches(node)) return
            enumProblem(node)?.let { return run { out += it } }
            when (val value = node.value) {
                is JsonObject -> visitObject(node, value)
                is JsonArray -> visitArray(node, value)
                is JsonPrimitive -> visitPrimitive(node, value)
            }
        }

        private fun typeMatches(node: SchemaNode): Boolean {
            val expected = node.schema["type"]?.jsonPrimitive?.content ?: return true
            if (JsonSchemaText.hasType(node.value, expected)) return true
            out += SchemaProblem(
                node.path, SchemaProblemCode.WRONG_TYPE,
                "expected ${JsonSchemaText.article(expected)}, got ${JsonSchemaText.describe(node.value)}",
            )
            return false
        }

        private fun enumProblem(node: SchemaNode): SchemaProblem? {
            val allowed = node.schema["enum"]?.jsonArray?.takeIf { node.value !in it } ?: return null
            return SchemaProblem(node.path, SchemaProblemCode.NOT_ALLOWED, "${node.value} is not one of ${allowed.joinToString()}")
        }

        private fun visitObject(node: SchemaNode, value: JsonObject) {
            val properties = node.schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            requireFields(node, value)
            val closed = (node.schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
            value.forEach { (name, child) ->
                val childSchema = properties[name]?.jsonObject
                when {
                    childSchema != null -> visit(SchemaNode(childSchema, child, node.pointer(name)))
                    closed -> out += SchemaProblem(
                        node.pointer(name), SchemaProblemCode.UNKNOWN_FIELD,
                        "'$name' is not a field here; allowed: ${properties.keys.joinToString()}",
                    )
                }
            }
        }

        private fun requireFields(node: SchemaNode, value: JsonObject) {
            val required = node.schema["required"]?.jsonArray ?: return
            required.map { it.jsonPrimitive.content }.filter { it !in value }.forEach { name ->
                out += SchemaProblem(node.pointer(name), SchemaProblemCode.MISSING_FIELD, "'$name' is required")
            }
        }

        private fun visitArray(node: SchemaNode, value: JsonArray) {
            node.schema.int("maxItems")?.takeIf { value.size > it }?.let { max ->
                out += SchemaProblem(node.path, SchemaProblemCode.TOO_MANY_ITEMS, "at most $max entries here (got ${value.size})")
            }
            node.schema.int("minItems")?.takeIf { value.size < it }?.let { min ->
                out += SchemaProblem(node.path, SchemaProblemCode.TOO_FEW_ITEMS, "at least $min entries here (got ${value.size})")
            }
            val items = node.schema["items"]?.jsonObject ?: return
            value.forEachIndexed { i, child -> visit(SchemaNode(items, child, "${node.path}/$i")) }
        }

        private fun visitPrimitive(node: SchemaNode, value: JsonPrimitive) {
            if (value.isString) return visitString(node, value)
            value.doubleOrNull?.let { number -> rangeProblem(node, number)?.let { out += it } }
        }

        private fun rangeProblem(node: SchemaNode, number: Double): SchemaProblem? {
            val min = node.schema.number("minimum")?.takeIf { number < it }
            val max = node.schema.number("maximum")?.takeIf { number > it }
            return when {
                min != null -> SchemaProblem(node.path, SchemaProblemCode.OUT_OF_RANGE, "${node.value} is under the minimum ${node.schema["minimum"]}")
                max != null -> SchemaProblem(node.path, SchemaProblemCode.OUT_OF_RANGE, "${node.value} is over the maximum ${node.schema["maximum"]}")
                else -> null
            }
        }

        private fun visitString(node: SchemaNode, value: JsonPrimitive) {
            // Characters as Kotlin counts them (UTF-16 units): never more lenient than a code-point count.
            val length = value.content.length
            node.schema.int("maxLength")?.takeIf { length > it }?.let { max ->
                out += SchemaProblem(node.path, SchemaProblemCode.TOO_LONG, "at most $max characters (got $length)")
            }
            node.schema.int("minLength")?.takeIf { length < it }?.let { min ->
                out += SchemaProblem(node.path, SchemaProblemCode.TOO_SHORT, if (min == 1) "must not be empty" else "at least $min characters (got $length)")
            }
            val pattern = node.schema["pattern"]?.jsonPrimitive?.content ?: return
            if (!Regex(pattern).matches(value.content)) out += hooks.badPattern(node, value.content)
        }

        private fun visitBranches(node: SchemaNode, branches: List<JsonObject>) {
            val key = discriminator ?: return visitUndiscriminated(node, branches)
            val value = node.value
            if (value !is JsonObject) {
                out += SchemaProblem(node.path, SchemaProblemCode.WRONG_TYPE, "expected an object, got ${JsonSchemaText.describe(value)}")
                return
            }
            val tag = value[key]
                ?: return run { out += SchemaProblem(node.pointer(key), SchemaProblemCode.MISSING_FIELD, "'$key' is required") }
            val byTag = branches.associateBy { it.tagOf(key) }
            val branch = JsonSchemaText.stringContent(tag)?.let { byTag[it] }
            if (branch != null) return visit(SchemaNode(branch, value, node.path))
            out += hooks.unknownBranch(node, key, tag, byTag.keys.toList())
        }

        /** No discriminator: the value holds when some branch holds it entirely. */
        private fun visitUndiscriminated(node: SchemaNode, branches: List<JsonObject>) {
            val matches = branches.any { JsonSchemaCheck(it, discriminator, hooks).check(node.value, node.path).isEmpty() }
            if (!matches) out += SchemaProblem(node.path, SchemaProblemCode.NO_BRANCH, "matches none of the ${branches.size} allowed shapes")
        }
    }

    private companion object {
        fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.content?.toIntOrNull()

        fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

        fun JsonObject.tagOf(key: String): String =
            this["properties"]!!.jsonObject[key]!!.jsonObject["enum"]!!.jsonArray.single().jsonPrimitive.content
    }
}

/** How the check words values and types in its messages. */
object JsonSchemaText {
    fun hasType(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value !is JsonNull && value.isString
        "boolean" -> value.isScalar() && (value as JsonPrimitive).booleanOrNull != null
        "integer" -> value.isScalar() && (value as JsonPrimitive).content.toLongOrNull() != null
        "number" -> value.isScalar() && (value as JsonPrimitive).doubleOrNull != null
        "null" -> value is JsonNull
        else -> true
    }

    fun describe(value: JsonElement): String = when {
        value is JsonNull -> "null"
        value is JsonObject -> "an object"
        value is JsonArray -> "an array"
        value is JsonPrimitive && value.isString -> "a string"
        else -> value.toString()
    }

    fun article(type: String): String = if (type.first() in "aeiou") "an $type" else "a $type"

    fun orList(names: List<String>): String = names.dropLast(1).joinToString() + " or " + names.last()

    fun stringContent(value: JsonElement): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun quoted(value: JsonElement): String = stringContent(value)?.let { "'$it'" } ?: value.toString()

    private fun JsonElement.isScalar(): Boolean = this is JsonPrimitive && this !is JsonNull && !isString
}
