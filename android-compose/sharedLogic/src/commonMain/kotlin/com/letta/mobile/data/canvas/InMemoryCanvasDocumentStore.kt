package com.letta.mobile.data.canvas

import com.letta.mobile.data.document.DocumentBacking
import com.letta.mobile.data.document.InMemoryDocumentStore
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thread-safe [CanvasDocumentStore] held in memory: the shared-document [InMemoryDocumentStore]
 * plus the conversation lookups. Without a [backing] it serves tests and transient scenarios; over
 * one (see [KeyValueCanvasStorage]) it is the board store of a platform without the notebook
 * repository, and reports that backing's faults.
 */
class InMemoryCanvasDocumentStore(
    backing: DocumentBacking<CanvasDocument>? = null,
) : InMemoryDocumentStore<CanvasId, CanvasDocument>(backing), CanvasDocumentStore, CanvasStorageHealth {
    override val storageFaults: StateFlow<List<CanvasStorageFault>> =
        (backing as? CanvasStorageHealth)?.storageFaults ?: MutableStateFlow(emptyList<CanvasStorageFault>()).asStateFlow()

    override suspend fun getForConversation(conversationId: ConversationId): CanvasDocument? = withDocuments { documents ->
        documents.values.firstOrNull { it.conversationId == conversationId.value }
    }

    override suspend fun createForConversationIfAbsent(doc: CanvasDocument): CanvasDocument = writeDocument { documents ->
        val conversationId = requireNotNull(doc.conversationId) { "createForConversationIfAbsent needs a conversationId" }
        documents.values.firstOrNull { it.conversationId == conversationId }?.let { it to null } ?: (doc to doc)
    }

    override suspend fun listForAgent(agentId: AgentId): List<CanvasDocument> = withDocuments { documents ->
        documents.values.filter { it.agentId == agentId.value }
    }
}
