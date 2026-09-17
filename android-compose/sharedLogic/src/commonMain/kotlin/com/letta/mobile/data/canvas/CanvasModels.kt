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
)
