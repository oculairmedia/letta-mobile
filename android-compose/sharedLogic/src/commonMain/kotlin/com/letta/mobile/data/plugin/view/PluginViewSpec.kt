package com.letta.mobile.data.plugin.view

import com.letta.mobile.data.plugin.PluginActionVisibility
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginPage
import kotlinx.serialization.json.JsonObject

/**
 * One live view: which plugin page shows which element of which canvas. [page] is the manifest's
 * entry for [pageId] and [viewActions] the plugin's actions a view may call (visibility `view`),
 * each with its input schema. Build it with [of] so both come from the manifest.
 */
data class PluginViewSpec(
    val pluginId: String,
    val pluginVersion: String,
    val pageId: String,
    val canvasId: String,
    val elementId: String,
    val elementType: String,
    val page: PluginPage,
    val viewActions: Map<String, JsonObject>,
) {
    companion object {
        /** The view of [element] on [canvasId] through [manifest]'s page [pageId], or null when the manifest has no such page. */
        fun of(manifest: PluginManifest, pageId: String, canvasId: String, element: ViewElement): PluginViewSpec? {
            val page = manifest.pages[pageId] ?: return null
            return PluginViewSpec(
                pluginId = manifest.id,
                pluginVersion = manifest.version,
                pageId = pageId,
                canvasId = canvasId,
                elementId = element.id,
                elementType = element.type,
                page = page,
                viewActions = viewActionsOf(manifest),
            )
        }

        /** The actions of [manifest] a page may call, with their input schemas. */
        fun viewActionsOf(manifest: PluginManifest): Map<String, JsonObject> = manifest.actions
            .filterValues { PluginActionVisibility.VIEW in it.visibility }
            .mapValues { (_, action) -> action.input }
    }
}
