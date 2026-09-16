package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CanvasDocumentDao {
    @Query("SELECT * FROM canvas_documents WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CanvasDocumentEntity?

    @Query("SELECT * FROM canvas_documents WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getForConversation(conversationId: String): CanvasDocumentEntity?

    @Query("SELECT * FROM canvas_documents WHERE agentId = :agentId ORDER BY updatedAtEpochMs DESC")
    suspend fun listForAgent(agentId: String): List<CanvasDocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CanvasDocumentEntity)
}
