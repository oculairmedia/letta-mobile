package com.letta.mobile.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.letta.mobile.data.canvas.CanvasAcl
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    val aclJson: String? = null,
) {
    fun toCanvasDocument(): CanvasDocument = CanvasDocument(
        id = CanvasId(id),
        agentId = agentId,
        conversationId = conversationId,
        title = title,
        revision = revision,
        sceneJson = sceneJson,
        updatedAtEpochMs = updatedAtEpochMs,
        acl = aclJson?.let(::decodeAcl),
    )

    /**
     * A null ACL means "unrestricted" to every mutation path, so a row whose ACL column is
     * present but unreadable must not quietly load as open: the decode error is surfaced.
     */
    private fun decodeAcl(raw: String): CanvasAcl = try {
        Json.decodeFromString<CanvasAcl>(raw)
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException("Canvas '$id' has a malformed ACL and cannot be loaded", e)
    }

    companion object {
        fun fromCanvasDocument(doc: CanvasDocument): CanvasDocumentEntity = CanvasDocumentEntity(
            id = doc.id.value,
            agentId = doc.agentId,
            conversationId = doc.conversationId,
            title = doc.title,
            revision = doc.revision,
            sceneJson = doc.sceneJson,
            updatedAtEpochMs = doc.updatedAtEpochMs,
            aclJson = doc.acl?.let { Json.encodeToString(it) },
        )
    }
}
