package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CanvasOpDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: CanvasOpEntity): Long

    @Query("SELECT * FROM canvas_ops WHERE canvasId = :canvasId AND lamport > :sinceLamport ORDER BY lamport ASC")
    suspend fun getSince(canvasId: String, sinceLamport: Long): List<CanvasOpEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM canvas_ops WHERE canvasId = :canvasId AND opId = :opId)")
    suspend fun has(canvasId: String, opId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM canvas_ops WHERE opId = :opId)")
    suspend fun hasOpId(opId: String): Boolean

    @Query("SELECT COUNT(*) FROM canvas_ops WHERE canvasId = :canvasId")
    suspend fun countForCanvas(canvasId: String): Int
}
