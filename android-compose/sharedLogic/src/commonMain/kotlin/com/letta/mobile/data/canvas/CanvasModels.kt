package com.letta.mobile.data.canvas

import com.letta.mobile.data.document.DocumentSnapshot
import com.letta.mobile.data.model.CanvasId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A board at a revision: the shared-document [DocumentSnapshot] for canvases. */
@Serializable
data class CanvasDocument(
    override val id: CanvasId,
    val agentId: String? = null,
    val conversationId: String? = null,
    val title: String,
    override val revision: Long = 0L,
    val sceneJson: String = "",
    override val updatedAtEpochMs: Long = 0L,
    val acl: CanvasAcl? = null,
) : DocumentSnapshot<CanvasId>

/**
 * A block document attached to a canvas: [json] is the Cascade editor document JSON and [frame]
 * is where it sits on the board, in the drawing's world units. A document without a frame has
 * never been placed; the workspace gives it a default spot until someone moves it.
 */
data class CanvasSceneDocument(
    val id: String,
    val json: String,
    val frame: CanvasDocumentFrame? = null,
    /** The card's colour as `#rrggbb`; null is the workspace's default note colour. */
    val color: String? = null,
    /** How the text is set; null is the editor's default. */
    val style: CanvasTextStyle? = null,
    /** The note's title, shown over it on the board and at the top of it opened large; null for none. */
    val title: String? = null,
    /**
     * Who decides the frame ([CanvasGeometryOwner]); null for a document written before owners
     * existed, or by a writer that never placed it.
     */
    val owner: CanvasGeometryOwner? = null,
    /** Which canvas_compose request made this document; null for one compose did not make. */
    val compose: CanvasComposeProvenance? = null,
)

/**
 * Who owns a block document's frame (canvas_compose plan, decision D3). Stored as a mode, never
 * as a measurement: the renderer measures locally and never writes geometry back.
 *
 *  - [AUTO]: placed by canvas_compose with a reserved height; the renderer fits the card to its
 *    content within that reservation.
 *  - [EXPLICIT]: a writer named the frame (`set_document` with a frame); drawn verbatim.
 *  - [USER]: a person moved or resized it; drawn verbatim, and never auto-fitted again.
 *
 * A document with no owner and no frame is a legacy frameless note, laid out as [AUTO] would be.
 */
@Serializable
enum class CanvasGeometryOwner {
    @SerialName("auto") AUTO,
    @SerialName("explicit") EXPLICIT,
    @SerialName("user") USER,
}

/**
 * Where a canvas_compose artifact's piece came from: the request's [artifactId], the item's [key],
 * its [kind] (a `ComposeKind` name, kept as text so a board written by a later catalog version
 * still reads here), and the [catalog] and [version] that compiled it. Carried by a block
 * document as `compose`, and by a TEXT or GROUP element as `_compose` (underscore-prefixed, so
 * [CanvasOpProjector.stripMetadataForDrawBox] removes it before DrawBox sees the element).
 */
@Serializable
data class CanvasComposeProvenance(
    val artifactId: String,
    val key: String,
    val kind: String,
    val catalog: String,
    val version: Int,
)

/**
 * How a block document's text is set on the board. [fontScale] multiplies the editor's sizes,
 * [fontFamily] is one of `sans`, `serif`, `mono`, [textColor] is `#rrggbb`, [align] is `start`,
 * `center` or `end`. Null fields mean the editor's default, so a style only says what differs.
 */
@Serializable
data class CanvasTextStyle(
    val fontScale: Float? = null,
    val fontFamily: String? = null,
    val textColor: String? = null,
    val align: String? = null,
)

/**
 * The board's background pattern: [kind] is `none`, `grid`, `dots` or `lines`, [spacing] the
 * repeat in world units, [colorHex] the pattern's `#rrggbb` tint. Kept on the scene root beside
 * `bgColor`, last writer wins like the colour.
 */
@Serializable
data class CanvasBackgroundPattern(
    val kind: String = NONE,
    val spacing: Float = DEFAULT_SPACING,
    val colorHex: String = DEFAULT_COLOR,
) {
    companion object {
        const val NONE = "none"
        const val GRID = "grid"
        const val DOTS = "dots"
        const val LINES = "lines"
        const val DEFAULT_SPACING = 32f
        const val DEFAULT_COLOR = "#9ca3af"
        val KINDS: List<String> = listOf(NONE, GRID, DOTS, LINES)
        val SPACINGS: List<Float> = listOf(16f, 32f, 64f)
    }
}

/** One end of a connector attached to a block document: which document, and which side of it. */
@Serializable
data class CanvasEndBinding(val documentId: String, val side: String)

/** Which documents a connector's ends are bound to; a null end is free (or bound to a drawn shape by DrawBox). */
@Serializable
data class CanvasArrowBinding(
    val start: CanvasEndBinding? = null,
    val end: CanvasEndBinding? = null,
)

/** Where a block document sits on the board: top-left corner and size in world units. */
@Serializable
data class CanvasDocumentFrame(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** The document every canvas carries by default: its notes beside the drawing. */
const val CANVAS_PRIMARY_DOCUMENT_ID: String = "notes"
