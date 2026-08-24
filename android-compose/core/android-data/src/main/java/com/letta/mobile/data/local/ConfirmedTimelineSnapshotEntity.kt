package com.letta.mobile.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(
    tableName = "confirmed_timeline_snapshots",
    primaryKeys = ["backend_id", "conversation_id"],
    indices = [
        Index(value = ["backend_id", "written_at_millis"]),
    ],
)
data class ConfirmedTimelineSnapshotEntity(
    @ColumnInfo(name = "backend_id")
    val backendId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "agent_id")
    val agentId: String? = null,
    @ColumnInfo(name = "revision")
    val revision: Long,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "written_at_millis")
    val writtenAtMillis: Long,
)

@Dao
interface ConfirmedTimelineSnapshotDao {
    @Query("SELECT * FROM confirmed_timeline_snapshots WHERE backend_id = :backendId AND conversation_id = :conversationId")
    suspend fun getSnapshot(backendId: String, conversationId: String): ConfirmedTimelineSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entity: ConfirmedTimelineSnapshotEntity)

    @Query("DELETE FROM confirmed_timeline_snapshots WHERE backend_id = :backendId AND conversation_id = :conversationId")
    suspend fun deleteSnapshot(backendId: String, conversationId: String)

    @Query("DELETE FROM confirmed_timeline_snapshots WHERE backend_id = :backendId")
    suspend fun clearForBackend(backendId: String)

    @Query(
        """
        DELETE FROM confirmed_timeline_snapshots
        WHERE backend_id = :backendId AND conversation_id NOT IN (
            SELECT conversation_id FROM confirmed_timeline_snapshots
            WHERE backend_id = :backendId
            ORDER BY written_at_millis DESC
            LIMIT :maxRetained
        )
        """
    )
    suspend fun pruneBackend(backendId: String, maxRetained: Int)
}
