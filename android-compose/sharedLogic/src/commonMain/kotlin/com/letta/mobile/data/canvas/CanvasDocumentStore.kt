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

    /** Every stored canvas, most recently updated first. Canvases are shared, so this is the library view. */
    suspend fun listAll(): List<CanvasDocument>
}
