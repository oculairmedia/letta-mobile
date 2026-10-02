package com.letta.mobile.data.canvas.plugin

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A piece of plugin content on the board (canvas plugin platform plan, section 4.1): one entry of
 * the scene-root collection `_pluginElements`, beside DrawBox's `elements` and the notes'
 * `_documents`. The `_` prefix keeps it out of DrawBox ([com.letta.mobile.data.canvas.CanvasOpProjector.stripMetadataForDrawBox]).
 *
 *  - [type] is `ext:<pluginId>/<kind>` and [v] the kind's schema version the writer used.
 *  - [frame] is in world units, like a note's; [owner] says who decides it (EXPLICIT when a writer
 *    named it, USER once a person moved it).
 *  - [ref] is the plugin's own opaque reference (never a secret), [props] small flat scalars,
 *    [snapshot] an image in the asset store and [fallback] what every client can draw without the
 *    plugin. [meta] is host-written provenance (which plugin, which call), flat like [props].
 *
 * The board stores each entry with split provenance ([CanvasPluginElements]), so a person moving
 * the element and the plugin updating its state never overwrite each other.
 */
@Serializable
data class CanvasPluginElement(
    val id: String,
    val type: String,
    val v: Int = 1,
    val frame: CanvasDocumentFrame? = null,
    val owner: CanvasGeometryOwner? = null,
    val ref: String? = null,
    val props: JsonObject = JsonObject(emptyMap()),
    val snapshot: CanvasPluginSnapshot? = null,
    val fallback: CanvasPluginFallback,
    val meta: JsonObject? = null,
) {
    /** The plugin that owns [type]: `letta.comfyui` of `ext:letta.comfyui/job`. */
    val pluginId: String get() = type.removePrefix(TYPE_PREFIX).substringBefore('/')

    /** The kind within the plugin: `job` of `ext:letta.comfyui/job`. */
    val kind: String get() = type.substringAfter('/', missingDelimiterValue = "")

    companion object {
        const val TYPE_PREFIX: String = "ext:"
    }
}

/**
 * The picture of a plugin element: an asset ([assetRef], `sha256:<hex>`) synced like any image, so
 * a peer without the plugin still sees it. [rev] counts the plugin's revisions of it.
 */
@Serializable
data class CanvasPluginSnapshot(
    val assetRef: String,
    val mediaType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rev: Long? = null,
    val takenAtEpochMs: Long? = null,
)

/**
 * What a client draws for a plugin element it has no renderer for, offline or with the plugin
 * uninstalled: a card with [title], [subtitle], an [icon] name and a link to [openUrl].
 */
@Serializable
data class CanvasPluginFallback(
    val title: String,
    val subtitle: String? = null,
    val icon: String? = null,
    val openUrl: String? = null,
)

