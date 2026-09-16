package com.letta.mobile.data.canvas

import kotlinx.serialization.Serializable

/**
 * Access Control List for a collaborative [CanvasDocument].
 *
 * Defines authorization for reading and writing canvas documents and applying operations.
 * Identifiers can be raw or prefixed with "user:" or "agent:".
 */
@Serializable
data class CanvasAcl(
    val ownerUserId: String,
    val writerUserIds: Set<String> = emptySet(),
    val writerAgentIds: Set<String> = emptySet(),
    val readerUserIds: Set<String> = emptySet(),
    val readerAgentIds: Set<String> = emptySet(),
) {
    /**
     * Checks if [actorId] is authorized to mutate the canvas.
     */
    fun canWrite(actorId: String?): Boolean {
        if (actorId.isNullOrBlank()) return false
        val normalized = normalize(actorId)
        if (actorId == ownerUserId || normalized == normalize(ownerUserId)) return true
        if (actorId in writerUserIds || normalized in writerUserIds) return true
        if (actorId in writerAgentIds || normalized in writerAgentIds) return true
        return false
    }

    /**
     * Checks if [actorId] is authorized to view the canvas.
     * When [readerUserIds] and [readerAgentIds] are empty, the canvas defaults to public-read.
     */
    fun canRead(actorId: String?): Boolean {
        if (readerUserIds.isEmpty() && readerAgentIds.isEmpty()) return true
        if (actorId.isNullOrBlank()) return false
        if (canWrite(actorId)) return true
        val normalized = normalize(actorId)
        if (actorId in readerUserIds || normalized in readerUserIds) return true
        if (actorId in readerAgentIds || normalized in readerAgentIds) return true
        return false
    }

    companion object {
        private fun normalize(id: String): String =
            id.removePrefix("user:").removePrefix("agent:")
    }
}

/**
 * Thrown when an actor attempts an unauthorized mutation on a [CanvasSession].
 */
class UnauthorizedCanvasMutationException(
    val actorId: String?,
    val canvasId: CanvasId,
    message: String = "Actor '${actorId ?: "anonymous"}' is not authorized to mutate canvas '$canvasId'",
) : IllegalStateException(message)
