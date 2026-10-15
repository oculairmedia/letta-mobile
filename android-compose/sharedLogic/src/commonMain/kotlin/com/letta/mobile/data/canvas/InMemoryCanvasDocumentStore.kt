package com.letta.mobile.data.canvas

import com.letta.mobile.data.document.InMemoryDocumentStore
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId

/**
 * Thread-safe in-memory implementation of [CanvasDocumentStore] for tests and transient scenarios:
 * the shared-document [InMemoryDocumentStore] plus the conversation lookups.
 */
class InMemoryCanvasDocumentStore : InMemoryDocumentStore<CanvasId, CanvasDocument>(), CanvasDocumentStore {
    override suspend fun getForConversation(conversationId: ConversationId): CanvasDocument? = withDocuments { documents ->
        documents.values.firstOrNull { it.conversationId == conversationId.value }
    }

    override suspend fun createForConversationIfAbsent(doc: CanvasDocument): CanvasDocument = withDocuments { documents ->
        val conversationId = requireNotNull(doc.conversationId) { "createForConversationIfAbsent needs a conversationId" }
        documents.values.firstOrNull { it.conversationId == conversationId } ?: doc.also { documents[it.id] = it }
    }

    override suspend fun listForAgent(agentId: AgentId): List<CanvasDocument> = withDocuments { documents ->
        documents.values.filter { it.agentId == agentId.value }
    }
}
