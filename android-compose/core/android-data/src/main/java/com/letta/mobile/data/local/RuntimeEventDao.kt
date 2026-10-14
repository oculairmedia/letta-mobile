package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RuntimeEventDao {
    @Query("SELECT COALESCE(MAX(eventOffset), 0) FROM runtime_events")
    suspend fun maxOffset(): Long

    @Query("SELECT MAX(eventOffset) FROM runtime_events")
    fun observeMaxOffset(): Flow<Long?>

    @Query("SELECT * FROM runtime_events WHERE eventOffset > :afterOffset ORDER BY eventOffset ASC LIMIT :limit")
    suspend fun listAfterOffset(afterOffset: Long, limit: Int): List<RuntimeEventEntity>

    /**
     * Deletes rows created before [cutoffEpochMs], never touching the newest row so
     * `MAX(eventOffset)` (the next-offset source) cannot regress.
     */
    @Query(
        "DELETE FROM runtime_events WHERE createdAtEpochMs < :cutoffEpochMs " +
            "AND eventOffset < (SELECT MAX(eventOffset) FROM runtime_events)",
    )
    suspend fun deleteOlderThan(cutoffEpochMs: Long): Int

    /**
     * Keeps only the newest [maxRows] rows (by offset). [maxRows] must be >= 1 so the
     * newest row always survives and offsets stay monotonic.
     */
    @Query(
        "DELETE FROM runtime_events WHERE eventOffset <= " +
            "(SELECT eventOffset FROM runtime_events ORDER BY eventOffset DESC LIMIT 1 OFFSET :maxRows)",
    )
    suspend fun deleteBeyondNewest(maxRows: Int): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rows: List<RuntimeEventEntity>)
}
