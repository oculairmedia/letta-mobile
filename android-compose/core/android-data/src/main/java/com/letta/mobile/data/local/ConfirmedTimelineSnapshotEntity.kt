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
class ConfirmedTimelineSnapshotChunkEntity(
    @ColumnInfo(name = "manifest_id") val manifestId: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @ColumnInfo(name = "payload") val payload: ByteArray,
)

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
@Entity(
    tableName = "normalized_timeline_snapshot_heads",
    primaryKeys = ["backend_id", "conversation_id"],
)
data class NormalizedTimelineSnapshotHeadEntity(
    @ColumnInfo(name = "backend_id") val backendId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "agent_id") val agentId: String?,
    @ColumnInfo(name = "storage_layout_version") val storageLayoutVersion: Int,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "envelope_schema_version") val envelopeSchemaVersion: Int,
    @ColumnInfo(name = "live_cursor") val liveCursor: String?,
    @ColumnInfo(name = "backfill_cursor") val backfillCursor: String?,
    @ColumnInfo(name = "released_older_count") val releasedOlderCount: Int,
    @ColumnInfo(name = "row_count") val rowCount: Int,
    @ColumnInfo(name = "root_digest") val rootDigest: String,
    @ColumnInfo(name = "generation") val generation: Long,
    @ColumnInfo(name = "written_at_millis") val writtenAtMillis: Long,
)

@Entity(
    tableName = "normalized_timeline_snapshot_rows",
    primaryKeys = ["backend_id", "conversation_id", "identity_primary", "identity_secondary"],
    indices = [Index(value = ["backend_id", "conversation_id", "event_order"], unique = true)],
)
data class NormalizedTimelineSnapshotRowEntity(
    @ColumnInfo(name = "backend_id") val backendId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "identity_primary") val identityPrimary: Long,
    @ColumnInfo(name = "identity_secondary") val identitySecondary: Long,
    @ColumnInfo(name = "event_order") val eventOrder: Int,
    @ColumnInfo(name = "payload") val payload: ByteArray,
    @ColumnInfo(name = "checksum") val checksum: String,
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
        "SELECT * FROM normalized_timeline_snapshot_heads WHERE backend_id = :backendId AND conversation_id = :conversationId"
    )
    suspend fun getNormalizedHead(backendId: String, conversationId: String): NormalizedTimelineSnapshotHeadEntity?

    @Query(
        "SELECT * FROM normalized_timeline_snapshot_rows WHERE backend_id = :backendId AND conversation_id = :conversationId ORDER BY event_order"
    )
    suspend fun getNormalizedRows(backendId: String, conversationId: String): List<NormalizedTimelineSnapshotRowEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNormalizedRows(rows: List<NormalizedTimelineSnapshotRowEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNormalizedHead(head: NormalizedTimelineSnapshotHeadEntity)

    @Query("DELETE FROM normalized_timeline_snapshot_rows WHERE backend_id = :backendId AND conversation_id = :conversationId")
    suspend fun deleteNormalizedRows(backendId: String, conversationId: String)

    @Query("DELETE FROM normalized_timeline_snapshot_heads WHERE backend_id = :backendId AND conversation_id = :conversationId")
    suspend fun deleteNormalizedHead(backendId: String, conversationId: String)

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
