package com.letta.mobile.data.plugin

import com.letta.mobile.data.canvas.plugin.InMemoryPluginKindCatalog
import com.letta.mobile.data.canvas.plugin.PluginKindCatalog
import com.letta.mobile.data.canvas.plugin.PluginKindSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject

/** One element kind as clients see it in `plugins.list` (plan section 4: clients get kinds from the host). */
@Serializable
data class PluginKindSummary(
    val type: String,
    val pluginId: String,
    val pluginName: String,
    val kind: String,
    val schemaVersion: Int,
    val defaultWidth: Float? = null,
    val defaultHeight: Float? = null,
    val page: String? = null,
)

/**
 * How installed plugins' element kinds reach the props validator (plan sections 4 and 6.1): every
 * installed plugin's kinds, at their current schema version, through the same
 * [InMemoryPluginKindCatalog.specsOf] the manifest fixture is read with (letta-mobile-s416w.2). A
 * disabled plugin's kinds stay known, so its elements on boards are still held to their schema.
 */
object PluginKinds {
    fun specsOf(manifest: PluginManifest): List<PluginKindSpec> =
        InMemoryPluginKindCatalog.specsOf(manifest.id, elementsJson(manifest))

    fun catalogOf(state: PluginRegistryState): PluginKindCatalog =
        InMemoryPluginKindCatalog(state.plugins.flatMap { specsOf(it.manifest) })

    fun summariesOf(state: PluginRegistryState): List<PluginKindSummary> = state.plugins.flatMap { plugin ->
        val manifest = plugin.manifest
        manifest.elements.map { (kind, declared) ->
            PluginKindSummary(
                type = manifest.elementType(kind),
                pluginId = manifest.id,
                pluginName = manifest.name,
                kind = kind,
                schemaVersion = declared.schemaVersion,
                defaultWidth = declared.defaultSize?.width,
                defaultHeight = declared.defaultSize?.height,
                page = declared.page,
            )
        }
    }

    private fun elementsJson(manifest: PluginManifest): JsonObject = PluginManifestParser.json.encodeToJsonElement(
        MapSerializer(String.serializer(), PluginElementKind.serializer()), manifest.elements,
    ) as JsonObject
}
