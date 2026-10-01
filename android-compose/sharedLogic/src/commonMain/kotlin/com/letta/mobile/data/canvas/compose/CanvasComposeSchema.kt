package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.compose.CanvasComposeContract as Contract
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The `canvas.compose` input schema, and the check that holds a request to it. The model is given
 * exactly this schema, so a refusal's path (`/items/2/markdown`) points at a place it can see. It
 * is strict at every level (`additionalProperties: false`) and its caps are [CanvasComposeContract]'s.
 *
 * [check] reads the schema rather than restating it: the keywords used here (type, properties,
 * required, additionalProperties, enum, pattern, min/maxLength, min/maxItems, and anyOf told
 * apart by a one-value `kind` enum) are the whole vocabulary, so the schema the model sees and
 * the rules a request is held to cannot drift apart.
 */
object CanvasComposeSchema {
    private const val KIND = "kind"

    private val key = string(
        pattern = Contract.KEY_PATTERN,
        description = "Optional id for this item, unique in the request: lowercase letters, digits, _ and -, at most 32.",
    )
    private val color = string(
        pattern = Contract.COLOR_PATTERN,
        description = Contract.COLOR_PRESETS.joinToString() + " or #rrggbb.",
    )
    private val title = string(maxLength = Contract.MAX_TITLE_CHARS)

    private val note = kindSchema(
        ComposeKind.NOTE,
        required = listOf("markdown"),
        "key" to key,
        "title" to title,
        "markdown" to string(
            maxLength = Contract.MAX_MARKDOWN_CHARS, minLength = 1,
            description = "The note, in the markdown subset ${CanvasToolContract.COMPOSE_GUIDE} lists.",
        ),
        "color" to color,
    )

    private val checklist = kindSchema(
        ComposeKind.CHECKLIST,
        required = listOf("items"),
        "key" to key,
        "title" to title,
        "items" to array(
            obj(
                required = listOf("text"),
                "text" to string(maxLength = Contract.MAX_VALUE_CHARS, minLength = 1),
                "checked" to type("boolean"),
            ),
            maxItems = Contract.MAX_CHECKLIST_ITEMS,
        ),
        "color" to color,
    )

    private val card = kindSchema(
        ComposeKind.CARD,
        required = listOf("title"),
        "key" to key,
        "title" to string(maxLength = Contract.MAX_TITLE_CHARS, minLength = 1),
        "fields" to array(
            obj(
                required = listOf("label", "value"),
                "label" to string(maxLength = Contract.MAX_LABEL_CHARS, minLength = 1),
                "value" to string(maxLength = Contract.MAX_VALUE_CHARS),
            ),
            maxItems = Contract.MAX_CARD_FIELDS,
            minItems = 0,
        ),
        "markdown" to string(maxLength = Contract.MAX_CARD_BODY_CHARS, description = "A short body under the fields."),
        "color" to color,
    )

    private val text = kindSchema(
        ComposeKind.TEXT,
        required = listOf("text", "size"),
        "key" to key,
        "text" to string(maxLength = Contract.MAX_TEXT_CHARS, minLength = 1),
        "size" to string(enum = ComposeTextSize.entries.map { it.name.lowercase() }),
    )

    private val group = kindSchema(
        ComposeKind.GROUP,
        required = listOf("children"),
        "key" to key,
        "label" to string(maxLength = Contract.MAX_LABEL_CHARS),
        "children" to array(
            anyOf(note, checklist, card, text),
            maxItems = Contract.MAX_ITEMS - 1,
            description = "The grouped items; a GROUP cannot hold a GROUP.",
        ),
    )

    /** The schema of one top-level item, any of the five kinds. */
    val item: JsonObject = anyOf(note, checklist, card, text, group)

    /** The whole `canvas.compose` input. */
    val input: JsonObject = obj(
        required = listOf("items"),
        "catalog" to string(enum = listOf(Contract.CATALOG)),
        "version" to type("integer", enum = Contract.SUPPORTED_VERSIONS.map { JsonPrimitive(it) }),
        "canvas_id" to string(description = CanvasToolContract.CANVAS_ID_DESCRIPTION),
        "artifact_id" to string(
            pattern = Contract.ARTIFACT_ID_PATTERN,
            description = "Optional id for the whole artifact (lowercase, digits, _ and -, at most 48). " +
                "Sending the same id and content again is a safe retry.",
        ),
        "title" to title,
        "items" to array(
            item,
            maxItems = Contract.MAX_ITEMS,
            description = "What to put on the board, in reading order (at most ${Contract.MAX_ITEMS}, group children counted).",
        ),
        "dry_run" to type("boolean", description = "true to check the request and see the receipt without publishing."),
    )

    /** Every place [instance] breaks [input], each with the JSON pointer of the offending value. */
    fun check(instance: JsonElement): List<ComposeProblem> = mutableListOf<ComposeProblem>().also { visit(input, instance, "", it) }

    private fun visit(schema: JsonObject, value: JsonElement, path: String, out: MutableList<ComposeProblem>) {
        schema["anyOf"]?.let { return visitKinds(it.jsonArray.map(JsonElement::jsonObject), value, path, out) }
        val expected = schema["type"]?.jsonPrimitive?.content
        if (expected != null && !hasType(value, expected)) {
            out += ComposeProblem(path, ComposeProblemCode.WRONG_TYPE, "expected ${article(expected)}, got ${describe(value)}")
            return
        }
        when (value) {
            is JsonObject -> visitObject(schema, value, path, out)
            is JsonArray -> visitArray(schema, value, path, out)
            is JsonPrimitive -> visitPrimitive(schema, value, path, out)
        }
    }

    private fun visitObject(schema: JsonObject, value: JsonObject, path: String, out: MutableList<ComposeProblem>) {
        val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.forEach { name ->
            if (name !in value) out += ComposeProblem(pointer(path, name), ComposeProblemCode.MISSING_FIELD, "'$name' is required")
        }
        val closed = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
        value.forEach { (name, child) ->
            val childSchema = properties[name]?.jsonObject
            when {
                childSchema != null -> visit(childSchema, child, pointer(path, name), out)
                closed -> out += ComposeProblem(
                    pointer(path, name), ComposeProblemCode.UNKNOWN_FIELD,
                    "'$name' is not a field here; allowed: ${properties.keys.joinToString()}",
                )
            }
        }
    }

    private fun visitArray(schema: JsonObject, value: JsonArray, path: String, out: MutableList<ComposeProblem>) {
        schema.int("maxItems")?.let { max ->
            if (value.size > max) out += ComposeProblem(path, ComposeProblemCode.TOO_MANY_ITEMS, "at most $max entries here (got ${value.size})")
        }
        schema.int("minItems")?.let { min ->
            if (value.size < min) out += ComposeProblem(path, ComposeProblemCode.BAD_VALUE, "at least $min entries here (got ${value.size})")
        }
        val items = schema["items"]?.jsonObject ?: return
        value.forEachIndexed { i, child -> visit(items, child, "$path/$i", out) }
    }

    private fun visitPrimitive(schema: JsonObject, value: JsonPrimitive, path: String, out: MutableList<ComposeProblem>) {
        schema["enum"]?.jsonArray?.let { allowed ->
            if (value !in allowed) out += ComposeProblem(path, ComposeProblemCode.BAD_VALUE, "$value is not one of ${allowed.joinToString()}")
        }
        if (!value.isString) return
        // Characters as Kotlin counts them (UTF-16 units): never more lenient than a code-point count.
        val length = value.content.length
        schema.int("maxLength")?.let { max ->
            if (length > max) out += ComposeProblem(path, ComposeProblemCode.TOO_LONG, "at most $max characters (got $length)")
        }
        schema.int("minLength")?.let { min ->
            if (length < min) out += ComposeProblem(path, ComposeProblemCode.BAD_VALUE, "must not be empty")
        }
        schema["pattern"]?.jsonPrimitive?.content?.let { pattern ->
            if (!Regex(pattern).matches(value.content)) out += badPattern(path, value.content)
        }
    }

    /** An item: its `kind` picks the branch, so a problem is reported against that kind's fields. */
    private fun visitKinds(branches: List<JsonObject>, value: JsonElement, path: String, out: MutableList<ComposeProblem>) {
        if (value !is JsonObject) {
            out += ComposeProblem(path, ComposeProblemCode.WRONG_TYPE, "expected an object, got ${describe(value)}")
            return
        }
        val kind = value[KIND]
        if (kind == null) {
            out += ComposeProblem(pointer(path, KIND), ComposeProblemCode.MISSING_FIELD, "'kind' is required")
            return
        }
        val name = (kind as? JsonPrimitive)?.takeIf { it.isString }?.content
        val byKind = branches.associateBy { it.kindOf() }
        val branch = name?.let { byKind[it] }
        if (branch != null) return visit(branch, value, path, out)
        val allowed = byKind.keys.toList()
        out += if (name != null && ComposeKind.entries.any { it.name == name }) {
            ComposeProblem(
                pointer(path, KIND), ComposeProblemCode.NESTING_TOO_DEEP,
                "a $name cannot go here; groups nest ${Contract.MAX_GROUP_DEPTH} level deep and hold ${orList(allowed)}",
            )
        } else {
            ComposeProblem(pointer(path, KIND), ComposeProblemCode.UNKNOWN_KIND, "${kind.quoted()} is not a kind; use ${orList(allowed)}")
        }
    }

    private fun badPattern(path: String, value: String): ComposeProblem = when (path.substringAfterLast('/')) {
        "color" -> ComposeProblem(path, ComposeProblemCode.BAD_COLOR, "'$value' is not a colour; use ${Contract.COLOR_PRESETS.joinToString()} or #rrggbb")
        "artifact_id" -> ComposeProblem(path, ComposeProblemCode.BAD_KEY, "'$value' is not an artifact id: lowercase letters, digits, _ and -, at most 48")
        else -> ComposeProblem(path, ComposeProblemCode.BAD_KEY, "'$value' is not a key: lowercase letters, digits, _ and -, at most 32")
    }

    private fun hasType(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value !is JsonNull && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.content.toLongOrNull() != null
        else -> true
    }

    private fun describe(value: JsonElement): String = when {
        value is JsonNull -> "null"
        value is JsonObject -> "an object"
        value is JsonArray -> "an array"
        value is JsonPrimitive && value.isString -> "a string"
        else -> value.toString()
    }

    private fun article(type: String) = if (type.first() in "aeiou") "an $type" else "a $type"

    private fun orList(names: List<String>) = names.dropLast(1).joinToString() + " or " + names.last()

    private fun JsonElement.quoted(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.let { "'${it.content}'" } ?: toString()

    /** RFC 6901: `~` and `/` in a field name are escaped. */
    private fun pointer(path: String, name: String) = "$path/" + name.replace("~", "~0").replace("/", "~1")

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.kindOf(): String =
        this["properties"]!!.jsonObject[KIND]!!.jsonObject["enum"]!!.jsonArray.single().jsonPrimitive.content

    // Schema builders. Every object is closed: a field the contract does not name is refused.

    private fun kindSchema(kind: ComposeKind, required: List<String>, vararg properties: Pair<String, JsonObject>): JsonObject =
        obj(required = listOf(KIND) + required, KIND to string(enum = listOf(kind.name)), *properties)

    private fun obj(required: List<String>, vararg properties: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties.toMap()))
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }

    private fun anyOf(vararg branches: JsonObject): JsonObject = buildJsonObject {
        put("anyOf", JsonArray(branches.toList()))
    }

    private fun array(items: JsonObject, maxItems: Int, minItems: Int = 1, description: String? = null): JsonObject = buildJsonObject {
        put("type", "array")
        description?.let { put("description", it) }
        put("items", items)
        put("minItems", minItems)
        put("maxItems", maxItems)
    }

    private fun string(
        maxLength: Int? = null,
        minLength: Int? = null,
        pattern: String? = null,
        enum: List<String>? = null,
        description: String? = null,
    ): JsonObject = type("string", enum = enum?.map { JsonPrimitive(it) }, description = description) {
        minLength?.let { put("minLength", it) }
        maxLength?.let { put("maxLength", it) }
        pattern?.let { put("pattern", it) }
    }

    private fun type(
        name: String,
        enum: List<JsonPrimitive>? = null,
        description: String? = null,
        extra: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject = buildJsonObject {
        put("type", name)
        description?.let { put("description", it) }
        enum?.let { put("enum", JsonArray(it)) }
        extra()
    }
}
