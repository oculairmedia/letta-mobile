package com.letta.mobile.data.plugin

import com.letta.mobile.data.canvas.plugin.CanvasPluginElementSchema
import com.letta.mobile.data.schema.SchemaProblem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A manifest's element kinds and pages (plan sections 3.1 and 4): props schemas that are closed
 * objects of scalars, short strings and bounded arrays of those within
 * 4 KiB, migrations from earlier versions only, pages that exist and live under `pages/`, CSP
 * domains that are origins, page permissions the plugin declares.
 */
internal object PluginContentRules {
    /** The largest props schema a kind may declare, serialised. */
    const val MAX_PROPS_SCHEMA_BYTES: Int = 4096

    /** The longest string a props field may hold: props are small; big content is a snapshot asset. */
    const val MAX_PROPS_STRING_LENGTH: Int = 1024

    /**
     * The most items an array props field may declare (`maxItems`). Arrays hold scalars only, never
     * objects or arrays; the element's serialised props stay within 4 KiB whatever the schema says.
     */
    const val MAX_PROPS_ARRAY_ITEMS: Int = CanvasPluginElementSchema.MAX_PROPS_ARRAY_ITEMS

    private val SCALAR_TYPES = setOf("string", "integer", "number", "boolean")

    fun check(manifest: PluginManifest): List<SchemaProblem> =
        manifest.elements.flatMap { (kind, declared) -> element(manifest, declared, ManifestPointer.of("elements", kind)) } +
            manifest.pages.flatMap { (id, page) -> page(manifest, page, ManifestPointer.of("pages", id)) }

    private fun element(manifest: PluginManifest, declared: PluginElementKind, at: ManifestPointer): List<SchemaProblem> {
        val unknownPage = PluginManifestProblem.UNKNOWN_PAGE.at(at / "page", "no page '${declared.page}' in pages; declare it or drop the reference")
            .takeIf { declared.page != null && declared.page !in manifest.pages }
        return listOfNotNull(unknownPage) + props(declared.props, at / "props") + migrations(declared, at / "migrations")
    }

    private fun props(schema: JsonObject, at: ManifestPointer): List<SchemaProblem> {
        if (!isClosedObject(schema)) return listOf(badProps(at, "props are a closed object: \"type\": \"object\", \"additionalProperties\": false"))
        val size = schema.toString().encodeToByteArray().size
        val tooLarge = PluginManifestProblem.PROPS_SCHEMA_TOO_LARGE.at(at, "the props schema is $size bytes; at most $MAX_PROPS_SCHEMA_BYTES")
            .takeIf { size > MAX_PROPS_SCHEMA_BYTES }
        val fields = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        return listOfNotNull(tooLarge) + fields.mapNotNull { (name, field) -> propsField(field, at / "properties" / name) }
    }

    private fun isClosedObject(schema: JsonObject): Boolean =
        (schema["type"] as? JsonPrimitive)?.content == "object" && (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false

    /** Why one props field is not a scalar, a short string or a bounded array of those, or null when it is. */
    private fun propsField(field: JsonElement, at: ManifestPointer): SchemaProblem? {
        val schema = field as? JsonObject ?: return badProps(at, "a props field is a schema object")
        if ((schema["type"] as? JsonPrimitive)?.content == "array") return arrayField(schema, at)
        return scalarField(schema, at, "each field is a string, integer, number, boolean, enum or array of those")
    }

    /** Why [schema] (a field, or an array's items) is not a scalar or a short string, or null when it is. */
    private fun scalarField(schema: JsonObject, at: ManifestPointer, expected: String): SchemaProblem? {
        val enum = schema["enum"] as? JsonArray
        if (enum != null) {
            return badProps(at / "enum", "an enum props field lists scalars only").takeIf { enum.any { it !is JsonPrimitive || it is JsonNull } }
        }
        val type = (schema["type"] as? JsonPrimitive)?.content
        if (type !in SCALAR_TYPES) return badProps(at, "props hold scalars: $expected")
        return badProps(at / "maxLength", "a string props field declares a maxLength of at most $MAX_PROPS_STRING_LENGTH")
            .takeIf { type == "string" && !isShortString(schema) }
    }

    /** An array field declares `maxItems` within [MAX_PROPS_ARRAY_ITEMS] and scalar `items`. */
    private fun arrayField(schema: JsonObject, at: ManifestPointer): SchemaProblem? {
        val maxItems = (schema["maxItems"] as? JsonPrimitive)?.intOrNull
        if (maxItems == null || maxItems !in 0..MAX_PROPS_ARRAY_ITEMS) {
            return badProps(at / "maxItems", "an array props field declares a maxItems of at most $MAX_PROPS_ARRAY_ITEMS")
        }
        val items = schema["items"] as? JsonObject ?: return badProps(at / "items", "an array props field declares its items' schema")
        return scalarField(items, at / "items", "an array's items are strings, integers, numbers, booleans or an enum")
    }

    private fun isShortString(schema: JsonObject): Boolean =
        ((schema["maxLength"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE) <= MAX_PROPS_STRING_LENGTH

    private fun migrations(declared: PluginElementKind, at: ManifestPointer): List<SchemaProblem> = declared.migrations.flatMap { (from, steps) ->
        val stepsAt = at / from
        val future = PluginManifestProblem.BAD_MIGRATION.at(stepsAt, "migrations are keyed by an earlier version than ${declared.schemaVersion}")
            .takeIf { (from.toIntOrNull() ?: 0) >= declared.schemaVersion }
        listOfNotNull(future) + steps.mapIndexedNotNull { index, step ->
            PluginManifestProblem.BAD_MIGRATION.at(stepsAt / index, "a step is exactly one of rename [from, to], default [field, scalar] or drop field")
                .takeIf { PluginMigrationOp.of(step) == null }
        }
    }

    private fun page(manifest: PluginManifest, page: PluginPage, at: ManifestPointer): List<SchemaProblem> {
        val html = PluginManifestProblem.BAD_PATH.at(at / "html", "a page is an .html file under pages/ inside the package")
            .takeIf { !PluginPackagePaths.isPage(page.html) }
        val csp = listOf("connectDomains" to page.csp.connectDomains, "resourceDomains" to page.csp.resourceDomains, "frameDomains" to page.csp.frameDomains)
            .flatMap { (name, domains) -> PluginManifestRules.origins(domains, at / "csp" / name) }
        val permissions = page.permissions.mapIndexedNotNull { index, permission ->
            PluginManifestRules.missing(permission, at / "permissions" / index).takeIf { permission !in manifest.capabilities }
        }
        return listOfNotNull(html) + csp + permissions + duplicates(page.displayModes, at / "displayModes")
    }

    private fun badProps(at: ManifestPointer, message: String) = PluginManifestProblem.BAD_PROPS_SCHEMA.at(at, message)
}
