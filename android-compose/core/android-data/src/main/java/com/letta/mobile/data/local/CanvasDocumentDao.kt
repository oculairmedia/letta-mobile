package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

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

    /**
     * Lookup and insert in one transaction, so two creators for the same conversation cannot
     * both find nothing and both insert.
     */
    @Transaction
    suspend fun insertIfAbsentForConversation(entity: CanvasDocumentEntity): CanvasDocumentEntity {
        val conversationId = requireNotNull(entity.conversationId) { "insertIfAbsentForConversation needs a conversationId" }
        return getForConversation(conversationId) ?: entity.also { upsert(it) }
    }

    /**
     * Writes the row only while its revision is still [expectedRevision]; the compare and the
     * write are one SQL statement, so no other writer can slip in between. Returns rows changed.
     */
    @Query(
        "UPDATE canvas_documents SET agentId = :agentId, conversationId = :conversationId, title = :title, " +
            "revision = :revision, sceneJson = :sceneJson, updatedAtEpochMs = :updatedAtEpochMs, aclJson = :aclJson " +
            "WHERE id = :id AND revision = :expectedRevision",
    )
    suspend fun updateIfRevision(
        id: String,
        expectedRevision: Long,
        agentId: String?,
        conversationId: String?,
        title: String,
        revision: Long,
        sceneJson: String,
        updatedAtEpochMs: Long,
        aclJson: String?,
    ): Int
}
