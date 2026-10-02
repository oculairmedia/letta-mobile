package com.letta.mobile.data.canvas.plugin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The props schemas of the plugin kinds a host knows, by element type and schema version (plan
 * section 4.3, letta-mobile-s416w.2).
 *
 * The validator asks it: a kind it knows has its props held to the schema, and a kind it does not
 * know is accepted on the envelope alone with a WARN (D11), so a board relayed by a host without
 * the plugin still converges. [Empty] knows no kinds.
 */
fun interface PluginKindCatalog {
    /** The props schema of [type] at version [v], or null when this host does not know that kind at that version. */
    fun schemaFor(type: String, v: Int): JsonObject?

    /** Whether this host has the plugin [pluginId] at all, whatever its kinds and their versions. */
    fun knows(pluginId: String): Boolean = false

    companion object {
        val Empty: PluginKindCatalog = PluginKindCatalog { _, _ -> null }
    }
}

/** The size a kind is placed at when its writer names no frame, in world units. */
data class PluginKindSize(val width: Float, val height: Float)

/**
 * One element kind of a plugin at one schema version: what the manifest's `elements` map declares
 * (plan section 3.1). [propsSchema] is a closed object schema of [com.letta.mobile.data.schema.JsonSchemaCheck]'s
 * vocabulary.
 */
data class PluginKindSpec(
    val elementType: String,
    val schemaVersion: Int,
    val propsSchema: JsonObject,
    val defaultSize: PluginKindSize? = null,
) {
    /** The plugin that declares the kind: `letta.example` of `ext:letta.example/widget`. */
    val pluginId: String get() = elementType.removePrefix(CanvasPluginElement.TYPE_PREFIX).substringBefore('/')

    /** Every reason this spec cannot be registered; empty when it can. */
    fun problems(): List<String> = listOfNotNull(
        "'$elementType' is not ext:<pluginId>/<kind>".takeIf { !CanvasPluginElementSchema.TYPE_PATTERN.matches(elementType) },
        "$elementType schemaVersion is $schemaVersion; versions start at 1".takeIf { schemaVersion < 1 },
        "$elementType props schema is not a closed object (type object, additionalProperties false)".takeIf { !propsSchema.isClosedObject() },
    )

    private fun JsonObject.isClosedObject(): Boolean =
        this["type"]?.jsonPrimitive?.content == "object" && (this["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
}

/**
 * A [PluginKindCatalog] over a fixed set of [PluginKindSpec]s: a test's, or the kinds of the plugins
 * a host has installed. A spec that cannot be registered, or two specs of one kind at one version,
 * refuse the whole catalog.
 */
class InMemoryPluginKindCatalog(specs: List<PluginKindSpec>) : PluginKindCatalog {
    private val byKind: Map<Pair<String, Int>, PluginKindSpec> = specs.associateBy { it.elementType to it.schemaVersion }
    private val plugins: Set<String> = specs.mapTo(mutableSetOf()) { it.pluginId }

    init {
        val problems = specs.flatMap { it.problems() }
        require(problems.isEmpty()) { "Cannot register plugin kinds: ${problems.joinToString("; ")}" }
        val repeated = specs.groupBy { it.elementType to it.schemaVersion }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "Plugin kinds registered twice: ${repeated.joinToString { (type, v) -> "$type v$v" }}" }
    }

    /** The spec of [type] at version [v], or null. */
    fun spec(type: String, v: Int): PluginKindSpec? = byKind[type to v]

    override fun schemaFor(type: String, v: Int): JsonObject? = spec(type, v)?.propsSchema

    override fun knows(pluginId: String): Boolean = pluginId in plugins

    companion object {
        /**
         * The kinds a manifest's `elements` map declares for [pluginId] (plan section 3.1): each
         * entry's current `schemaVersion`, `props` schema and `defaultSize`. A kind missing its
         * `props` schema is declared with none, which [PluginKindSpec.problems] refuses.
         */
        fun specsOf(pluginId: String, elements: JsonObject): List<PluginKindSpec> = elements.map { (kind, declared) ->
            val entry = declared as? JsonObject ?: JsonObject(emptyMap())
            PluginKindSpec(
                elementType = "${CanvasPluginElement.TYPE_PREFIX}$pluginId/$kind",
                schemaVersion = (entry["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 1,
                propsSchema = entry["props"] as? JsonObject ?: JsonObject(emptyMap()),
                defaultSize = (entry["defaultSize"] as? JsonObject)?.let(::sizeOf),
            )
        }

        private fun sizeOf(size: JsonObject): PluginKindSize? {
            val width = (size["width"] as? JsonPrimitive)?.floatOrNull ?: return null
            val height = (size["height"] as? JsonPrimitive)?.floatOrNull ?: return null
            return PluginKindSize(width, height)
        }
    }
}
