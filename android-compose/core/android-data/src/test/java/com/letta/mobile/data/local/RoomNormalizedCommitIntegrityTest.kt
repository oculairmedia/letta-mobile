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
        // Round 6 item 1: capture the COMPLETE pre-fault state, not just cardinalities.
        // Counting rows and reading the head revision cannot see a transaction that mutated
        // the two existing rows while rolling back the new ones -- the count and the revision
        // would both still match and the test would pass on corrupt data.
        val rowsBefore = dao.getNormalizedRowDigestProjection(scope.backendId, scope.conversationId)
        val headBefore = requireNotNull(dao.getNormalizedHead(scope.backendId, scope.conversationId))
        val readBefore = requireNotNull(store(db).readSnapshot(scope)) {
            "the owner must be able to read its own snapshot before the fault is injected"
        }
        assertEquals(2, rowsBefore.size)
        assertEquals(1L, headBefore.revision)
        assertEquals(2, readBefore.events.size)

        val faulting = RoomConfirmedTimelineStore(
            db,
            beforeHeadPublicationObserver = { throw CancellationException("fault before head publication") },
        )
        val second = envelope(scope, revision = 2L, events = 4)
        runCatching { commit(faulting, first, second) }

        assertEquals(
            "every row's identity, order, kind and digest must be byte-identical after rollback",
            rowsBefore,
            dao.getNormalizedRowDigestProjection(scope.backendId, scope.conversationId),
        )
        val headAfter = requireNotNull(dao.getNormalizedHead(scope.backendId, scope.conversationId))
        assertEquals(
            "the complete head -- owner, revision, rootDigest, cursors, released count, schema -- must be unchanged",
            headBefore,
            headAfter,
        )
        assertEquals(
            "the owner must still read the identical pre-fault timeline",
            readBefore.events,
            store(db).readSnapshot(scope)?.events,
        )

        // The store is not wedged: a clean commit still succeeds afterwards.
        val healthy = RoomConfirmedTimelineStore(db)
        assertTrue(commit(healthy, first, second) is NormalizedTimelineWriteResult.Committed)
        assertEquals(4, store(db).let { it.readSnapshot(scope)?.events?.size })
    }

    private fun store(db: LettaDatabase) = RoomConfirmedTimelineStore(db)

    /**
     * Round 4 gap 1: an UNSCOPED writer must not alter an OWNED head, on either branch.
     *
     * The ownership predicate previously accepted `scope.agentId == null` even when the head
     * had an owner. Apply then wrote `agentId = scope.agentId`, clearing ownership outright,
     * and NoOp recomputed the root under a null scope against an owned head so the owner's
     * later reads failed checksum. Both are same-revision cases, so revision CAS cannot catch
     * them -- only the ownership check can.
     *
     * Asserts head, rows and digest are byte-identical afterwards, and the owner still reads.
     */
    @Test
    fun anUnscopedApplyCannotClearOwnershipOfAnOwnedHead() = runTest {
        val db = database()
        val store = RoomConfirmedTimelineStore(db)
        val owner = TimelineScope(backendId = "b1", conversationId = "conv-unscoped", agentId = "agent-owner")
        val unscoped = TimelineScope(backendId = "b1", conversationId = "conv-unscoped")

        val first = envelope(owner, revision = 1L, events = 3)
        assertTrue(commit(store, null, first) is NormalizedTimelineWriteResult.Committed)

        val dao = db.confirmedTimelineSnapshotDao()
        val headBefore = dao.getNormalizedHead("b1", "conv-unscoped")
        val rowsBefore = dao.getNormalizedRowDigestProjection("b1", "conv-unscoped")

        // Same base revision, so only ownership can reject this.
        val target = envelope(unscoped, revision = 2L, events = 5)
        val result = commit(store, first.copy(scope = unscoped), target)
        assertTrue(
            "an unscoped Apply must not take an owned head",
            result is NormalizedTimelineWriteResult.Stale,
        )

        val headAfter = dao.getNormalizedHead("b1", "conv-unscoped")
        assertEquals("ownership must survive", headBefore?.agentId, headAfter?.agentId)
        assertEquals("revision must not move", headBefore?.revision, headAfter?.revision)
        assertEquals("root digest must be byte-identical", headBefore?.rootDigest, headAfter?.rootDigest)
        assertEquals("row set must be untouched", rowsBefore.size, dao.getNormalizedRowDigestProjection("b1", "conv-unscoped").size)
        assertNotNull("the owner must still read its own timeline", store.readSnapshot(owner))
        assertEquals(3, store.readSnapshot(owner)?.events?.size)
    }

    /**
     * Round 4 gap 1, NoOp branch. A null-scope no-op previously advanced the revision and
     * recomputed the root under the wrong identity, which is the shape that made reads fail
     * checksum rather than merely losing ownership.
     */
    @Test
    fun anUnscopedNoOpCannotRewriteAnOwnedHead() = runTest {
        val db = database()
        val store = RoomConfirmedTimelineStore(db)
        val owner = TimelineScope(backendId = "b1", conversationId = "conv-unscoped-noop", agentId = "agent-owner")
        val unscoped = TimelineScope(backendId = "b1", conversationId = "conv-unscoped-noop")

        val first = envelope(owner, revision = 1L, events = 3)
        assertTrue(commit(store, null, first) is NormalizedTimelineWriteResult.Committed)

        val dao = db.confirmedTimelineSnapshotDao()
        val headBefore = dao.getNormalizedHead("b1", "conv-unscoped-noop")

        // Content-identical under an unscoped identity => a NoOp plan at the same base revision.
        val unchanged = first.copy(scope = unscoped, revision = 2L, writtenAtMillis = 9_000L)
        val plan = NormalizedTimelineCommitPlanner.plan(first.copy(scope = unscoped), unchanged)
        assertTrue(plan is NormalizedTimelineCommitPlan.NoOp)
        assertTrue(
            "an unscoped NoOp must not rewrite an owned head",
            store.commitNormalized(plan, unchanged, false) is NormalizedTimelineWriteResult.Stale,
        )

        val headAfter = dao.getNormalizedHead("b1", "conv-unscoped-noop")
        assertEquals(headBefore?.agentId, headAfter?.agentId)
        assertEquals(headBefore?.revision, headAfter?.revision)
        assertEquals("root digest must be byte-identical", headBefore?.rootDigest, headAfter?.rootDigest)
        assertNotNull("the owner read must still validate", store.readSnapshot(owner))
    }

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
