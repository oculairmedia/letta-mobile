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
    fun migration10To11ChunksAndPreservesLegacySnapshot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "timeline-migration-${System.nanoTime()}.db"
        val scope = TimelineScope(backendId = "legacy-backend", conversationId = "legacy-conversation", agentId = "agent")
        val envelope = StoredTimelineEnvelope(
            scope = scope,
            revision = 7L,
            events = listOf(
                StoredTimelineEvent(
                    position = 1.0,
                    otid = "legacy",
                    content = "z".repeat(1024 * 1024),
                    serverId = "legacy-server",
                    messageType = "USER",
                    dateIso = "2026-08-24T00:00:00Z",
                )
            ),
            writtenAtMillis = 1234L,
        )
        val payload = TimelineSnapshotCodec.encode(envelope)

        val version10 = sqliteHelper(context, databaseName, 10, onCreate = { db ->
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
        })
        version10.writableDatabase.compileStatement(
            "INSERT INTO confirmed_timeline_snapshots VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).apply {
            bindString(1, scope.backendId)
            bindString(2, scope.conversationId)
            bindString(3, requireNotNull(scope.agentId))
            bindLong(4, 7L)
            bindLong(5, 1L)
            bindString(6, payload)
            bindLong(7, 1234L)
            executeInsert()
        }
        version10.close()

        val version11 = sqliteHelper(
            context = context,
            name = databaseName,
            version = 11,
            onCreate = {},
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(10, oldVersion)
                assertEquals(11, newVersion)
                LettaDatabaseMigrations.MIGRATION_10_11.migrate(db)
            },
        )
        val migrated = version11.writableDatabase
        migrated.query(
            "SELECT high_water_revision, active_manifest_id FROM confirmed_timeline_snapshots " +
                "WHERE backend_id = ? AND conversation_id = ?",
            arrayOf(scope.backendId, scope.conversationId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(7L, cursor.getLong(0))
            val manifestId = cursor.getString(1)
            migrated.query(
                "SELECT MAX(length(payload)), SUM(length(payload)), COUNT(*) " +
                    "FROM confirmed_timeline_snapshot_chunks WHERE manifest_id = ?",
                arrayOf(manifestId),
            ).use { chunks ->
                assertTrue(chunks.moveToFirst())
                assertTrue(chunks.getInt(0) <= RoomConfirmedTimelineStore.CHUNK_SIZE_BYTES)
                assertEquals(payload.encodeToByteArray().size.toLong(), chunks.getLong(1))
                assertTrue(chunks.getInt(2) > 8)
            }
        }
        version11.close()
        context.deleteDatabase(databaseName)
    }

    private fun sqliteHelper(
        context: Context,
        name: String,
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit,
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> },
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        onUpgrade(db, oldVersion, newVersion)
                }
            )
            .build(),
    )
}
