package com.letta.mobile.plugin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * What a plugin puts on the board (wire `host.emit` parameters, and [ActionResult.Ok.emit]). The
 * host compiles it into one atomic batch of canvas ops through the same validator as an agent's
 * `canvas_apply_ops`; whatever it refuses comes back in the [EmitReceipt], never as a failure.
 *
 * Only the plugin's own kinds can be placed or updated (`ext:<pluginId>/<kind>`), and removals name
 * the plugin's own elements. [placeImages] places core images that outlive the plugin.
 */
@Serializable
public data class PluginEmit(
    public val place: List<PlaceElement> = emptyList(),
    public val update: List<UpdateElement> = emptyList(),
    public val remove: List<String> = emptyList(),
    public val placeImages: List<PlaceImage> = emptyList(),
) {
    /** Whether the emit asks for nothing. */
    public val isEmpty: Boolean
        get() = place.isEmpty() && update.isEmpty() && remove.isEmpty() && placeImages.isEmpty()
}

/**
 * A new element of the plugin's [kind] (a key of the manifest's `elements`) at schema version [v]
 * (the kind's `schemaVersion`), with [props] valid against the kind's props schema, the [fallback]
 * card every client can draw, an optional [frame] (the host picks one from the kind's `defaultSize`
 * otherwise), the plugin's own opaque [ref] (never a secret) and an optional [snapshot] picture.
 */
@Serializable
public data class PlaceElement(
    public val kind: String,
    public val v: Int,
    public val props: JsonObject,
    public val fallback: ElementFallback,
    public val ref: String? = null,
    public val frame: ElementFrame? = null,
    public val snapshot: SnapshotSource? = null,
    /** The board to place on, when the emit is not an answer to an agent's call (which names its own board). */
    public val canvasId: String? = null,
)

/**
 * A change to the state of the plugin's element [elementId]: only the fields given change (the
 * element's `_state` group; its frame belongs to whoever moved it last).
 */
@Serializable
public data class UpdateElement(
    public val elementId: String,
    public val props: JsonObject? = null,
    public val fallback: ElementFallback? = null,
    public val snapshot: SnapshotSource? = null,
    public val ref: String? = null,
)

/**
 * A core image element placed from the plugin's output (needs `assets:write`): it keeps the
 * plugin as provenance and stays on the board without the plugin.
 */
@Serializable
public data class PlaceImage(
    public val image: SnapshotSource,
    public val frame: ElementFrame? = null,
    public val alt: String? = null,
    public val canvasId: String? = null,
)

/** Where a picture comes from: an asset already stored, or bytes the host stores (needs `assets:write`). */
@Serializable
public sealed interface SnapshotSource {
    /** An asset the host already has, by its `sha256:<hex>` [ref] (from [PluginHost.putAsset]). */
    @Serializable
    @SerialName("asset")
    public data class Asset(public val ref: String) : SnapshotSource

    /**
     * Raw [bytes] of [mediaType] (base64 on the wire), stored by the host on emit. A carrier, not
     * a value: two instances are equal only when they are the same instance (compare [bytes] with
     * `contentEquals`).
     */
    @Serializable
    @SerialName("bytes")
    public class Bytes(
        public val mediaType: String,
        @Serializable(with = Base64ByteArraySerializer::class)
        public val bytes: ByteArray,
    ) : SnapshotSource {
        override fun toString(): String = "Bytes(mediaType=$mediaType, size=${bytes.size})"
    }
}

/** A rectangle on the board in world units. */
@Serializable
public data class ElementFrame(
    public val x: Float,
    public val y: Float,
    public val width: Float,
    public val height: Float,
)

/** The card a client draws for an element it has no renderer for: [title], [subtitle], an [icon] name and an [openUrl]. */
@Serializable
public data class ElementFallback(
    public val title: String,
    public val subtitle: String? = null,
    public val icon: String? = null,
    public val openUrl: String? = null,
)

/** What the host did with an emit: the ids of the elements [placed] (in order) and what it [refused]. */
@Serializable
public data class EmitReceipt(
    public val placed: List<String> = emptyList(),
    public val refused: List<EmitRefusal> = emptyList(),
)

/** One refused entry: its [index] in the emit's flattened order (place, update, remove, placeImages) and the [reason]. */
@Serializable
public data class EmitRefusal(public val index: Int, public val reason: String)
