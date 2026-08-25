package com.letta.mobile.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(
    tableName = "confirmed_timeline_snapshots",
    primaryKeys = ["backend_id", "conversation_id"],
    indices = [Index(value = ["backend_id", "written_at_millis"])],
)
data class ConfirmedTimelineSnapshotHeadEntity(
    @ColumnInfo(name = "backend_id") val backendId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "agent_id") val agentId: String?,
    @ColumnInfo(name = "active_manifest_id") val activeManifestId: String?,
    @ColumnInfo(name = "fallback_manifest_id") val fallbackManifestId: String?,
    @ColumnInfo(name = "high_water_revision") val highWaterRevision: Long,
    @ColumnInfo(name = "written_at_millis") val writtenAtMillis: Long,
)

@Entity(
    tableName = "confirmed_timeline_snapshot_manifests",
    indices = [
        Index(value = ["backend_id", "conversation_id", "revision"]),
        Index(value = ["backend_id"]),
    ],
)
data class ConfirmedTimelineSnapshotManifestEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "manifest_id") val manifestId: String,
    @ColumnInfo(name = "backend_id") val backendId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "agent_id") val agentId: String?,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "byte_length") val byteLength: Long,
    @ColumnInfo(name = "chunk_count") val chunkCount: Int,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "written_at_millis") val writtenAtMillis: Long,
)

@Entity(
    tableName = "confirmed_timeline_snapshot_chunks",
    primaryKeys = ["manifest_id", "chunk_index"],
    foreignKeys = [
        ForeignKey(
            entity = ConfirmedTimelineSnapshotManifestEntity::class,
            parentColumns = ["manifest_id"],
            childColumns = ["manifest_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["manifest_id"])],
)
data class ConfirmedTimelineSnapshotChunkEntity(
    @ColumnInfo(name = "manifest_id") val manifestId: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @ColumnInfo(name = "payload") val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is ConfirmedTimelineSnapshotChunkEntity &&
                chunkIndex == other.chunkIndex &&
                manifestId == other.manifestId &&
                payload.contentEquals(other.payload)
        )

    override fun hashCode(): Int {
        var result = manifestId.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

data class ConfirmedTimelineSnapshotHeadMetadata(
    @ColumnInfo(name = "backend_id") val backendId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "agent_id") val agentId: String?,
    @ColumnInfo(name = "active_manifest_id") val activeManifestId: String?,
    @ColumnInfo(name = "fallback_manifest_id") val fallbackManifestId: String?,
    @ColumnInfo(name = "high_water_revision") val highWaterRevision: Long,
    @ColumnInfo(name = "written_at_millis") val writtenAtMillis: Long,
)

@Dao
interface ConfirmedTimelineSnapshotDao {
    @Query(
        """
        SELECT backend_id, conversation_id, agent_id, active_manifest_id,
               fallback_manifest_id, high_water_revision, written_at_millis
        FROM confirmed_timeline_snapshots
        WHERE backend_id = :backendId AND conversation_id = :conversationId
        """
    )
    suspend fun getHeadMetadata(backendId: String, conversationId: String): ConfirmedTimelineSnapshotHeadMetadata?

    @Query(
        """
        SELECT manifest_id, backend_id, conversation_id, agent_id, revision, schema_version,
               byte_length, chunk_count, sha256, written_at_millis
        FROM confirmed_timeline_snapshot_manifests WHERE manifest_id = :manifestId
        """
    )
    suspend fun getManifest(manifestId: String): ConfirmedTimelineSnapshotManifestEntity?

    @Query(
        """
        SELECT payload FROM confirmed_timeline_snapshot_chunks
        WHERE manifest_id = :manifestId AND chunk_index = :chunkIndex
        """
    )
    suspend fun getChunk(manifestId: String, chunkIndex: Int): ByteArray?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertManifest(entity: ConfirmedTimelineSnapshotManifestEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChunks(entities: List<ConfirmedTimelineSnapshotChunkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceHead(entity: ConfirmedTimelineSnapshotHeadEntity)

    @Query("DELETE FROM confirmed_timeline_snapshots WHERE backend_id = :backendId AND conversation_id = :conversationId")
    suspend fun deleteHead(backendId: String, conversationId: String)

    @Query("DELETE FROM confirmed_timeline_snapshot_manifests WHERE backend_id = :backendId AND conversation_id = :conversationId")
    suspend fun deleteManifestsForScope(backendId: String, conversationId: String)

    @Query("DELETE FROM confirmed_timeline_snapshots WHERE backend_id = :backendId")
    suspend fun clearHeadsForBackend(backendId: String)

    @Query("DELETE FROM confirmed_timeline_snapshot_manifests WHERE backend_id = :backendId")
    suspend fun clearManifestsForBackend(backendId: String)

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
    suspend fun pruneHeads(backendId: String, maxRetained: Int)

    @Query(
        """
        DELETE FROM confirmed_timeline_snapshot_manifests
        WHERE backend_id = :backendId
          AND manifest_id NOT IN (
              SELECT active_manifest_id FROM confirmed_timeline_snapshots
              WHERE backend_id = :backendId AND active_manifest_id IS NOT NULL
              UNION
              SELECT fallback_manifest_id FROM confirmed_timeline_snapshots
              WHERE backend_id = :backendId AND fallback_manifest_id IS NOT NULL
          )
        """
    )
    suspend fun deleteOrphanManifestsForBackend(backendId: String)

    @Query("DELETE FROM confirmed_timeline_snapshot_manifests WHERE manifest_id = :manifestId")
    suspend fun deleteManifest(manifestId: String)
}
