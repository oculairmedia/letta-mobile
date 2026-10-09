package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.compose.CanvasComposeContract as Contract
import com.letta.mobile.data.schema.JsonSchemaCheck
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `canvas_compose` input schema, and the check that holds a request to it. The model is given
 * exactly this schema, so a refusal's path (`/items/2/markdown`) points at a place it can see. It
 * is strict at every level (`additionalProperties: false`) and its caps are [CanvasComposeContract]'s.
 *
 * [check] reads the schema rather than restating it: the keywords used here (type, properties,
 * required, additionalProperties, enum, pattern, min/maxLength, min/maxItems, and anyOf told
 * apart by a one-value `kind` enum) are the whole vocabulary, so the schema the model sees and
 * the rules a request is held to cannot drift apart. The check itself is the shared
 * [JsonSchemaCheck], with `kind` telling an item's branches apart and [ComposeSchemaHooks] wording
 * its problems as `canvas_compose` does.
 */
object CanvasComposeSchema {
    internal const val KIND = "kind"

    private val key = StringSchema(
        pattern = Contract.KEY_PATTERN,
        description = "Optional id, unique in the request.",
    ).json()
    private val color = StringSchema(
        pattern = Contract.COLOR_PATTERN,
    ).json()
    private val title = StringSchema(maxLength = Contract.MAX_TITLE_CHARS).json()

    private val note = kindSchema(
        ComposeKind.NOTE,
        required = listOf("markdown"),
        "key" to key,
        "title" to title,
        "markdown" to StringSchema(
            maxLength = Contract.MAX_MARKDOWN_CHARS, minLength = 1,
            description = "Markdown subset: see ${CanvasToolContract.COMPOSE_GUIDE}.",
        ).json(),
        "color" to color,
    )

    private val checklist = kindSchema(
        ComposeKind.CHECKLIST,
        required = listOf("items"),
        "key" to key,
        "title" to title,
        "items" to ArraySchema(
            obj(
                required = listOf("text"),
                "text" to StringSchema(maxLength = Contract.MAX_VALUE_CHARS, minLength = 1).json(),
                "checked" to TypeSchema("boolean").json(),
            ),
            maxItems = Contract.MAX_CHECKLIST_ITEMS,
        ).json(),
        "color" to color,
    )

    private val card = kindSchema(
        ComposeKind.CARD,
        required = listOf("title"),
        "key" to key,
        "title" to StringSchema(maxLength = Contract.MAX_TITLE_CHARS, minLength = 1).json(),
        "fields" to ArraySchema(
            obj(
                required = listOf("label", "value"),
                "label" to StringSchema(maxLength = Contract.MAX_LABEL_CHARS, minLength = 1).json(),
                "value" to StringSchema(maxLength = Contract.MAX_VALUE_CHARS).json(),
            ),
            maxItems = Contract.MAX_CARD_FIELDS,
            minItems = 0,
        ).json(),
        "markdown" to StringSchema(maxLength = Contract.MAX_CARD_BODY_CHARS, description = "Short body under the fields.").json(),
        "color" to color,
    )

    private val text = kindSchema(
        ComposeKind.TEXT,
        required = listOf("text", "size"),
        "key" to key,
        "text" to StringSchema(maxLength = Contract.MAX_TEXT_CHARS, minLength = 1).json(),
        "size" to StringSchema(enum = ComposeTextSize.entries.map { it.name.lowercase() }).json(),
    )

    private val group = kindSchema(
        ComposeKind.GROUP,
        required = listOf("children"),
        "key" to key,
        "label" to StringSchema(maxLength = Contract.MAX_LABEL_CHARS).json(),
        "children" to ArraySchema(
            anyOf(note, checklist, card, text),
            maxItems = Contract.MAX_ITEMS - 1,
            description = "A GROUP cannot hold a GROUP.",
        ).json(),
    )

    /** The schema of one top-level item, any of the five kinds. */
    val item: JsonObject = anyOf(note, checklist, card, text, group)

    /** The whole `canvas_compose` input. */
    val input: JsonObject = obj(
        required = listOf("items"),
        "catalog" to StringSchema(enum = listOf(Contract.CATALOG)).json(),
        "version" to TypeSchema("integer", enum = Contract.SUPPORTED_VERSIONS.map { JsonPrimitive(it) }).json(),
        "canvas_id" to StringSchema(description = CanvasToolContract.CANVAS_ID_DESCRIPTION).json(),
        "artifact_id" to StringSchema(
            pattern = Contract.ARTIFACT_ID_PATTERN,
            description = "Optional id for the whole artifact; resending the same id and content is a safe retry.",
        ).json(),
        "title" to title,
        "items" to ArraySchema(
            item,
            maxItems = Contract.MAX_ITEMS,
            description = "In reading order; group children count toward the cap.",
        ).json(),
        "dry_run" to TypeSchema("boolean", description = "true to get the receipt without publishing.").json(),
    )

    private val checker = JsonSchemaCheck(input, discriminator = KIND, hooks = ComposeSchemaHooks)

    /** Every place [instance] breaks [input], each with the JSON pointer of the offending value. */
    fun check(instance: JsonElement): List<ComposeProblem> = checker.check(instance).map(ComposeSchemaHooks::toCompose)

    // Schema builders. Every object is closed: a field the contract does not name is refused.

    private fun kindSchema(kind: ComposeKind, required: List<String>, vararg properties: Pair<String, JsonObject>): JsonObject =
        obj(required = listOf(KIND) + required, KIND to StringSchema(enum = listOf(kind.name)).json(), *properties)

    private fun obj(required: List<String>, vararg properties: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties.toMap()))
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }

    private fun anyOf(vararg branches: JsonObject): JsonObject = buildJsonObject {
        put("anyOf", JsonArray(branches.toList()))
    }
}

/** A schema of one JSON type, with its allowed values and description when it has them. */
private class TypeSchema(
    private val name: String,
    private val enum: List<JsonPrimitive>? = null,
    private val description: String? = null,
) {
    fun json(extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("type", name)
        description?.let { put("description", it) }
        enum?.let { put("enum", JsonArray(it)) }
        extra()
    }
}

private class StringSchema(
    private val maxLength: Int? = null,
    private val minLength: Int? = null,
    private val pattern: String? = null,
    private val enum: List<String>? = null,
    private val description: String? = null,
) {
    fun json(): JsonObject = TypeSchema("string", enum = enum?.map { JsonPrimitive(it) }, description = description).json {
        minLength?.let { put("minLength", it) }
        maxLength?.let { put("maxLength", it) }
        pattern?.let { put("pattern", it) }
    }
}

private class ArraySchema(
    private val items: JsonObject,
    private val maxItems: Int,
    private val minItems: Int = 1,
    private val description: String? = null,
) {
    fun json(): JsonObject = buildJsonObject {
        put("type", "array")
        description?.let { put("description", it) }
        put("items", items)
        put("minItems", minItems)
        put("maxItems", maxItems)
    }
}
