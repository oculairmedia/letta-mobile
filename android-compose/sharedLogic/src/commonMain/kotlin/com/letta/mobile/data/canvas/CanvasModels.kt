package com.letta.mobile.data.canvas

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class CanvasId(val value: String) {
    override fun toString(): String = value

    companion object {
        /**
         * A fresh id for a canvas that is about to be upserted. Every store's upsert replaces
         * an existing row (and its ACL) silently, so the id must not be able to collide: a
         * timestamp plus a small suffix could repeat within one millisecond, a UUID cannot.
         */
        @OptIn(ExperimentalUuidApi::class)
        fun generate(): CanvasId = CanvasId("canvas-${Uuid.random()}")
    }
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
