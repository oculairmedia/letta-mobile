package com.letta.mobile.data.plugin

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
 * objects of scalars and short strings within 4 KiB, migrations from earlier versions only, pages
 * that exist and live under `pages/`, CSP domains that are origins, page permissions the plugin
 * declares.
 */
internal object PluginContentRules {
    /** The largest props schema a kind may declare, serialised. */
    const val MAX_PROPS_SCHEMA_BYTES: Int = 4096

    /** The longest string a props field may hold: props are small and flat; big content is a snapshot asset. */
    const val MAX_PROPS_STRING_LENGTH: Int = 1024

    private val SCALAR_TYPES = setOf("string", "integer", "number", "boolean")

    fun check(manifest: PluginManifest): List<SchemaProblem> =
        manifest.elements.flatMap { (kind, declared) -> element(manifest, kind, declared) } +
            manifest.pages.flatMap { (id, page) -> page(manifest, id, page) }

    private fun element(manifest: PluginManifest, kind: String, declared: PluginElementKind): List<SchemaProblem> {
        val path = pointer("elements", kind)
        val unknownPage = problem("$path/page", PluginManifestProblem.UNKNOWN_PAGE, "no page '${declared.page}' in pages; declare it or drop the reference")
            .takeIf { declared.page != null && declared.page !in manifest.pages }
        return listOfNotNull(unknownPage) + props(declared.props, "$path/props") + migrations(declared, "$path/migrations")
    }

    private fun props(schema: JsonObject, path: String): List<SchemaProblem> {
        val closed = (schema["type"] as? JsonPrimitive)?.content == "object" && (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
        if (!closed) return listOf(badProps(path, "props are a closed object: \"type\": \"object\", \"additionalProperties\": false"))
        val size = schema.toString().encodeToByteArray().size
        val tooLarge = problem(path, PluginManifestProblem.PROPS_SCHEMA_TOO_LARGE, "the props schema is $size bytes; at most $MAX_PROPS_SCHEMA_BYTES")
            .takeIf { size > MAX_PROPS_SCHEMA_BYTES }
        val fields = (schema["properties"] as? JsonObject).orEmpty()
        return listOfNotNull(tooLarge) + fields.mapNotNull { (name, field) -> propsField(field, "$path/properties" + pointer(name)) }
    }

    /** Why one props field is not a scalar or a short string, or null when it is. */
    private fun propsField(field: JsonElement, path: String): SchemaProblem? {
        val schema = field as? JsonObject ?: return badProps(path, "a props field is a schema object")
        val enum = schema["enum"] as? JsonArray
        if (enum != null) {
            return badProps("$path/enum", "an enum props field lists scalars only").takeIf { enum.any { it !is JsonPrimitive || it is JsonNull } }
        }
        val type = (schema["type"] as? JsonPrimitive)?.content
        if (type !in SCALAR_TYPES) return badProps(path, "props are flat: each field is a string, integer, number, boolean or enum")
        val maxLength = (schema["maxLength"] as? JsonPrimitive)?.intOrNull
        return badProps("$path/maxLength", "a string props field declares a maxLength of at most $MAX_PROPS_STRING_LENGTH")
            .takeIf { type == "string" && (maxLength == null || maxLength > MAX_PROPS_STRING_LENGTH) }
    }

    private fun migrations(declared: PluginElementKind, path: String): List<SchemaProblem> = declared.migrations.flatMap { (from, steps) ->
        val stepsPath = path + pointer(from)
        val future = problem(stepsPath, PluginManifestProblem.BAD_MIGRATION, "migrations are keyed by an earlier version than ${declared.schemaVersion}")
            .takeIf { (from.toIntOrNull() ?: 0) >= declared.schemaVersion }
        listOfNotNull(future) + steps.mapIndexedNotNull { index, step ->
            problem("$stepsPath/$index", PluginManifestProblem.BAD_MIGRATION, "a step is exactly one of rename [from, to], default [field, scalar] or drop field")
                .takeIf { PluginMigrationOp.of(step) == null }
        }
    }

    private fun page(manifest: PluginManifest, id: String, page: PluginPage): List<SchemaProblem> {
        val path = pointer("pages", id)
        val html = problem("$path/html", PluginManifestProblem.BAD_PATH, "a page is an .html file under pages/ inside the package")
            .takeIf { !PluginPackagePaths.isPage(page.html) }
        val csp = listOf("connectDomains" to page.csp.connectDomains, "resourceDomains" to page.csp.resourceDomains, "frameDomains" to page.csp.frameDomains)
            .flatMap { (name, domains) -> PluginManifestRules.origins(domains, "$path/csp/$name") }
        val permissions = page.permissions.mapIndexedNotNull { index, permission ->
            PluginManifestRules.missing(permission, "$path/permissions/$index").takeIf { permission !in manifest.capabilities }
        }
        return listOfNotNull(html) + csp + permissions + duplicates(page.displayModes, "$path/displayModes")
    }

    private fun badProps(path: String, message: String) = problem(path, PluginManifestProblem.BAD_PROPS_SCHEMA, message)

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
}
