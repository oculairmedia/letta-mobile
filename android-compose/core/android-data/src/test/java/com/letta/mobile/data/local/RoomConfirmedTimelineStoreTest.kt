package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineReadResult
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitFailure
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlan
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlanner
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult
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
        val cancellingStore = RoomConfirmedTimelineStore(db, bootstrapBatchObserver = { batch ->
            if (batch == 1) throw CancellationException("cancel bootstrap")
        })

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

    @Test
    fun laterLegacyRevisionRefreshesExistingNormalizedSnapshot() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope("normalized", "refresh", "agent")
        val first = StoredTimelineEnvelope(scope = scope, revision = 1L, events = listOf(event(0)), writtenAtMillis = 10L)
        val second = first.copy(revision = 2L, events = listOf(event(0), event(1)), writtenAtMillis = 20L)

        assertTrue(store.writeSnapshot(first))
        assertEquals(first, store.readSnapshot(scope))
        assertTrue(store.writeSnapshot(second))
        assertEquals(second, store.readSnapshot(scope))

        db.confirmedTimelineSnapshotDao().deleteHead(scope.backendId, scope.conversationId)
        db.confirmedTimelineSnapshotDao().deleteManifestsForScope(scope.backendId, scope.conversationId)
        assertEquals(second, store.readSnapshot(scope))
    }

    @Test
    fun positivePruneRemovesNormalizedFallbackForDroppedConversation() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val retained = TimelineScope("normalized", "retained", "agent")
        val dropped = TimelineScope("normalized", "dropped", "agent")
        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = dropped, revision = 1L, writtenAtMillis = 10L)))
        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = retained, revision = 1L, writtenAtMillis = 20L)))
        assertNotNull(store.readSnapshot(dropped))
        assertNotNull(store.readSnapshot(retained))

        store.prune(retained.backendId, maxRetainedConversations = 1)

        assertNull(store.readSnapshot(dropped))
        assertNotNull(store.readSnapshot(retained))
    }

    @Test
    fun normalizedRootDigestDistinguishesNullLiteralAndDelimiters() {
        val scope = TimelineScope("backend|id", "conversation", null)
        val base = StoredTimelineEnvelope(scope = scope, revision = 1L, liveCursor = null, backfillCursor = "a|b")
        val nullLiteral = base.copy(liveCursor = "null")
        val delimiterShift = base.copy(liveCursor = "a", backfillCursor = "b|null")

        assertFalse(normalizedRootDigest(base, emptyList()) == normalizedRootDigest(nullLiteral, emptyList()))
        assertFalse(normalizedRootDigest(base, emptyList()) == normalizedRootDigest(delimiterShift, emptyList()))
    }

    // -- letta-mobile-827s9.4: incremental normalized-commit (commitNormalized) coverage --

    private fun plan(previous: StoredTimelineEnvelope?, current: StoredTimelineEnvelope) =
        NormalizedTimelineCommitPlanner.plan(previous, current)

    @Test
    fun initialNormalizedCommitAppliesWithoutAnyLegacyWrite() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope("normalized", "initial-commit", "agent")
        val envelope = StoredTimelineEnvelope(scope = scope, revision = 1L, events = listOf(event(0), event(1)))

        val result = store.commitNormalized(plan(null, envelope), envelope, checkpointLegacyEnvelope = false)

        assertTrue(result is NormalizedTimelineWriteResult.Committed)
        assertEquals(envelope, store.readSnapshot(scope))
        assertEquals(0, db.confirmedTimelineSnapshotDao().countManifests(scope.backendId, scope.conversationId))
    }

    @Test
    fun oneEventAppendCommitsWithoutGrowingLegacyManifestCount() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope("normalized", "append", "agent")
        val v1 = StoredTimelineEnvelope(scope = scope, revision = 1L, events = listOf(event(0)))
        assertTrue(store.commitNormalized(plan(null, v1), v1, checkpointLegacyEnvelope = true) is NormalizedTimelineWriteResult.Committed)
        val manifestsAfterCheckpoint = db.confirmedTimelineSnapshotDao().countManifests(scope.backendId, scope.conversationId)
        assertEquals(1, manifestsAfterCheckpoint)

        val v2 = v1.copy(revision = 2L, events = listOf(event(0), event(1)))
        val result = store.commitNormalized(plan(v1, v2), v2, checkpointLegacyEnvelope = false)

        assertTrue(result is NormalizedTimelineWriteResult.Committed)
        // This is the encode-count proof: an ordinary one-event append performs zero
        // TimelineSnapshotCodec.encode calls on the production write path. Every legacy
        // writeSnapshot call inserts exactly one manifest row (see createWritePlan), so an
        // unchanged manifest count after this append proves no full-envelope encode ran.
        assertEquals(manifestsAfterCheckpoint, db.confirmedTimelineSnapshotDao().countManifests(scope.backendId, scope.conversationId))
        assertEquals(v2, store.readSnapshot(scope))
        assertEquals(2, db.confirmedTimelineSnapshotDao().getNormalizedRowDigestProjection(scope.backendId, scope.conversationId).size)
    }

    @Test
    fun oneEventContentUpdateTouchesOnlyThatRow() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope("normalized", "update", "agent")
        val v1 = StoredTimelineEnvelope(scope = scope, revision = 1L, events = listOf(event(0), event(1)))
        assertTrue(store.commitNormalized(plan(null, v1), v1) is NormalizedTimelineWriteResult.Committed)

        val updatedEvent0 = event(0).copy(content = "edited content")
        val v2 = v1.copy(revision = 2L, events = listOf(updatedEvent0, event(1)))
        val result = store.commitNormalized(plan(v1, v2), v2)

        assertTrue(result is NormalizedTimelineWriteResult.Committed)
        assertEquals(v2, store.readSnapshot(scope))
        assertEquals("edited content", store.readSnapshot(scope)?.events?.first()?.content)
    }

    @Test
    fun deleteRemovesRowSymmetricallyFromNormalizedStorage() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope("normalized", "delete", "agent")
        val v1 = StoredTimelineEnvelope(scope = scope, revision = 1L, events = listOf(event(0), event(1), event(2)))
        assertTrue(store.commitNormalized(plan(null, v1), v1) is NormalizedTimelineWriteResult.Committed)

        val v2 = v1.copy(revision = 2L, events = listOf(event(0), event(2)))
        val result = store.commitNormalized(plan(v1, v2), v2)

        assertTrue(result is NormalizedTimelineWriteResult.Committed)
        assertEquals(v2, store.readSnapshot(scope))
        assertEquals(2, db.confirmedTimelineSnapshotDao().getNormalizedRowDigestProjection(scope.backendId, scope.conversationId).size)
    }

    @Test
    fun reorderUpdatesAffectedRowOrdersAndRootDigest() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope("normalized", "reorder", "agent")
        val v1 = StoredTimelineEnvelope(scope = scope, revision = 1L, events = listOf(event(0), event(1), event(2)))
        assertTrue(store.commitNormalized(plan(null, v1), v1) is NormalizedTimelineWriteResult.Committed)
        val digestBefore = requireNotNull(db.confirmedTimelineSnapshotDao().getNormalizedHead(scope.backendId, scope.conversationId)).rootDigest

        val v2 = v1.copy(revision = 2L, events = listOf(event(1), event(0), event(2)))
        val result = store.commitNormalized(plan(v1, v2), v2)

        assertTrue(result is NormalizedTimelineWriteResult.Committed)
        assertEquals(v2, store.readSnapshot(scope))
        val digestAfter = requireNotNull(db.confirmedTimelineSnapshotDao().getNormalizedHead(scope.backendId, scope.conversationId)).rootDigest
        assertFalse(digestBefore == digestAfter)
    }

    @Test
    fun cursorOnlyMetadataUpdateWritesNoRows() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope("normalized", "cursor-only", "agent")
        val v1 = StoredTimelineEnvelope(scope = scope, revision = 1L, liveCursor = "c1", events = listOf(event(0)))
        assertTrue(store.commitNormalized(plan(null, v1), v1) is NormalizedTimelineWriteResult.Committed)
        val rowsBefore = db.confirmedTimelineSnapshotDao().getNormalizedRows(scope.backendId, scope.conversationId)
            .map { row -> Triple(row.identityPrimary, row.identitySecondary, row.checksum) }

        val v2 = v1.copy(revision = 2L, liveCursor = "c2")
        val commitPlan = plan(v1, v2)
        assertTrue("cursor-only change must still be an Apply plan (empty upserts/deletes)", commitPlan is NormalizedTimelineCommitPlan.Apply)
        commitPlan as NormalizedTimelineCommitPlan.Apply
        assertTrue(commitPlan.commit.upserts.isEmpty())
        assertTrue(commitPlan.commit.deletes.isEmpty())

        val result = store.commitNormalized(commitPlan, v2)

        assertTrue(result is NormalizedTimelineWriteResult.Committed)
        assertEquals("c2", store.readSnapshot(scope)?.liveCursor)
        val rowsAfter = db.confirmedTimelineSnapshotDao().getNormalizedRows(scope.backendId, scope.conversationId)
            .map { row -> Triple(row.identityPrimary, row.identitySecondary, row.checksum) }
        assertEquals(rowsBefore, rowsAfter)
    }

    @Test
    fun noOpRevisionAdvanceWritesZeroRowsThenAcceptsAChangedCommitAfterward() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope("normalized", "no-op-then-change", "agent")
        val v1 = StoredTimelineEnvelope(scope = scope, revision = 1L, events = listOf(event(0)))
        assertTrue(store.commitNormalized(plan(null, v1), v1) is NormalizedTimelineWriteResult.Committed)
        suspend fun rowFingerprints() = db.confirmedTimelineSnapshotDao().getNormalizedRows(scope.backendId, scope.conversationId)
            .map { row -> Triple(row.identityPrimary, row.identitySecondary, row.checksum) }
        val rowsBefore = rowFingerprints()

        // Identical content, only revision advances -> planner must produce NoOp.
        val v2 = v1.copy(revision = 2L)
        val noOpPlan = plan(v1, v2)
        assertTrue(noOpPlan is NormalizedTimelineCommitPlan.NoOp)
        val noOpResult = store.commitNormalized(noOpPlan, v2)
        assertTrue(noOpResult is NormalizedTimelineWriteResult.NoOp)
        assertEquals(2L, (noOpResult as NormalizedTimelineWriteResult.NoOp).revision.value)
        assertEquals(rowsBefore, rowFingerprints())
        assertEquals(2L, requireNotNull(db.confirmedTimelineSnapshotDao().getNormalizedHead(scope.backendId, scope.conversationId)).revision)

        // A real change immediately after the no-op must plan against the no-op's target
        // revision (2), not the pre-no-op revision (1).
        val v3 = v2.copy(revision = 3L, events = listOf(event(0), event(1)))
        val changedResult = store.commitNormalized(plan(v2, v3), v3)
        assertTrue(changedResult is NormalizedTimelineWriteResult.Committed)
        assertEquals(v3, store.readSnapshot(scope))
    }

    @Test
    fun staleConcurrentWriterIsRejectedWithoutMutatingRowsOrHead() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope("normalized", "stale-writer", "agent")
        val v1 = StoredTimelineEnvelope(scope = scope, revision = 1L, events = listOf(event(0)))
        assertTrue(store.commitNormalized(plan(null, v1), v1) is NormalizedTimelineWriteResult.Committed)

        // Two "writers" both plan from the same v1 baseline; the first wins.
        val winner = v1.copy(revision = 2L, events = listOf(event(0), event(1)))
        val loser = v1.copy(revision = 2L, events = listOf(event(0), event(2)))
        val winnerPlan = plan(v1, winner)
        val loserPlan = plan(v1, loser)

        assertTrue(store.commitNormalized(winnerPlan, winner) is NormalizedTimelineWriteResult.Committed)
        val staleResult = store.commitNormalized(loserPlan, loser)

        assertTrue(staleResult is NormalizedTimelineWriteResult.Stale)
        assertEquals(2L, (staleResult as NormalizedTimelineWriteResult.Stale).highWaterRevision.value)
        // The winner's state must be untouched by the rejected loser.
        assertEquals(winner, store.readSnapshot(scope))
    }

    @Test
    fun oversizedRowFailsClosedAndNeverEntersNormalizedStorage() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope("normalized", "oversized", "agent")
        val v1 = StoredTimelineEnvelope(scope = scope, revision = 1L, events = listOf(event(0)))
        assertTrue(store.commitNormalized(plan(null, v1), v1) is NormalizedTimelineWriteResult.Committed)

        val oversizedEvent = event(1).copy(content = "x".repeat(600 * 1024))
        val v2 = v1.copy(revision = 2L, events = listOf(event(0), oversizedEvent))
        val result = store.commitNormalized(plan(v1, v2), v2)

        assertTrue(result is NormalizedTimelineWriteResult.Invalid)
        assertEquals(NormalizedTimelineCommitFailure.OVERSIZED_ROW, (result as NormalizedTimelineWriteResult.Invalid).reason)
        // Fail-closed: the oversized row must not have entered normalized storage, and the
        // existing durable state (v1) must be exactly what a subsequent read still returns.
        assertEquals(1, db.confirmedTimelineSnapshotDao().getNormalizedRowDigestProjection(scope.backendId, scope.conversationId).size)
        assertEquals(v1, store.readSnapshot(scope))
    }

    @Test
    fun corruptedRowAfterIncrementalCommitFailsClosedOnRead() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope("normalized", "corrupt-after-commit", "agent")
        val v1 = StoredTimelineEnvelope(scope = scope, revision = 1L, events = listOf(event(0), event(1)))
        assertTrue(store.commitNormalized(plan(null, v1), v1) is NormalizedTimelineWriteResult.Committed)
        assertEquals(v1, store.readSnapshot(scope))

        val dao = db.confirmedTimelineSnapshotDao()
        val rows = dao.getNormalizedRows(scope.backendId, scope.conversationId)
        val corrupted = rows.first().copy(checksum = "0".repeat(64))
        dao.deleteNormalizedRow(scope.backendId, scope.conversationId, corrupted.identityPrimary, corrupted.identitySecondary)
        dao.upsertNormalizedRows(listOf(corrupted))

        val read = store.readSnapshotResult(scope)
        assertTrue(read is ConfirmedTimelineReadResult.ReconciliationRequired)
        assertEquals(SnapshotReadFailure.CHECKSUM_MISMATCH, (read as ConfirmedTimelineReadResult.ReconciliationRequired).failure)
    }

    @Test
    fun cancellationDuringIncrementalCommitRowMutationRollsBackRowsAndHead() = runTest {
        val db = inMemoryDatabase()
        val scope = TimelineScope("normalized", "cancelled-commit", "agent")
        val writer = RoomConfirmedTimelineStore(db)
        val v1 = StoredTimelineEnvelope(scope = scope, revision = 1L, events = listOf(event(0)))
        assertTrue(writer.commitNormalized(plan(null, v1), v1) is NormalizedTimelineWriteResult.Committed)

        val cancellingStore = RoomConfirmedTimelineStore(db, commitBatchObserver = { batch ->
            if (batch == 0) throw CancellationException("cancel commit")
        })
        val v2 = v1.copy(revision = 2L, events = listOf(event(0), event(1)))

        try {
            cancellingStore.commitNormalized(plan(v1, v2), v2)
            throw AssertionError("incremental commit cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected: Room rolls the whole transaction (rows + head) back before publication.
        }

        // Both the row set and the head must reflect only the pre-cancellation durable state.
        assertEquals(v1, writer.readSnapshot(scope))
        assertEquals(1, db.confirmedTimelineSnapshotDao().getNormalizedRowDigestProjection(scope.backendId, scope.conversationId).size)
        assertEquals(1L, requireNotNull(db.confirmedTimelineSnapshotDao().getNormalizedHead(scope.backendId, scope.conversationId)).revision)
    }

    @Test
    fun pruneRemovesNormalizedRowsWrittenIncrementallyForDroppedConversation() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val retained = TimelineScope("normalized", "retained-incremental", "agent")
        val dropped = TimelineScope("normalized", "dropped-incremental", "agent")
        val droppedEnvelope = StoredTimelineEnvelope(scope = dropped, revision = 1L, events = listOf(event(0)), writtenAtMillis = 10L)
        val retainedEnvelope = StoredTimelineEnvelope(scope = retained, revision = 1L, events = listOf(event(0)), writtenAtMillis = 20L)
        assertTrue(store.commitNormalized(plan(null, droppedEnvelope), droppedEnvelope, checkpointLegacyEnvelope = true) is NormalizedTimelineWriteResult.Committed)
        assertTrue(store.commitNormalized(plan(null, retainedEnvelope), retainedEnvelope, checkpointLegacyEnvelope = true) is NormalizedTimelineWriteResult.Committed)

        store.prune(retained.backendId, maxRetainedConversations = 1)

        assertNull(store.readSnapshot(dropped))
        assertNotNull(store.readSnapshot(retained))
        assertTrue(db.confirmedTimelineSnapshotDao().getNormalizedRowDigestProjection(dropped.backendId, dropped.conversationId).isEmpty())
    }

    @Test
    fun legacyCheckpointFailureDoesNotDowngradeAnAlreadyDurableCommit() = runTest {
        val db = inMemoryDatabase()
        val scope = TimelineScope("normalized", "checkpoint-failure", "agent")
        val v1 = StoredTimelineEnvelope(scope = scope, revision = 1L, events = listOf(event(0)))
        val store = RoomConfirmedTimelineStore(
            db,
            legacyCheckpointFailureInjector = { IllegalStateException("simulated legacy checkpoint I/O failure") },
        )

        // checkpointLegacyEnvelope=true forces the (deliberately faulted) legacy checkpoint to
        // run right after the normalized commit succeeds. The normalized commit is the durable
        // write of record, so its result must still be Committed and the row/head state must
        // still be readable, even though the checkpoint threw.
        val result = store.commitNormalized(plan(null, v1), v1, checkpointLegacyEnvelope = true)

        assertTrue(result is NormalizedTimelineWriteResult.Committed)
        assertEquals(v1, store.readSnapshot(scope))
        // The faulted legacy write must not have left a partial/garbage manifest behind.
        assertEquals(0, db.confirmedTimelineSnapshotDao().countManifests(scope.backendId, scope.conversationId))
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
