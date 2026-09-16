package com.letta.mobile.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasId

@Entity(
    tableName = "canvas_documents",
    indices = [
        Index(value = ["agentId"]),
        Index(value = ["conversationId"]),
        Index(value = ["updatedAtEpochMs"]),
    ],
)
data class CanvasDocumentEntity(
    @PrimaryKey val id: String,
    val agentId: String? = null,
    val conversationId: String? = null,
    val title: String,
    val revision: Long,
    val sceneJson: String,
    val updatedAtEpochMs: Long,
) {
    fun toCanvasDocument(): CanvasDocument = CanvasDocument(
        id = CanvasId(id),
        agentId = agentId,
        conversationId = conversationId,
        title = title,
        revision = revision,
        sceneJson = sceneJson,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    companion object {
        fun fromCanvasDocument(doc: CanvasDocument): CanvasDocumentEntity = CanvasDocumentEntity(
            id = doc.id.value,
            agentId = doc.agentId,
            conversationId = doc.conversationId,
            title = doc.title,
            revision = doc.revision,
            sceneJson = doc.sceneJson,
            updatedAtEpochMs = doc.updatedAtEpochMs,
        )
    }
}
