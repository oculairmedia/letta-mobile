package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.compose.CanvasComposeContract as Contract
import com.letta.mobile.data.schema.JsonSchemaCheck
import com.letta.mobile.data.schema.SchemaProblem
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
    private const val CHILD_DESCRIPTION = "An item of any kind but GROUP, as in items."

    private val key = StringSchema(
        pattern = Contract.KEY_PATTERN,
        description = "Optional id, unique in the request.",
    ).json()
    private val color = StringSchema(
        pattern = Contract.COLOR_PATTERN,
        // Short, but present: a provider that ignores `pattern` must still see the presets.
        description = Contract.COLOR_PRESETS.joinToString() + " or #rrggbb",
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

    private val tone = StringSchema(enum = ComposeTone.entries.map { it.name.lowercase() }).json()

    private val table = kindSchema(
        ComposeKind.TABLE,
        required = listOf("columns", "rows"),
        "key" to key,
        "title" to title,
        "columns" to ArraySchema(
            StringSchema(maxLength = Contract.MAX_CELL_CHARS, minLength = 1).json(),
            maxItems = Contract.MAX_TABLE_COLUMNS,
        ).json(),
        "rows" to ArraySchema(
            ArraySchema(StringSchema(maxLength = Contract.MAX_CELL_CHARS).json(), maxItems = Contract.MAX_TABLE_COLUMNS).json(),
            maxItems = Contract.MAX_TABLE_ROWS,
        ).json(),
        "color" to color,
    )

    private val stat = kindSchema(
        ComposeKind.STAT,
        required = listOf("label", "value"),
        "key" to key,
        "label" to StringSchema(maxLength = Contract.MAX_LABEL_CHARS, minLength = 1).json(),
        "value" to StringSchema(maxLength = Contract.MAX_STAT_VALUE_CHARS, minLength = 1).json(),
        "delta" to StringSchema(maxLength = Contract.MAX_LABEL_CHARS).json(),
        "tone" to tone,
        "caption" to StringSchema(maxLength = Contract.MAX_STAT_NOTE_CHARS).json(),
    )

    private val progress = kindSchema(
        ComposeKind.PROGRESS,
        required = listOf("label", "value"),
        "key" to key,
        "label" to StringSchema(maxLength = Contract.MAX_LABEL_CHARS, minLength = 1).json(),
        "value" to numberSchema(0.0, 1.0),
        "caption" to StringSchema(maxLength = Contract.MAX_STAT_NOTE_CHARS).json(),
        "tone" to tone,
    )

    private val chart = kindSchema(
        ComposeKind.CHART,
        required = listOf("style", "points"),
        "key" to key,
        "title" to title,
        "style" to StringSchema(enum = ComposeChartStyle.entries.map { it.name.lowercase() }).json(),
        "points" to ArraySchema(
            obj(
                required = listOf("label", "value"),
                "label" to StringSchema(maxLength = Contract.MAX_CHART_LABEL_CHARS, minLength = 1).json(),
                "value" to numberSchema(-Contract.MAX_CHART_VALUE, Contract.MAX_CHART_VALUE),
            ),
            maxItems = Contract.MAX_CHART_POINTS,
            minItems = Contract.MIN_CHART_POINTS,
        ).json(),
        "unit" to StringSchema(maxLength = Contract.MAX_CHART_UNIT_CHARS).json(),
    )

    private val group = kindSchema(
        ComposeKind.GROUP,
        required = listOf("children"),
        "key" to key,
        "label" to StringSchema(maxLength = Contract.MAX_LABEL_CHARS).json(),
        // The children's schema is [child], held to each child by [check]: written out here it
        // would repeat every kind's schema in the definition every model request carries.
        "children" to ArraySchema(
            buildJsonObject { put("description", CHILD_DESCRIPTION) },
            maxItems = Contract.MAX_ITEMS - 1,
            description = "A GROUP cannot hold a GROUP.",
        ).json(),
    )

    /** What a GROUP's child may be: any kind but GROUP, one level of nesting. */
    val child: JsonObject = anyOf(note, checklist, card, text, table, stat, progress, chart)

    /** The schema of one top-level item, any of the kinds. */
    val item: JsonObject = anyOf(note, checklist, card, text, table, stat, progress, chart, group)

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
    private val childChecker = JsonSchemaCheck(child, discriminator = KIND, hooks = ComposeSchemaHooks)

    /**
     * Every place [instance] breaks [input], each with the JSON pointer of the offending value: the
     * request against [input], then each GROUP's children against [child].
     */
    fun check(instance: JsonElement): List<ComposeProblem> =
        (checker.check(instance) + childProblems(instance)).map(ComposeSchemaHooks::toCompose)

    private fun childProblems(instance: JsonElement): List<SchemaProblem> {
        val items = ((instance as? JsonObject)?.get("items") as? JsonArray).orEmpty()
        return items.flatMapIndexed { i, item ->
            val group = (item as? JsonObject)?.takeIf { it[KIND] == JsonPrimitive(ComposeKind.GROUP.name) }
            val children = (group?.get("children") as? JsonArray).orEmpty()
            children.flatMapIndexed { c, child -> childChecker.check(child, "/items/$i/children/$c") }
        }
    }

    // Schema builders. Every object is closed: a field the contract does not name is refused.

    private fun kindSchema(kind: ComposeKind, required: List<String>, vararg properties: Pair<String, JsonObject>): JsonObject =
        obj(required = listOf(KIND) + required, KIND to StringSchema(enum = listOf(kind.name)).json(), *properties)

    private fun numberSchema(minimum: Double, maximum: Double): JsonObject = TypeSchema("number").json {
        put("minimum", minimum)
        put("maximum", maximum)
    }

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
