package com.letta.mobile.data.canvas

import kotlinx.serialization.Serializable

/**
 * Snapshot checkpoint representing a point-in-time state of a [CanvasDocument].
 */
@Serializable
data class CanvasCheckpoint(
    val checkpointId: String,
    val canvasId: CanvasId,
    val revision: Long,
    val lamport: Long,
    val sceneJson: String,
    val actorId: String,
    val description: String = "",
    val createdAtEpochMs: Long = 0L,
)
