package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Columns are named rather than starred so a migration that adds one is a compile error here
 * instead of a shape change the reader discovers at runtime (`NoSelectStarInRoomDao`).
 */
private const val CANVAS_COLUMNS =
    "id, agentId, conversationId, title, revision, sceneJson, updatedAtEpochMs, aclJson"

@Dao
interface CanvasDocumentDao {
    @Query("SELECT $CANVAS_COLUMNS FROM canvas_documents WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CanvasDocumentEntity?

    @Query("SELECT $CANVAS_COLUMNS FROM canvas_documents WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getForConversation(conversationId: String): CanvasDocumentEntity?

    @Query("SELECT $CANVAS_COLUMNS FROM canvas_documents WHERE agentId = :agentId ORDER BY updatedAtEpochMs DESC")
    suspend fun listForAgent(agentId: String): List<CanvasDocumentEntity>

    @Query("SELECT $CANVAS_COLUMNS FROM canvas_documents ORDER BY updatedAtEpochMs DESC")
    suspend fun listAll(): List<CanvasDocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CanvasDocumentEntity)
}
