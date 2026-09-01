package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineReadResult
import com.letta.mobile.data.timeline.snapshot.SnapshotReadFailure
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class RoomConfirmedTimelineStoreTest {
    private var database: LettaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        database = null
    }

    private fun inMemoryDatabase(): LettaDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { database = it }
    }

    @Test
    fun writeAndReadSnapshotPreservesData() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope(backendId = "b1", conversationId = "c1", agentId = "a1")

        val envelope = StoredTimelineEnvelope(
            schemaVersion = 1,
            scope = scope,
            revision = 10L,
            liveCursor = "srv-10",
            backfillCursor = "srv-1",
            releasedOlderCount = 2,
            events = listOf(
                StoredTimelineEvent(
                    position = 1.0,
                    otid = "otid-1",
                    content = "Hello from Android persistence",
                    serverId = "srv-1",
                    messageType = "USER",
                    dateIso = "2026-08-24T00:00:00Z",
                )
            ),
            writtenAtMillis = 1000L,
        )

        val written = store.writeSnapshot(envelope)
        assertTrue(written)

        val read = store.readSnapshot(scope)
        assertNotNull(read)
        assertEquals(10L, read?.revision)
        assertEquals("srv-10", read?.liveCursor)
        assertEquals(1, read?.events?.size)
        assertEquals("Hello from Android persistence", read?.events?.first()?.content)
    }

    @Test
    fun staleRevisionWritesAreRejected() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope(backendId = "b1", conversationId = "c1")

        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 5L)))
        assertFalse(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 5L)))
        assertFalse(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 4L)))
        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 6L)))

        assertEquals(6L, store.readSnapshot(scope)?.revision)
    }

    @Test
    fun backendIsolationAndPrune() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)

        val scopeA = TimelineScope(backendId = "backend-A", conversationId = "c1")
        val scopeB = TimelineScope(backendId = "backend-B", conversationId = "c1")

        store.writeSnapshot(StoredTimelineEnvelope(scope = scopeA, revision = 1L, writtenAtMillis = 100L))
        store.writeSnapshot(StoredTimelineEnvelope(scope = scopeB, revision = 1L, writtenAtMillis = 100L))

        assertNotNull(store.readSnapshot(scopeA))
        assertNotNull(store.readSnapshot(scopeB))

        store.clearForBackend("backend-A")
        assertNull(store.readSnapshot(scopeA))
        assertNotNull(store.readSnapshot(scopeB))
    }

    @Test
    fun corruptActiveUsesFallbackAndCarriesHighWaterRevision() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope(backendId = "b1", conversationId = "c1")

        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 1L)))
        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 2L)))
        val manifestId = requireNotNull(
            db.confirmedTimelineSnapshotDao().getHeadMetadata(scope.backendId, scope.conversationId)?.activeManifestId,
        )
        val original = requireNotNull(db.confirmedTimelineSnapshotDao().getChunk(manifestId, 0))
        val corruptChunk = db.openHelper.writableDatabase.compileStatement(
            """
            UPDATE confirmed_timeline_snapshot_chunks
            SET payload = ?
            WHERE manifest_id = ? AND chunk_index = 0
            """.trimIndent(),
        )
        corruptChunk.bindBlob(1, ByteArray(original.size))
        corruptChunk.bindString(2, manifestId)
        corruptChunk.executeUpdateDelete()

        val read = store.readSnapshotResult(scope)
        assertTrue(read is ConfirmedTimelineReadResult.Fallback)
        read as ConfirmedTimelineReadResult.Fallback
        assertEquals(1L, read.snapshot.revision)
        assertEquals(2L, read.highWaterRevision)
        assertEquals(SnapshotReadFailure.CHECKSUM_MISMATCH, read.activeFailure)
        assertFalse(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 2L)))
        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 3L)))

        val healedHead = requireNotNull(db.confirmedTimelineSnapshotDao().getHeadMetadata(scope.backendId, scope.conversationId))
        val healedFallback = requireNotNull(healedHead.fallbackManifestId)
        assertEquals(1L, requireNotNull(db.confirmedTimelineSnapshotDao().getManifest(healedFallback)).revision)
    }

    @Test
    fun missingActiveManifestReadsFallbackWithoutLoweringHighWater() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope(backendId = "b1", conversationId = "missing-active")
        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 1L)))
        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 2L)))

        db.openHelper.writableDatabase.execSQL(
            "UPDATE confirmed_timeline_snapshots SET active_manifest_id = NULL " +
                "WHERE backend_id = ? AND conversation_id = ?",
            arrayOf(scope.backendId, scope.conversationId),
        )

        val read = store.readSnapshotResult(scope) as ConfirmedTimelineReadResult.Fallback
        assertEquals(1L, read.snapshot.revision)
        assertEquals(2L, read.highWaterRevision)
        assertEquals(SnapshotReadFailure.MISSING, read.activeFailure)
    }

    @Test
    fun oneFourAndEightMiBSnapshotsUseBoundedChunks() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope(backendId = "large", conversationId = "pixel-9-pro")

        listOf(1, 4, 8).forEachIndexed { index, mebibytes ->
            val content = "x".repeat(mebibytes * 1024 * 1024)
            val envelope = StoredTimelineEnvelope(
                scope = scope,
                revision = index + 1L,
                events = listOf(
                    StoredTimelineEvent(
                        position = 1.0,
                        otid = "large-$mebibytes",
                        content = content,
                        serverId = "server-$mebibytes",
                        messageType = "USER",
                        dateIso = "2026-08-24T00:00:00Z",
                    )
                ),
            )
            assertTrue("$mebibytes MiB snapshot should store", store.writeSnapshot(envelope))
            val read = requireNotNull(store.readSnapshot(scope))
            assertEquals(content.length, read.events.single().content.length)

            db.openHelper.readableDatabase.query(
                "SELECT MAX(length(payload)), COUNT(*) FROM confirmed_timeline_snapshot_chunks " +
                    "WHERE manifest_id = (SELECT active_manifest_id FROM confirmed_timeline_snapshots " +
                    "WHERE backend_id = ? AND conversation_id = ?)",
                arrayOf(scope.backendId, scope.conversationId),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getInt(0) <= RoomConfirmedTimelineStore.CHUNK_SIZE_BYTES)
                assertTrue(cursor.getInt(1) >= mebibytes * 8)
            }
        }
    }

    @Test
    fun migration10To11CopiesEveryRowInBoundedChunks() {
        val fixture = LegacyMigrationDatabase(ApplicationProvider.getApplicationContext())
        val snapshots = listOf(
            LegacySnapshotFixture.create("legacy-conversation", revision = 7L, contentBytes = 1024 * 1024),
            LegacySnapshotFixture.create("chunk-boundary", revision = 8L, contentBytes = 128 * 1024),
        )
        try {
            fixture.writeVersion10(snapshots)
            val observations = fixture.migrateAndObserve(snapshots)

            observations.zip(snapshots).forEach { (observation, snapshot) ->
                assertEquals(snapshot.envelope.revision, observation.highWaterRevision)
                assertTrue(observation.maxChunkBytes <= RoomConfirmedTimelineStore.CHUNK_SIZE_BYTES)
                assertEquals(snapshot.payload.encodeToByteArray().size.toLong(), observation.totalBytes)
                assertTrue(observation.chunkCount >= 2)
            }
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun normalizedBootstrapReconstructsRichTwoThousandEventHistory() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope("normalized", "large-history", "agent")
        val envelope = StoredTimelineEnvelope(
            scope = scope,
            revision = 2_001L,
            liveCursor = "live-2000",
            backfillCursor = "backfill-1",
            releasedOlderCount = 7,
            events = (0 until 2_000).map { index -> event(index) },
            writtenAtMillis = 9_999L,
        )

        assertTrue(store.writeSnapshot(envelope))
        assertEquals(envelope, store.readSnapshot(scope))
        assertEquals(2_000, db.confirmedTimelineSnapshotDao().getNormalizedRows(scope.backendId, scope.conversationId).size)

        db.confirmedTimelineSnapshotDao().deleteHead(scope.backendId, scope.conversationId)
        db.confirmedTimelineSnapshotDao().deleteManifestsForScope(scope.backendId, scope.conversationId)
        assertEquals(envelope, store.readSnapshot(scope))
    }

    @Test
    fun normalizedReaderRejectsInvalidHeadAndRowShapes() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope("normalized", "corruption", "agent")
        val envelope = StoredTimelineEnvelope(
            scope = scope,
            revision = 2L,
            events = listOf(event(0), event(1)),
            writtenAtMillis = 10L,
        )
        assertTrue(store.writeSnapshot(envelope))
        assertEquals(envelope, store.readSnapshot(scope))
        val dao = db.confirmedTimelineSnapshotDao()
        val originalHead = requireNotNull(dao.getNormalizedHead(scope.backendId, scope.conversationId))
        val originalRows = dao.getNormalizedRows(scope.backendId, scope.conversationId)
        dao.deleteHead(scope.backendId, scope.conversationId)
        dao.deleteManifestsForScope(scope.backendId, scope.conversationId)

        suspend fun assertRejected(
            expected: SnapshotReadFailure,
            head: NormalizedTimelineSnapshotHeadEntity = originalHead,
            rows: List<NormalizedTimelineSnapshotRowEntity> = originalRows,
        ) {
            dao.deleteNormalizedHead(scope.backendId, scope.conversationId)
            dao.deleteNormalizedRows(scope.backendId, scope.conversationId)
            dao.insertNormalizedRows(rows)
            dao.insertNormalizedHead(head)
            val result = store.readSnapshotResult(scope) as ConfirmedTimelineReadResult.ReconciliationRequired
            assertEquals(expected, result.failure)
        }

        assertRejected(SnapshotReadFailure.SCHEMA_MISMATCH, head = originalHead.copy(storageLayoutVersion = 99))
        assertRejected(SnapshotReadFailure.SCOPE_MISMATCH, head = originalHead.copy(agentId = "other-agent"))
        assertRejected(SnapshotReadFailure.LENGTH_MISMATCH, rows = originalRows.dropLast(1))
        assertRejected(SnapshotReadFailure.METADATA_INVALID, rows = originalRows.mapIndexed { index, row -> row.copy(eventOrder = index + 1) })
        assertRejected(SnapshotReadFailure.CHECKSUM_MISMATCH, rows = originalRows.mapIndexed { index, row -> if (index == 0) row.copy(checksum = "0".repeat(64)) else row })
        assertRejected(SnapshotReadFailure.CORRUPT_ENCODING, rows = originalRows.mapIndexed { index, row ->
            if (index == 0) row.copy(payload = "not-json".encodeToByteArray(), checksum = sha256("not-json".encodeToByteArray())) else row
        })
        assertRejected(SnapshotReadFailure.CHECKSUM_MISMATCH, head = originalHead.copy(rootDigest = "f".repeat(64)))
    }

    @Test
    fun cancellationDuringBootstrapRollsBackRowsAndHead() = runTest {
        val db = inMemoryDatabase()
        val scope = TimelineScope("normalized", "cancelled-bootstrap", "agent")
        val writer = RoomConfirmedTimelineStore(db)
        val envelope = StoredTimelineEnvelope(
            scope = scope,
            revision = 1L,
            events = (0 until 600).map { index -> event(index) },
            writtenAtMillis = 10L,
        )
        assertTrue(writer.writeSnapshot(envelope))
        val cancellingStore = RoomConfirmedTimelineStore(db) { batch ->
            if (batch == 1) throw CancellationException("cancel bootstrap")
        }

        try {
            cancellingStore.readSnapshot(scope)
            throw AssertionError("bootstrap cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected: Room rolls the transaction back before head publication.
        }

        val dao = db.confirmedTimelineSnapshotDao()
        assertNull(dao.getNormalizedHead(scope.backendId, scope.conversationId))
        assertTrue(dao.getNormalizedRows(scope.backendId, scope.conversationId).isEmpty())
        assertEquals(envelope, writer.readSnapshot(scope))
    }

    private fun event(index: Int) = StoredTimelineEvent(
        position = index.toDouble(),
        otid = "otid-$index",
        content = "message-$index",
        serverId = "server-$index",
        messageType = if (index % 2 == 0) "USER" else "ASSISTANT",
        dateIso = "2026-08-24T00:00:00Z",
        runId = "run-${index / 10}",
        stepId = "step-$index",
        seqId = index,
    )

    private data class LegacySnapshotFixture(
        val envelope: StoredTimelineEnvelope,
        val payload: String,
    ) {
        companion object {
            fun create(conversationId: String, revision: Long, contentBytes: Int): LegacySnapshotFixture {
                val scope = TimelineScope("legacy-backend", conversationId, "agent")
                val envelope = StoredTimelineEnvelope(
                    scope = scope,
                    revision = revision,
                    events = listOf(
                        StoredTimelineEvent(
                            position = 1.0,
                            otid = "legacy-$conversationId",
                            content = "z".repeat(contentBytes),
                            serverId = "server-$conversationId",
                            messageType = "USER",
                            dateIso = "2026-08-24T00:00:00Z",
                        )
                    ),
                    writtenAtMillis = 1234L,
                )
                return LegacySnapshotFixture(envelope, TimelineSnapshotCodec.encode(envelope))
            }
        }
    }

    private data class MigratedSnapshotObservation(
        val highWaterRevision: Long,
        val maxChunkBytes: Int,
        val totalBytes: Long,
        val chunkCount: Int,
    )

    private class LegacyMigrationDatabase(
        private val context: Context,
    ) {
        private val name = "timeline-migration-${System.nanoTime()}.db"

        fun writeVersion10(snapshots: List<LegacySnapshotFixture>) {
            openHelper(LegacySchema).use { helper ->
                snapshots.forEach { snapshot -> insertLegacySnapshot(helper.writableDatabase, snapshot) }
            }
        }

        fun migrateAndObserve(snapshots: List<LegacySnapshotFixture>): List<MigratedSnapshotObservation> =
            openHelper(ChunkedSchema).use { helper ->
                snapshots.map { snapshot -> helper.writableDatabase.observe(snapshot.envelope.scope) }
            }

        fun delete() {
            context.deleteDatabase(name)
        }

        private fun openHelper(schema: TestSchema): SupportSQLiteOpenHelper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(name)
                    .callback(schema.callback)
                    .build(),
            )

        private fun insertLegacySnapshot(db: SupportSQLiteDatabase, snapshot: LegacySnapshotFixture) {
            db.compileStatement("INSERT INTO confirmed_timeline_snapshots VALUES (?, ?, ?, ?, ?, ?, ?)").apply {
                val envelope = snapshot.envelope
                bindString(1, envelope.scope.backendId)
                bindString(2, envelope.scope.conversationId)
                bindString(3, requireNotNull(envelope.scope.agentId))
                bindLong(4, envelope.revision)
                bindLong(5, envelope.schemaVersion.toLong())
                bindString(6, snapshot.payload)
                bindLong(7, envelope.writtenAtMillis)
                executeInsert()
            }
        }

        private fun SupportSQLiteDatabase.observe(scope: TimelineScope): MigratedSnapshotObservation {
            val head = query(
                "SELECT high_water_revision, active_manifest_id FROM confirmed_timeline_snapshots " +
                    "WHERE backend_id = ? AND conversation_id = ?",
                arrayOf(scope.backendId, scope.conversationId),
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0) to cursor.getString(1)
            }
            return query(
                "SELECT MAX(length(payload)), SUM(length(payload)), COUNT(*) " +
                    "FROM confirmed_timeline_snapshot_chunks WHERE manifest_id = ?",
                arrayOf(head.second),
            ).use { chunks ->
                check(chunks.moveToFirst())
                MigratedSnapshotObservation(
                    highWaterRevision = head.first,
                    maxChunkBytes = chunks.getInt(0),
                    totalBytes = chunks.getLong(1),
                    chunkCount = chunks.getInt(2),
                )
            }
        }
    }

    private sealed class TestSchema(version: Int) {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) = create(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                upgrade(db, oldVersion, newVersion)
        }

        protected abstract fun create(db: SupportSQLiteDatabase)
        protected open fun upgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private data object LegacySchema : TestSchema(10) {
        override fun create(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE confirmed_timeline_snapshots (
                    backend_id TEXT NOT NULL, conversation_id TEXT NOT NULL, agent_id TEXT,
                    revision INTEGER NOT NULL, schema_version INTEGER NOT NULL,
                    payload_json TEXT NOT NULL, written_at_millis INTEGER NOT NULL,
                    PRIMARY KEY(backend_id, conversation_id)
                )
                """.trimIndent(),
            )
        }
    }

    private data object ChunkedSchema : TestSchema(11) {
        override fun create(db: SupportSQLiteDatabase) = Unit

        override fun upgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            assertEquals(10, oldVersion)
            assertEquals(11, newVersion)
            LettaDatabaseMigrations.MIGRATION_10_11.migrate(db)
        }
    }
}
