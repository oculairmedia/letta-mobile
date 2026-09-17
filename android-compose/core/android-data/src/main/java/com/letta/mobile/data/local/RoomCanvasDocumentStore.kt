package com.letta.mobile.data.local

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasId

/**
 * Android Room-backed implementation of [CanvasDocumentStore].
 */
class RoomCanvasDocumentStore(
    private val dao: CanvasDocumentDao,
) : CanvasDocumentStore {

    override suspend fun get(id: CanvasId): CanvasDocument? =
        dao.getById(id.value)?.toCanvasDocument()

    override suspend fun getForConversation(conversationId: String): CanvasDocument? =
        dao.getForConversation(conversationId)?.toCanvasDocument()

    override suspend fun upsert(doc: CanvasDocument) {
        dao.upsert(CanvasDocumentEntity.fromCanvasDocument(doc))
    }

    override suspend fun upsertIfRevision(doc: CanvasDocument, expectedRevision: Long): Boolean {
        val entity = CanvasDocumentEntity.fromCanvasDocument(doc)
        val changed = dao.updateIfRevision(
            id = entity.id,
            expectedRevision = expectedRevision,
            agentId = entity.agentId,
            conversationId = entity.conversationId,
            title = entity.title,
            revision = entity.revision,
            sceneJson = entity.sceneJson,
            updatedAtEpochMs = entity.updatedAtEpochMs,
            aclJson = entity.aclJson,
        )
        return changed == 1
    }

    override suspend fun createForConversationIfAbsent(doc: CanvasDocument): CanvasDocument =
        dao.insertIfAbsentForConversation(CanvasDocumentEntity.fromCanvasDocument(doc)).toCanvasDocument()

    override suspend fun listForAgent(agentId: String): List<CanvasDocument> =
        dao.listForAgent(agentId).map { it.toCanvasDocument() }
}
