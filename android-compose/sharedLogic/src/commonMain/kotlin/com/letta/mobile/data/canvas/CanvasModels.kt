package com.letta.mobile.data.canvas

import kotlinx.serialization.Serializable

@Serializable
data class CanvasId(val value: String) {
    override fun toString(): String = value
}

@Serializable
data class CanvasDocument(
    val id: CanvasId,
    val agentId: String? = null,
    val conversationId: String? = null,
    val title: String,
    val revision: Long = 0L,
    val sceneJson: String = "",
    val updatedAtEpochMs: Long = 0L,
    val acl: CanvasAcl? = null,
)

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
