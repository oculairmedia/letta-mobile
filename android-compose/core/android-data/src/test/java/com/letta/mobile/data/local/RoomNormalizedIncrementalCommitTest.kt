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

/**
 * Incremental NORMALIZED commit behaviour, split out of RoomConfirmedTimelineStoreTest.
 *
 * That class had grown to cover three unrelated concerns -- legacy snapshot chunking and
 * migration, normalized bootstrap and read validation, and the incremental commit path -- which
 * CodeScene flagged as low cohesion. The incremental path is the subject of letta-mobile-827s9
 * and changes on its own cadence, so it gets its own class and its own fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class RoomNormalizedIncrementalCommitTest {
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

    private fun plan(previous: StoredTimelineEnvelope?, current: StoredTimelineEnvelope) =
        NormalizedTimelineCommitPlanner.plan(previous, current)
}
