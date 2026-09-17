package com.letta.mobile.data.canvas

/**
 * Persistence contract for [CanvasDocument].
 *
 * Implemented via Room on Android ([com.letta.mobile.data.local.RoomCanvasDocumentStore])
 * and atomic JSON files on Desktop ([com.letta.mobile.desktop.canvas.DesktopCanvasDocumentStore]).
 */
interface CanvasDocumentStore {
    suspend fun get(id: CanvasId): CanvasDocument?
    suspend fun getForConversation(conversationId: String): CanvasDocument?
    suspend fun upsert(doc: CanvasDocument)
    suspend fun listForAgent(agentId: String): List<CanvasDocument>

    /**
     * Persists [doc] only if the stored document's revision is still [expectedRevision].
     * The compare and the write are one atomic step, so two writers that both read revision N
     * cannot both land: exactly one sees `true`, the other sees `false` and must re-read.
     *
     * A document that does not exist yet has no revision to compare, so this returns `false`
     * for it; use [upsert] to create.
     */
    suspend fun upsertIfRevision(doc: CanvasDocument, expectedRevision: Long): Boolean

    /**
     * Returns the canvas already bound to [doc]'s `conversationId`, or inserts [doc] and
     * returns it. Lookup and insert are one atomic step, so two concurrent creators for the
     * same conversation end up sharing one canvas instead of each persisting their own.
     * [doc] must carry a non-null `conversationId`.
     */
    suspend fun createForConversationIfAbsent(doc: CanvasDocument): CanvasDocument
}
