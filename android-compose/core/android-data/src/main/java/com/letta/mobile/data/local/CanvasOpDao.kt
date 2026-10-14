package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Columns are named rather than starred so a migration that adds one is a compile error here
 * instead of a shape change the reader discovers at runtime (`NoSelectStarInRoomDao`).
 */
private const val CANVAS_OP_COLUMNS =
    "opId, canvasId, lamport, actorId, opType, payloadJson, createdAtEpochMs, settled"

@Dao
interface CanvasOpDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: CanvasOpEntity): Long

    @Query("SELECT $CANVAS_OP_COLUMNS FROM canvas_ops WHERE canvasId = :canvasId AND lamport > :sinceLamport ORDER BY lamport ASC")
    suspend fun getSince(canvasId: String, sinceLamport: Long): List<CanvasOpEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM canvas_ops WHERE canvasId = :canvasId AND opId = :opId)")
    suspend fun has(canvasId: String, opId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM canvas_ops WHERE opId = :opId)")
    suspend fun hasOpId(opId: String): Boolean

    /** Ops the stored document may not hold yet, in the order they were appended (rowid). */
    @Query("SELECT $CANVAS_OP_COLUMNS FROM canvas_ops WHERE canvasId = :canvasId AND settled = 0 ORDER BY rowid ASC")
    suspend fun getUnsettled(canvasId: String): List<CanvasOpEntity>

    @Query("UPDATE canvas_ops SET settled = 1 WHERE canvasId = :canvasId AND opId IN (:opIds)")
    suspend fun settle(canvasId: String, opIds: List<String>)

    @Query("SELECT COUNT(*) FROM canvas_ops WHERE canvasId = :canvasId")
    suspend fun countForCanvas(canvasId: String): Int
}
