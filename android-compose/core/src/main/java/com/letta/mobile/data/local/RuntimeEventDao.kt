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

    @Query("SELECT * FROM runtime_events WHERE eventOffset > :afterOffset ORDER BY eventOffset ASC")
    suspend fun listAfterOffset(afterOffset: Long): List<RuntimeEventEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: RuntimeEventEntity)

    @Query("DELETE FROM runtime_events")
    suspend fun deleteAll()
}
