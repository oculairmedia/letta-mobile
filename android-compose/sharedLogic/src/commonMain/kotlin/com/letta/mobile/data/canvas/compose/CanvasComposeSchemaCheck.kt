package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.compose.CanvasComposeContract as Contract
import com.letta.mobile.data.canvas.compose.CanvasComposeSchema.KIND
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A [value] held to the [schema] it sits under, at [path] (a JSON pointer). */
internal class SchemaNode(val schema: JsonObject, val value: JsonElement, val path: String) {
    /** RFC 6901: `~` and `/` in a field name are escaped. */
    fun pointer(name: String): String = "$path/" + name.replace("~", "~0").replace("/", "~1")
}

/**
 * [CanvasComposeSchema.check]: walks a request alongside the schema, adding to [out] every place
 * it breaks it. Reads only the keywords [CanvasComposeSchema] uses.
 */
internal class ComposeSchemaCheck(private val out: MutableList<ComposeProblem>) {
    fun visit(node: SchemaNode) {
        val branches = node.schema["anyOf"]
        if (branches != null) return visitKinds(node, branches.jsonArray.map(JsonElement::jsonObject))
        if (!typeMatches(node)) return
        when (val value = node.value) {
            is JsonObject -> visitObject(node, value)
            is JsonArray -> visitArray(node, value)
            is JsonPrimitive -> visitPrimitive(node, value)
        }
    }

    private fun typeMatches(node: SchemaNode): Boolean {
        val expected = node.schema["type"]?.jsonPrimitive?.content ?: return true
        if (hasType(node.value, expected)) return true
        out += ComposeProblem(node.path, ComposeProblemCode.WRONG_TYPE, "expected ${article(expected)}, got ${describe(node.value)}")
        return false
    }

    private fun visitObject(node: SchemaNode, value: JsonObject) {
        val properties = node.schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        requireFields(node, value)
        val closed = (node.schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
        value.forEach { (name, child) ->
            val childSchema = properties[name]?.jsonObject
            when {
                childSchema != null -> visit(SchemaNode(childSchema, child, node.pointer(name)))
                closed -> out += ComposeProblem(
                    node.pointer(name), ComposeProblemCode.UNKNOWN_FIELD,
                    "'$name' is not a field here; allowed: ${properties.keys.joinToString()}",
                )
            }
        }
    }

    private fun requireFields(node: SchemaNode, value: JsonObject) {
        val required = node.schema["required"]?.jsonArray ?: return
        required.map { it.jsonPrimitive.content }.filter { it !in value }.forEach { name ->
            out += ComposeProblem(node.pointer(name), ComposeProblemCode.MISSING_FIELD, "'$name' is required")
        }
    }

    private fun visitArray(node: SchemaNode, value: JsonArray) {
        node.schema.int("maxItems")?.takeIf { value.size > it }?.let { max ->
            out += ComposeProblem(node.path, ComposeProblemCode.TOO_MANY_ITEMS, "at most $max entries here (got ${value.size})")
        }
        node.schema.int("minItems")?.takeIf { value.size < it }?.let { min ->
            out += ComposeProblem(node.path, ComposeProblemCode.BAD_VALUE, "at least $min entries here (got ${value.size})")
        }
        val items = node.schema["items"]?.jsonObject ?: return
        value.forEachIndexed { i, child -> visit(SchemaNode(items, child, "${node.path}/$i")) }
    }

    private fun visitPrimitive(node: SchemaNode, value: JsonPrimitive) {
        node.schema["enum"]?.jsonArray?.takeIf { value !in it }?.let { allowed ->
            out += ComposeProblem(node.path, ComposeProblemCode.BAD_VALUE, "$value is not one of ${allowed.joinToString()}")
        }
        if (value.isString) visitString(node, value)
    }

    private fun visitString(node: SchemaNode, value: JsonPrimitive) {
        // Characters as Kotlin counts them (UTF-16 units): never more lenient than a code-point count.
        val length = value.content.length
        node.schema.int("maxLength")?.takeIf { length > it }?.let { max ->
            out += ComposeProblem(node.path, ComposeProblemCode.TOO_LONG, "at most $max characters (got $length)")
        }
        node.schema.int("minLength")?.takeIf { length < it }?.let {
            out += ComposeProblem(node.path, ComposeProblemCode.BAD_VALUE, "must not be empty")
        }
        val pattern = node.schema["pattern"]?.jsonPrimitive?.content ?: return
        if (!Regex(pattern).matches(value.content)) out += badPattern(node, value)
    }

    /** An item: its `kind` picks the branch, so a problem is reported against that kind's fields. */
    private fun visitKinds(node: SchemaNode, branches: List<JsonObject>) {
        val value = node.value
        if (value !is JsonObject) {
            out += ComposeProblem(node.path, ComposeProblemCode.WRONG_TYPE, "expected an object, got ${describe(value)}")
            return
        }
        val kind = value[KIND]
            ?: return run { out += ComposeProblem(node.pointer(KIND), ComposeProblemCode.MISSING_FIELD, "'kind' is required") }
        val byKind = branches.associateBy { it.kindOf() }
        val branch = kind.stringContent()?.let { byKind[it] }
        if (branch != null) return visit(SchemaNode(branch, value, node.path))
        out += kindProblem(node, kind, byKind.keys.toList())
    }

    private fun kindProblem(node: SchemaNode, kind: JsonElement, allowed: List<String>): ComposeProblem {
        val name = kind.stringContent()
        if (name != null && ComposeKind.entries.any { it.name == name }) {
            return ComposeProblem(
                node.pointer(KIND), ComposeProblemCode.NESTING_TOO_DEEP,
                "a $name cannot go here; groups nest ${Contract.MAX_GROUP_DEPTH} level deep and hold ${orList(allowed)}",
            )
        }
        return ComposeProblem(node.pointer(KIND), ComposeProblemCode.UNKNOWN_KIND, "${kind.quoted()} is not a kind; use ${orList(allowed)}")
    }

    private fun badPattern(node: SchemaNode, value: JsonPrimitive): ComposeProblem {
        val text = value.content
        return when (node.path.substringAfterLast('/')) {
            "color" -> ComposeProblem(
                node.path, ComposeProblemCode.BAD_COLOR,
                "'$text' is not a colour; use ${Contract.COLOR_PRESETS.joinToString()} or #rrggbb",
            )
            "artifact_id" -> ComposeProblem(
                node.path, ComposeProblemCode.BAD_KEY,
                "'$text' is not an artifact id: lowercase letters, digits, _ and -, at most 48",
            )
            else -> ComposeProblem(node.path, ComposeProblemCode.BAD_KEY, "'$text' is not a key: lowercase letters, digits, _ and -, at most 32")
        }
    }

    private companion object {
        fun hasType(value: JsonElement, type: String): Boolean = when (type) {
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> value is JsonPrimitive && value !is JsonNull && value.isString
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "integer" -> value is JsonPrimitive && !value.isString && value.content.toLongOrNull() != null
            else -> true
        }

        fun describe(value: JsonElement): String = when {
            value is JsonNull -> "null"
            value is JsonObject -> "an object"
            value is JsonArray -> "an array"
            value is JsonPrimitive && value.isString -> "a string"
            else -> value.toString()
        }

        fun article(type: String) = if (type.first() in "aeiou") "an $type" else "a $type"

        fun orList(names: List<String>) = names.dropLast(1).joinToString() + " or " + names.last()

        fun JsonElement.stringContent(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun JsonElement.quoted(): String = stringContent()?.let { "'$it'" } ?: toString()

        fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.content?.toIntOrNull()

        fun JsonObject.kindOf(): String =
            this["properties"]!!.jsonObject[KIND]!!.jsonObject["enum"]!!.jsonArray.single().jsonPrimitive.content
    }
}
