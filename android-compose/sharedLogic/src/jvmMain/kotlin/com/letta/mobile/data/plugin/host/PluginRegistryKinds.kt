package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.canvas.plugin.PluginKindCatalog
import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.PluginRegistryState
import kotlinx.serialization.json.JsonObject

/**
 * The element kinds of every installed plugin, read live from the registry: what the host canvas
 * backend (and so the one canvas validator) holds plugin element props to. A kind is known at its
 * manifest's current `schemaVersion` only.
 */
class PluginRegistryKinds(private val registry: () -> PluginRegistryState) : PluginKindCatalog {
    override fun schemaFor(type: String, v: Int): JsonObject? = registry().plugins.firstNotNullOfOrNull { plugin ->
        plugin.manifest.elements.entries
            .firstOrNull { (kind, spec) -> plugin.manifest.elementType(kind) == type && spec.schemaVersion == v }
            ?.value?.props
    }

    override fun knows(pluginId: PluginId): Boolean = registry()[pluginId] != null
}

/**
 * A [PluginKindCatalog] whose [target] is bound after it is handed out: the canvas backend is built
 * before the plugin host that owns the kinds. Until bound it knows no kinds.
 */
class LateBoundPluginKinds : PluginKindCatalog {
    @Volatile var target: PluginKindCatalog = PluginKindCatalog.Empty

    override fun schemaFor(type: String, v: Int): JsonObject? = target.schemaFor(type, v)

    override fun knows(pluginId: PluginId): Boolean = target.knows(pluginId)
}
