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

/** A block document attached to a canvas: [json] is the Cascade editor document JSON. */
data class CanvasSceneDocument(
    val id: String,
    val json: String,
)

/** The document every canvas carries by default: its notes beside the drawing. */
const val CANVAS_PRIMARY_DOCUMENT_ID: String = "notes"
