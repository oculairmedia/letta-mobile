package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import com.letta.mobile.data.canvas.plugin.CanvasPluginElements
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * What `canvas_get_scene` answers, on the Iroh host and on an app's own App Server alike
 * (letta-mobile-s416w.5).
 *
 * The board's plugin elements are not in `scene_json`: the scene root's `_pluginElements` holds them
 * with per-register provenance an agent has no use for. They are listed once, compact, in
 * `plugin_elements`: each `{id, type, v, frame?, owner?, ref?, props, snapshot?, fallback, meta?}`,
 * the fields of `set_plugin_element`, absent ones left out. Removed and partial entries are not listed.
 * A `canvas_replace_scene` of the scene read keeps them (the projector carries them across).
 */
internal object CanvasSceneRead {
    private val compact = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun result(sceneJson: String, revision: Long, canvasId: String): CanvasGetSceneResult = CanvasGetSceneResult(
        sceneJson = withoutPluginEntries(sceneJson),
        revision = revision,
        canvasId = canvasId,
        schemaHint = CanvasSceneSchema.hint,
        pluginElements = CanvasOpProjector.pluginElementsOf(sceneJson).map(::compact),
    )

    private fun compact(element: CanvasPluginElement): JsonObject =
        compact.encodeToJsonElement(CanvasPluginElement.serializer(), element).jsonObject

    /** [sceneJson] without its `_pluginElements`, untouched when it has none. */
    private fun withoutPluginEntries(sceneJson: String): String {
        if (CanvasPluginElements.KEY !in sceneJson) return sceneJson
        val root = runCatching { compact.parseToJsonElement(sceneJson).jsonObject }.getOrNull() ?: return sceneJson
        return JsonObject(root - CanvasPluginElements.KEY).toString()
    }
}
