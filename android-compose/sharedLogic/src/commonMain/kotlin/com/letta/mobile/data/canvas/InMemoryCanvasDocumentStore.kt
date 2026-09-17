package com.letta.mobile.data.canvas

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe in-memory implementation of [CanvasDocumentStore] for tests and transient scenarios.
 */
class InMemoryCanvasDocumentStore : CanvasDocumentStore {
    private val mutex = Mutex()
    private val documents = mutableMapOf<CanvasId, CanvasDocument>()

    override suspend fun get(id: CanvasId): CanvasDocument? = mutex.withLock {
        documents[id]
    }

    override suspend fun getForConversation(conversationId: String): CanvasDocument? = mutex.withLock {
        documents.values.firstOrNull { it.conversationId == conversationId }
    }

    override suspend fun upsert(doc: CanvasDocument): Unit = mutex.withLock {
        documents[doc.id] = doc
    }

    override suspend fun listForAgent(agentId: String): List<CanvasDocument> = mutex.withLock {
        documents.values.filter { it.agentId == agentId }
    }

    override suspend fun listAll(): List<CanvasDocument> = mutex.withLock {
        documents.values.sortedByDescending { it.updatedAtEpochMs }
    }
}
