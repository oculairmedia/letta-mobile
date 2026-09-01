package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlan
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlanner
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-827s9.4, PM review items 4 and 5.
 *
 * The two named integrity gates from bead `.4` that the existing suite did not prove:
 * conflicting agent ownership on one conversation, and a fault immediately before normalized
 * head publication. Backend isolation and mid-batch cancellation are adjacent but neither
 * establishes these.
 *
 * Deliberately a SEPARATE, cohesive class rather than more cases appended to
 * `RoomConfirmedTimelineStoreTest` — CodeScene flagged that file for critical low cohesion,
 * and these tests share a single concern (incremental-commit integrity boundaries) and a
 * single fixture shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class RoomNormalizedCommitIntegrityTest {
    private var database: LettaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        database = null
    }

    private fun database(): LettaDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { database = it }
    }

    private fun envelope(scope: TimelineScope, revision: Long, events: Int) = StoredTimelineEnvelope(
        schemaVersion = 1,
        scope = scope,
        revision = revision,
        liveCursor = "srv-$revision",
        backfillCursor = "srv-1",
        releasedOlderCount = 0,
        events = (1..events).map { index ->
            StoredTimelineEvent(
                position = index.toDouble(),
                otid = "otid-$index",
                content = "event $index at revision $revision",
                serverId = "srv-$index",
                messageType = "USER",
                dateIso = "2026-09-01T00:00:00Z",
            )
        },
        writtenAtMillis = 1_000L + revision,
    )

    private suspend fun commit(
        store: RoomConfirmedTimelineStore,
        previous: StoredTimelineEnvelope?,
        target: StoredTimelineEnvelope,
    ): NormalizedTimelineWriteResult =
        store.commitNormalized(NormalizedTimelineCommitPlanner.plan(previous, target), target, false)

    /**
     * Named gate: same conversation id under CONFLICTING agent ownership.
     *
     * Backend isolation does not prove this — that varies `backendId`, which is part of the
     * row key. Here the backend and conversation are identical and only the agent differs,
     * which is the scope collision a subagent dispatch actually produces.
     */
    @Test
    fun aConflictingAgentCannotCommitOverAnotherAgentsConversation() = runTest {
        val db = database()
        val store = RoomConfirmedTimelineStore(db)
        val owner = TimelineScope(backendId = "b1", conversationId = "conv-shared", agentId = "agent-owner")
        val intruder = TimelineScope(backendId = "b1", conversationId = "conv-shared", agentId = "agent-intruder")

        val ownerFirst = envelope(owner, revision = 1L, events = 2)
        assertTrue(commit(store, null, ownerFirst) is NormalizedTimelineWriteResult.Committed)

        // Review round 2 item 2: this previously planned from null, so the intruder's commit
        // carried baseRevision 0 against a durable head at 1 and was rejected on REVISION.
        // That proved nothing about ownership -- it passed for the wrong reason.
        //
        // Now the intruder plans from the SAME baseline the owner just committed, so its base
        // revision matches exactly and revision-CAS alone would let it through. Only the
        // agentId ownership check can reject it.
        val intruderTarget = envelope(intruder, revision = 2L, events = 5)
        val intruderPlan = NormalizedTimelineCommitPlanner.plan(ownerFirst.copy(scope = intruder), intruderTarget)
        val intruderResult = store.commitNormalized(intruderPlan, intruderTarget, false)
        assertTrue(
            "a different agent must not commit over this conversation even at a matching base revision",
            intruderResult is NormalizedTimelineWriteResult.Stale,
        )

        // The owner still reads its own data, unchanged.
        val ownerRead = store.readSnapshot(owner)
        assertNotNull(ownerRead)
        assertEquals(2, ownerRead?.events?.size)
        assertEquals(1L, ownerRead?.revision)

        // And the intruder gets nothing rather than the owner's rows.
        assertNull(
            "a conflicting agent must not read the owner's normalized rows",
            store.readSnapshot(intruder),
        )
    }

    /**
     * Named gate: fault IMMEDIATELY BEFORE normalized head publication.
     *
     * Row mutations have already been applied inside the transaction at that point, so this
     * is the window where a head could be published over rows that never became durable. The
     * surrounding transaction must roll the rows back and leave the previous head intact.
     * Distinct from mid-batch cancellation, which faults before any head write is even
     * reached.
     */
    @Test
    fun aFaultImmediatelyBeforeHeadPublicationRollsBackRowsAndLeavesTheHeadIntact() = runTest {
        val db = database()
        val scope = TimelineScope(backendId = "b1", conversationId = "conv-head", agentId = "agent")

        val baseline = RoomConfirmedTimelineStore(db)
        val first = envelope(scope, revision = 1L, events = 2)
        assertTrue(commit(baseline, null, first) is NormalizedTimelineWriteResult.Committed)

        val dao = db.confirmedTimelineSnapshotDao()
        val rowsBefore = dao.getNormalizedRowDigestProjection(scope.backendId, scope.conversationId).size
        val headBefore = dao.getNormalizedHead(scope.backendId, scope.conversationId)
        assertEquals(2, rowsBefore)
        assertEquals(1L, headBefore?.revision)

        val faulting = RoomConfirmedTimelineStore(
            db,
            beforeHeadPublicationObserver = { throw CancellationException("fault before head publication") },
        )
        val second = envelope(scope, revision = 2L, events = 4)
        runCatching { commit(faulting, first, second) }

        assertEquals(
            "row mutations staged before the fault must roll back",
            rowsBefore,
            dao.getNormalizedRowDigestProjection(scope.backendId, scope.conversationId).size,
        )
        assertEquals(
            "the head must not advance past rows that never became durable",
            1L,
            dao.getNormalizedHead(scope.backendId, scope.conversationId)?.revision,
        )

        // The store is not wedged: a clean commit still succeeds afterwards.
        val healthy = RoomConfirmedTimelineStore(db)
        assertTrue(commit(healthy, first, second) is NormalizedTimelineWriteResult.Committed)
        assertEquals(4, store(db).let { it.readSnapshot(scope)?.events?.size })
    }

    private fun store(db: LettaDatabase) = RoomConfirmedTimelineStore(db)

    /** Guards the planner contract these gates rely on: no plan, no commit. */
    @Test
    fun anUnchangedEnvelopePlansNoOpRatherThanRewritingRows() = runTest {
        val db = database()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope(backendId = "b1", conversationId = "conv-noop", agentId = "agent")
        val first = envelope(scope, revision = 1L, events = 3)
        assertTrue(commit(store, null, first) is NormalizedTimelineWriteResult.Committed)

        val unchanged = first.copy(revision = 2L, writtenAtMillis = 2_000L)
        val plan = NormalizedTimelineCommitPlanner.plan(first, unchanged)
        assertTrue(plan is NormalizedTimelineCommitPlan.NoOp)
        assertTrue(store.commitNormalized(plan, unchanged, false) is NormalizedTimelineWriteResult.NoOp)
        assertEquals(2L, store.readSnapshot(scope)?.revision)
    }
}
