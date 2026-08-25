package com.letta.mobile.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import java.security.MessageDigest
import java.util.UUID

/** Streams schema-10 snapshot rows into bounded schema-11 chunks. */
internal class LegacySnapshotMigration(
    private val database: SupportSQLiteDatabase,
) {
    private val copier = LegacySnapshotRowCopier(database)

    fun copyAll() {
        database.query(LEGACY_SNAPSHOT_ROWS_QUERY).use { cursor ->
            while (cursor.moveToNext()) copier.copy(LegacySnapshotRow.from(cursor))
        }
    }
}

private data class LegacySnapshotRow(
    val rowId: Long,
    val backendId: String,
    val conversationId: String,
    val agentId: String?,
    val revision: Long,
    val schemaVersion: Long,
    val writtenAtMillis: Long,
    val byteLength: Long,
) {
    val chunkCount: Int
        get() = ((byteLength + SNAPSHOT_CHUNK_BYTES - 1) / SNAPSHOT_CHUNK_BYTES).toInt()

    companion object {
        fun from(cursor: Cursor) = LegacySnapshotRow(
            rowId = cursor.getLong(0),
            backendId = cursor.getString(1),
            conversationId = cursor.getString(2),
            agentId = cursor.getStringOrNull(3),
            revision = cursor.getLong(4),
            schemaVersion = cursor.getLong(5),
            writtenAtMillis = cursor.getLong(6),
            byteLength = cursor.getLong(7),
        )
    }
}

private class LegacySnapshotRowCopier(
    private val database: SupportSQLiteDatabase,
) {
    private val statements = MigrationStatements(database)

    fun copy(row: LegacySnapshotRow) {
        val manifestId = MigrationManifestId(UUID.randomUUID().toString())
        statements.insertManifest(row, manifestId)
        statements.updateChecksum(manifestId, copyChunks(row, manifestId))
        statements.insertHead(row, manifestId)
    }

    private fun copyChunks(row: LegacySnapshotRow, manifestId: MigrationManifestId): MigrationChecksum {
        val digest = MessageDigest.getInstance(SHA_256)
        repeat(row.chunkCount) { index ->
            val chunk = readChunk(MigrationChunk(row.rowId, index))
            digest.update(chunk)
            statements.insertChunk(manifestId, index, chunk)
        }
        return MigrationChecksum(digest.digest().toMigrationHex())
    }

    private fun readChunk(chunk: MigrationChunk): ByteArray {
        val offset = chunk.index.toLong() * SNAPSHOT_CHUNK_BYTES + 1L
        val query = """
            SELECT substr(CAST(payload_json AS BLOB), $offset, $SNAPSHOT_CHUNK_BYTES)
            FROM confirmed_timeline_snapshots
            WHERE rowid = ${chunk.rowId}
        """.trimIndent()
        return database.query(query).use { cursor ->
            check(cursor.moveToFirst()) { "Legacy snapshot disappeared during migration" }
            cursor.getBlob(0).also { payload ->
                check(payload.size <= SNAPSHOT_CHUNK_BYTES) { "Migration chunk exceeded bound" }
            }
        }
    }
}

@JvmInline
private value class MigrationManifestId(val value: String)

@JvmInline
private value class MigrationChecksum(val value: String)

private data class MigrationChunk(val rowId: Long, val index: Int)

private class MigrationStatements(database: SupportSQLiteDatabase) {
    private val insertManifest = database.compileStatement(INSERT_MANIFEST_SQL)
    private val insertChunk = database.compileStatement(INSERT_CHUNK_SQL)
    private val updateChecksum = database.compileStatement(UPDATE_CHECKSUM_SQL)
    private val insertHead = database.compileStatement(INSERT_HEAD_SQL)

    fun insertManifest(row: LegacySnapshotRow, manifestId: MigrationManifestId) = insertManifest.run {
        clearBindings()
        bindString(1, manifestId.value)
        bindString(2, row.backendId)
        bindString(3, row.conversationId)
        bindNullableString(4, row.agentId)
        bindLong(5, row.revision)
        bindLong(6, row.schemaVersion)
        bindLong(7, row.byteLength)
        bindLong(8, row.chunkCount.toLong())
        bindString(9, PENDING_CHECKSUM)
        bindLong(10, row.writtenAtMillis)
        executeInsert()
        Unit
    }

    fun insertChunk(manifestId: MigrationManifestId, chunkIndex: Int, payload: ByteArray) = insertChunk.run {
        clearBindings()
        bindString(1, manifestId.value)
        bindLong(2, chunkIndex.toLong())
        bindBlob(3, payload)
        executeInsert()
        Unit
    }

    fun updateChecksum(manifestId: MigrationManifestId, checksum: MigrationChecksum) = updateChecksum.run {
        clearBindings()
        bindString(1, checksum.value)
        bindString(2, manifestId.value)
        executeUpdateDelete()
        Unit
    }

    fun insertHead(row: LegacySnapshotRow, manifestId: MigrationManifestId) = insertHead.run {
        clearBindings()
        bindString(1, row.backendId)
        bindString(2, row.conversationId)
        bindNullableString(3, row.agentId)
        bindString(4, manifestId.value)
        bindLong(5, row.revision)
        bindLong(6, row.writtenAtMillis)
        executeInsert()
        Unit
    }
}

private fun SupportSQLiteStatement.bindNullableString(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindString(index, value)
}

private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

private fun ByteArray.toMigrationHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private const val SNAPSHOT_CHUNK_BYTES = 128 * 1024
private const val SHA_256 = "SHA-256"
private const val PENDING_CHECKSUM = "pending"
private val LEGACY_SNAPSHOT_ROWS_QUERY = """
    SELECT rowid, backend_id, conversation_id, agent_id, revision, schema_version,
           written_at_millis, length(CAST(payload_json AS BLOB)) AS byte_length
    FROM confirmed_timeline_snapshots
""".trimIndent()
private val INSERT_MANIFEST_SQL = """
    INSERT INTO confirmed_timeline_snapshot_manifests (
        manifest_id, backend_id, conversation_id, agent_id, revision, schema_version,
        byte_length, chunk_count, sha256, written_at_millis
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
""".trimIndent()
private const val INSERT_CHUNK_SQL =
    "INSERT INTO confirmed_timeline_snapshot_chunks (manifest_id, chunk_index, payload) VALUES (?, ?, ?)"
private const val UPDATE_CHECKSUM_SQL =
    "UPDATE confirmed_timeline_snapshot_manifests SET sha256 = ? WHERE manifest_id = ?"
private val INSERT_HEAD_SQL = """
    INSERT INTO confirmed_timeline_snapshot_heads_new (
        backend_id, conversation_id, agent_id, active_manifest_id, fallback_manifest_id,
        high_water_revision, written_at_millis
    ) VALUES (?, ?, ?, ?, NULL, ?, ?)
""".trimIndent()
