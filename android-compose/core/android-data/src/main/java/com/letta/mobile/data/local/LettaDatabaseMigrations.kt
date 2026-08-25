package com.letta.mobile.data.local

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import java.security.MessageDigest
import java.util.UUID

object LettaDatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `project_bug_reports` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `projectIdentifier` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `severity` TEXT NOT NULL,
                    `tagsJson` TEXT NOT NULL,
                    `attachmentReferencesJson` TEXT NOT NULL,
                    `structuredPrompt` TEXT NOT NULL,
                    `createdAt` TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pending_local_messages` (
                    `otid` TEXT NOT NULL,
                    `conversationId` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `attachmentsJson` TEXT NOT NULL,
                    `sentAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`otid`)
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val cursor = db.query("SELECT `id`, `tagsJson` FROM `agents` WHERE `tagsJson` IS NOT NULL")
            cursor.use {
                val idIndex = it.getColumnIndexOrThrow("id")
                val tagsIndex = it.getColumnIndexOrThrow("tagsJson")
                val update = db.compileStatement("UPDATE `agents` SET `tagsJson` = ? WHERE `id` = ?")
                while (it.moveToNext()) {
                    val rawTags = it.getString(tagsIndex)
                    if (AgentEntity.isJsonEncodedTags(rawTags)) continue

                    update.clearBindings()
                    update.bindString(1, AgentEntity.encodeTags(AgentEntity.decodeTags(rawTags)))
                    update.bindString(2, it.getString(idIndex))
                    update.executeUpdateDelete()
                }
            }
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversations` (
                    `id` TEXT NOT NULL,
                    `agentId` TEXT NOT NULL,
                    `summary` TEXT,
                    `createdAt` TEXT,
                    `updatedAt` TEXT,
                    `lastMessageAt` TEXT,
                    `archived` INTEGER,
                    `archivedAt` TEXT,
                    `inContextMessageIdsJson` TEXT NOT NULL,
                    `isolatedBlockIdsJson` TEXT NOT NULL,
                    `cachedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_agentId` ON `conversations` (`agentId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_lastMessageAt` ON `conversations` (`lastMessageAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_createdAt` ON `conversations` (`createdAt`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversation_refresh_state` (
                    `agentId` TEXT NOT NULL,
                    `lastRefreshAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`agentId`)
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `runtime_events` (
                    `eventOffset` INTEGER NOT NULL,
                    `eventId` TEXT NOT NULL,
                    `backendId` TEXT NOT NULL,
                    `runtimeId` TEXT NOT NULL,
                    `agentId` TEXT,
                    `conversationId` TEXT,
                    `runId` TEXT,
                    `createdAtEpochMs` INTEGER NOT NULL,
                    `source` TEXT NOT NULL,
                    `schemaVersion` INTEGER NOT NULL,
                    `payloadJson` TEXT NOT NULL,
                    PRIMARY KEY(`eventOffset`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_runtime_events_eventId` ON `runtime_events` (`eventId`)")
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_runtime_events_backendId_runtimeId_eventOffset`
                ON `runtime_events` (`backendId`, `runtimeId`, `eventOffset`)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_runtime_events_conversationId` ON `runtime_events` (`conversationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_runtime_events_agentId` ON `runtime_events` (`agentId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_runtime_events_runId` ON `runtime_events` (`runId`)")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `memfs_files` (
                    `path` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `content` TEXT NOT NULL,
                    `metadataJson` TEXT NOT NULL,
                    PRIMARY KEY(`path`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `memfs_commits` (
                    `revision` INTEGER NOT NULL,
                    `commitId` TEXT NOT NULL,
                    `path` TEXT NOT NULL,
                    `operation` TEXT NOT NULL,
                    `createdAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`revision`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memfs_commits_commitId` ON `memfs_commits` (`commitId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_memfs_commits_path` ON `memfs_commits` (`path`)")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversation_cursors` (
                    `conv_id` TEXT NOT NULL,
                    `highest_seen_seq` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`conv_id`)
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `agents` ADD COLUMN `metadataJson` TEXT")
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `confirmed_timeline_snapshots` (
                    `backend_id` TEXT NOT NULL,
                    `conversation_id` TEXT NOT NULL,
                    `agent_id` TEXT,
                    `revision` INTEGER NOT NULL,
                    `schema_version` INTEGER NOT NULL,
                    `payload_json` TEXT NOT NULL,
                    `written_at_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`backend_id`, `conversation_id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_confirmed_timeline_snapshots_backend_id_written_at_millis`
                ON `confirmed_timeline_snapshots` (`backend_id`, `written_at_millis`)
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createSnapshotChunkTables(db)
            migrateLegacySnapshotsInBoundedChunks(db)
            db.execSQL("DROP TABLE `confirmed_timeline_snapshots`")
            db.execSQL("ALTER TABLE `confirmed_timeline_snapshot_heads_new` RENAME TO `confirmed_timeline_snapshots`")
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_confirmed_timeline_snapshots_backend_id_written_at_millis`
                ON `confirmed_timeline_snapshots` (`backend_id`, `written_at_millis`)
                """.trimIndent(),
            )
        }
    }

    private fun createSnapshotChunkTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `confirmed_timeline_snapshot_heads_new` (
                `backend_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `agent_id` TEXT,
                `active_manifest_id` TEXT,
                `fallback_manifest_id` TEXT,
                `high_water_revision` INTEGER NOT NULL,
                `written_at_millis` INTEGER NOT NULL,
                PRIMARY KEY(`backend_id`, `conversation_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `confirmed_timeline_snapshot_manifests` (
                `manifest_id` TEXT NOT NULL,
                `backend_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `agent_id` TEXT,
                `revision` INTEGER NOT NULL,
                `schema_version` INTEGER NOT NULL,
                `byte_length` INTEGER NOT NULL,
                `chunk_count` INTEGER NOT NULL,
                `sha256` TEXT NOT NULL,
                `written_at_millis` INTEGER NOT NULL,
                PRIMARY KEY(`manifest_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `confirmed_timeline_snapshot_chunks` (
                `manifest_id` TEXT NOT NULL,
                `chunk_index` INTEGER NOT NULL,
                `payload` BLOB NOT NULL,
                PRIMARY KEY(`manifest_id`, `chunk_index`),
                FOREIGN KEY(`manifest_id`) REFERENCES `confirmed_timeline_snapshot_manifests`(`manifest_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_confirmed_timeline_snapshot_manifests_backend_id_conversation_id_revision` " +
                "ON `confirmed_timeline_snapshot_manifests` (`backend_id`, `conversation_id`, `revision`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_confirmed_timeline_snapshot_manifests_backend_id` " +
                "ON `confirmed_timeline_snapshot_manifests` (`backend_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_confirmed_timeline_snapshot_chunks_manifest_id` " +
                "ON `confirmed_timeline_snapshot_chunks` (`manifest_id`)",
        )
    }

    private fun migrateLegacySnapshotsInBoundedChunks(db: SupportSQLiteDatabase) {
        val copier = LegacySnapshotCopier(db)
        db.query(LEGACY_SNAPSHOT_ROWS_QUERY).use { cursor ->
            while (cursor.moveToNext()) {
                copier.copy(LegacySnapshotRow.from(cursor))
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
            fun from(cursor: Cursor): LegacySnapshotRow = LegacySnapshotRow(
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

    private class LegacySnapshotCopier(
        private val db: SupportSQLiteDatabase,
    ) {
        private val insertManifest = db.compileStatement(INSERT_MANIFEST_SQL)
        private val insertChunk = db.compileStatement(INSERT_CHUNK_SQL)
        private val updateChecksum = db.compileStatement(UPDATE_CHECKSUM_SQL)
        private val insertHead = db.compileStatement(INSERT_HEAD_SQL)

        fun copy(row: LegacySnapshotRow) {
            val manifestId = UUID.randomUUID().toString()
            insertManifest(row, manifestId)
            val checksum = copyChunks(row, manifestId)
            updateChecksum(manifestId, checksum)
            insertHead(row, manifestId)
        }

        private fun insertManifest(row: LegacySnapshotRow, manifestId: String) = insertManifest.run {
            clearBindings()
            bindString(1, manifestId)
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

        private fun copyChunks(row: LegacySnapshotRow, manifestId: String): String {
            val digest = MessageDigest.getInstance(SHA_256)
            repeat(row.chunkCount) { chunkIndex ->
                val chunk = readChunk(row.rowId, chunkIndex)
                digest.update(chunk)
                insertChunk(manifestId, chunkIndex, chunk)
            }
            return digest.digest().toHex()
        }

        private fun readChunk(rowId: Long, chunkIndex: Int): ByteArray {
            val offset = chunkIndex.toLong() * SNAPSHOT_CHUNK_BYTES + 1L
            val query = """
                SELECT substr(CAST(payload_json AS BLOB), $offset, $SNAPSHOT_CHUNK_BYTES)
                FROM confirmed_timeline_snapshots
                WHERE rowid = $rowId
            """.trimIndent()
            return db.query(query).use { cursor ->
                check(cursor.moveToFirst()) { "Legacy snapshot disappeared during migration" }
                cursor.getBlob(0).also { chunk ->
                    check(chunk.size <= SNAPSHOT_CHUNK_BYTES) { "Migration chunk exceeded bound" }
                }
            }
        }

        private fun insertChunk(manifestId: String, chunkIndex: Int, payload: ByteArray) = insertChunk.run {
            clearBindings()
            bindString(1, manifestId)
            bindLong(2, chunkIndex.toLong())
            bindBlob(3, payload)
            executeInsert()
            Unit
        }

        private fun updateChecksum(manifestId: String, checksum: String) = updateChecksum.run {
            clearBindings()
            bindString(1, checksum)
            bindString(2, manifestId)
            executeUpdateDelete()
            Unit
        }

        private fun insertHead(row: LegacySnapshotRow, manifestId: String) = insertHead.run {
            clearBindings()
            bindString(1, row.backendId)
            bindString(2, row.conversationId)
            bindNullableString(3, row.agentId)
            bindString(4, manifestId)
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

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
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

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
    )
}
