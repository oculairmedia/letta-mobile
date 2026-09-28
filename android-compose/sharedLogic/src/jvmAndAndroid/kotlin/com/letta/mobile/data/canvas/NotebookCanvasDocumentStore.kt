package com.letta.mobile.data.canvas

/** Canvas view of the notebook repository. Canvas identity and metadata live in each notebook CRDT,
 * not in a second canvas database. A repository lock serializes claims and revision checks. */
class NotebookCanvasDocumentStore(private val notebooks: NotebookLocalStore) : CanvasDocumentStore, CanvasDeletedElementStore {
    override suspend fun deletedElements(id: CanvasId): List<CanvasDeletedElement> = notebooks.withCanvasLock {
        val target = notebooks.listDocuments().firstOrNull { notebooks.canvasDocument(it)?.id == id }
            ?: return@withCanvasLock emptyList()
        notebooks.deletedBoardElements(target)
    }

    private fun all(): List<CanvasDocument> = notebooks.listDocuments().mapNotNull { notebooks.canvasDocument(it) }

    override suspend fun get(id: CanvasId): CanvasDocument? = notebooks.withCanvasLock {
        all().firstOrNull { it.id == id }
    }

    override suspend fun getForConversation(conversationId: String): CanvasDocument? = notebooks.withCanvasLock {
        all().firstOrNull { it.conversationId == conversationId }
    }

    override suspend fun upsert(doc: CanvasDocument) = notebooks.withCanvasLock {
        val existing = notebooks.listDocuments().firstOrNull { notebooks.canvasDocument(it)?.id == doc.id }
        val target = existing ?: notebooks.create(doc.title)
        notebooks.writeCanvas(target, doc, null)
        Unit
    }

    override suspend fun upsertIfRevision(doc: CanvasDocument, expectedRevision: Long): Boolean = notebooks.withCanvasLock {
        val target = notebooks.listDocuments().firstOrNull { notebooks.canvasDocument(it)?.id == doc.id }
            ?: return@withCanvasLock false
        notebooks.writeCanvas(target, doc, expectedRevision)
    }

    override suspend fun createForConversationIfAbsent(doc: CanvasDocument): CanvasDocument = notebooks.withCanvasLock {
        val conversation = requireNotNull(doc.conversationId) { "createForConversationIfAbsent needs a conversationId" }
        all().firstOrNull { it.conversationId == conversation } ?: run {
            require(all().none { it.id == doc.id }) { "Canvas ID already belongs to another conversation" }
            val target = notebooks.create(doc.title)
            notebooks.writeCanvas(target, doc, null)
            doc
        }
    }

    override suspend fun listForAgent(agentId: String): List<CanvasDocument> = notebooks.withCanvasLock {
        all().filter { it.agentId == agentId }.sortedByDescending { it.updatedAtEpochMs }
    }

    override suspend fun listAll(): List<CanvasDocument> = notebooks.withCanvasLock {
        all().sortedByDescending { it.updatedAtEpochMs }
    }
}
