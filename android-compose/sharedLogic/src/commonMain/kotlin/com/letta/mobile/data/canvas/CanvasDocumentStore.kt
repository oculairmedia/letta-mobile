package com.letta.mobile.data.canvas

import com.letta.mobile.data.document.DocumentStore
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId

/**
 * Persistence contract for [CanvasDocument]: the shared-document [DocumentStore] (get, upsert,
 * compare-and-set by revision, library listing) plus the canvas's conversation lookups.
 *
 * Implemented over the Automerge notebook repository ([NotebookCanvasDocumentStore]), the one owner
 * of every canvas document on Android and desktop (letta-mobile-by4vm.22); the op log beside it is
 * its write-ahead journal ([CanvasOpLog.unsettled]). [InMemoryCanvasDocumentStore] serves tests.
 */
data class CanvasDeletedElement(val elementId: String, val elementJson: String)

/** Optional durable history of individually deleted drawing elements. */
interface CanvasDeletedElementStore {
    suspend fun deletedElements(id: CanvasId): List<CanvasDeletedElement>
}

interface CanvasDocumentStore : DocumentStore<CanvasId, CanvasDocument> {
    suspend fun getForConversation(conversationId: ConversationId): CanvasDocument?
    suspend fun listForAgent(agentId: AgentId): List<CanvasDocument>

    /**
     * Returns the canvas already bound to [doc]'s `conversationId`, or inserts [doc] and
     * returns it. Lookup and insert are one atomic step, so two concurrent creators for the
     * same conversation end up sharing one canvas instead of each persisting their own.
     * [doc] must carry a non-null `conversationId`.
     */
    suspend fun createForConversationIfAbsent(doc: CanvasDocument): CanvasDocument
}
