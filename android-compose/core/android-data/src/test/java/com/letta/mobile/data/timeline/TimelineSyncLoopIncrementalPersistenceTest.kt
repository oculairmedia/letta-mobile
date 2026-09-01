package com.letta.mobile.data.timeline

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.local.LettaDatabase
import com.letta.mobile.data.local.RoomConfirmedTimelineStore
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-827s9.4: proves the *production* [TimelineSyncLoop] persistence path -- not a
 * fake, not [com.letta.mobile.data.timeline.snapshot.InMemoryConfirmedTimelineStore] -- is
 * actually wired to [RoomConfirmedTimelineStore.commitNormalized] and stops growing the legacy
 * v11 manifest table on ordinary mutations.
 *
 * Every legacy `writeSnapshot` call inserts exactly one row into
 * `confirmed_timeline_snapshot_manifests` (see `RoomConfirmedTimelineStore.createWritePlan`),
 * and that insert is the only place on the write path that runs
 * `TimelineSnapshotCodec.encode`. So the manifest-row count doubles as a faithful,
 * production-code-observing proxy for full-envelope encode calls. If a future change reverts
 * `TimelineSyncLoop.persistCurrentSnapshot` back to calling `confirmedTimelineStore.writeSnapshot`
 * for ordinary mutations, [manifestCountStaysBoundedAcrossOrdinaryAppends] fails because the
 * manifest count grows once per append instead of staying flat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineSyncLoopIncrementalPersistenceTest {
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
    fun manifestCountStaysBoundedAcrossOrdinaryAppends() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope(backendId = "backend", conversationId = "conv-incremental", agentId = "agent")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loop = TimelineSyncLoop(
            messageApi = FakeSyncApi().let(::MessageApiTimelineTransport),
            conversationId = scope.conversationId,
            agentId = scope.agentId,
            scope = CoroutineScope(dispatcher),
            startStreamSubscriber = false,
            confirmedTimelineStore = store,
            timelineScope = scope,
            ioDispatcher = dispatcher,
        )

        // First persist is the "initial commit" -- production always checkpoints it, so exactly
        // one legacy manifest is expected here.
        loop.ingestStreamEvent(UserMessage(id = "msg-0", date = FIXTURE_DATE, contentRaw = JsonPrimitive("first")))
        loop.flushSnapshotNow()
        advanceUntilIdle()
        val dao = db.confirmedTimelineSnapshotDao()
        val manifestsAfterInitialCommit = dao.countManifests(scope.backendId, scope.conversationId)
        assertEquals(1, manifestsAfterInitialCommit)

        // 10 further ordinary one-event appends. None of these are the checkpoint cadence
        // boundary (LEGACY_CHECKPOINT_INTERVAL = 25), so the manifest count must not move.
        repeat(10) { index ->
            loop.ingestStreamEvent(
                UserMessage(id = "msg-${index + 1}", date = FIXTURE_DATE, contentRaw = JsonPrimitive("event ${index + 1}")),
            )
            loop.flushSnapshotNow()
            advanceUntilIdle()
        }

        assertEquals(manifestsAfterInitialCommit, dao.countManifests(scope.backendId, scope.conversationId))

        val persisted = store.readSnapshot(scope)
        assertNotNull(persisted)
        assertEquals(11, persisted?.events?.size)
        assertTrue(dao.getNormalizedRowDigestProjection(scope.backendId, scope.conversationId).size == 11)

        loop.closeAndJoin()
    }

    /**
     * PM review item 1. `stageLegacyCheckpoint` used to rethrow `CancellationException` after
     * the normalized CAS transaction had ALREADY committed, so the loop saw an exception for a
     * durable write and never advanced `lastPersistedEnvelope`.
     *
     * Fail-on-revert: with the rethrow restored, the first commit reports failure, the
     * acknowledged envelope stays null, and the SECOND append therefore plans baseRevision 0
     * against a normalized head already at revision 1 -- CAS rejects it Stale and event 2 never
     * persists. Asserting both events are readable proves the durable result survived.
     */
    @Test
    fun postCommitCheckpointCancellationDoesNotDowngradeADurableWrite() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(
            db,
            legacyCheckpointFailureInjector = { CancellationException("injected checkpoint cancellation") },
        )
        val scope = TimelineScope(backendId = "backend", conversationId = "conv-cancel", agentId = "agent")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loop = newLoop(scope, store, dispatcher)

        // The first persist is always a checkpoint, so the injector fires on it.
        loop.ingestStreamEvent(UserMessage(id = "msg-0", date = FIXTURE_DATE, contentRaw = JsonPrimitive("first")))
        loop.flushSnapshotNow()
        advanceUntilIdle()

        loop.ingestStreamEvent(UserMessage(id = "msg-1", date = FIXTURE_DATE, contentRaw = JsonPrimitive("second")))
        loop.flushSnapshotNow()
        advanceUntilIdle()

        val persisted = store.readSnapshot(scope)
        assertNotNull("the durable commit must survive a cancelled post-commit checkpoint", persisted)
        assertEquals(
            "acknowledged state must have advanced, so the follow-up append also commits",
            2,
            persisted?.events?.size,
        )
        loop.closeAndJoin()
    }

    /**
     * PM review item 2. A rejected write must not consume a revision, and the retry must
     * converge rather than leaving a permanent gap in the emitted revision sequence.
     */
    @Test
    fun aStaleRejectionConsumesNoRevisionAndTheRetryConverges() = runTest {
        val db = inMemoryDatabase()
        val delegate = RoomConfirmedTimelineStore(db)
        val store = OutcomeOverridingStore(delegate)
        val scope = TimelineScope(backendId = "backend", conversationId = "conv-stale", agentId = "agent")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loop = newLoop(scope, store, dispatcher)

        loop.ingestStreamEvent(UserMessage(id = "msg-0", date = FIXTURE_DATE, contentRaw = JsonPrimitive("first")))
        loop.flushSnapshotNow()
        advanceUntilIdle()
        val revisionAfterFirst = store.readSnapshot(scope)?.revision
        assertEquals(1L, revisionAfterFirst)

        // Force exactly one Stale, then let the real store handle everything after it.
        store.forceStaleOnce()
        loop.ingestStreamEvent(UserMessage(id = "msg-1", date = FIXTURE_DATE, contentRaw = JsonPrimitive("rejected")))
        loop.flushSnapshotNow()
        advanceUntilIdle()

        loop.ingestStreamEvent(UserMessage(id = "msg-2", date = FIXTURE_DATE, contentRaw = JsonPrimitive("retry")))
        loop.flushSnapshotNow()
        advanceUntilIdle()

        val persisted = store.readSnapshot(scope)
        assertNotNull(persisted)
        // The loop retries the rejected content on the next persist, so all three events land.
        assertEquals(3, persisted?.events?.size)
        // The real invariant, expressed rather than hard-coded: every event arrived in its own
        // successful commit, so a gapless revision sequence means revision == committed events.
        // A rejected attempt that burned a revision shows up here as revision 4 against 3
        // events -- which is exactly what happens if `snapshotRevision` is incremented before
        // the outcome is known.
        assertEquals(
            "a rejected write must not consume a revision",
            persisted?.events?.size?.toLong(),
            persisted?.revision,
        )
        loop.closeAndJoin()
    }

    /**
     * PM review item 3. `Invalid` is not transient -- an oversized row makes every later plan
     * Invalid too -- so without the explicit legacy fallback the conversation silently stops
     * being durable forever. Fail-on-revert: remove the fallback and the persisted snapshot
     * never advances past the pre-Invalid state.
     */
    @Test
    fun anInvalidPlanFallsBackToALegacyWriteAndPreservesDurableProgress() = runTest {
        val db = inMemoryDatabase()
        val delegate = RoomConfirmedTimelineStore(db)
        val store = OutcomeOverridingStore(delegate)
        val scope = TimelineScope(backendId = "backend", conversationId = "conv-invalid", agentId = "agent")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loop = newLoop(scope, store, dispatcher)

        loop.ingestStreamEvent(UserMessage(id = "msg-0", date = FIXTURE_DATE, contentRaw = JsonPrimitive("first")))
        loop.flushSnapshotNow()
        advanceUntilIdle()

        store.forceInvalidOnce()
        loop.ingestStreamEvent(UserMessage(id = "msg-1", date = FIXTURE_DATE, contentRaw = JsonPrimitive("oversized")))
        loop.flushSnapshotNow()
        advanceUntilIdle()

        val persisted = store.readSnapshot(scope)
        assertNotNull(persisted)
        assertEquals(
            "the legacy fallback must preserve durable progress through an Invalid plan",
            2,
            persisted?.events?.size,
        )

        // And the loop is not wedged: ordinary incremental commits resume afterwards.
        loop.ingestStreamEvent(UserMessage(id = "msg-2", date = FIXTURE_DATE, contentRaw = JsonPrimitive("after")))
        loop.flushSnapshotNow()
        advanceUntilIdle()
        assertEquals(3, store.readSnapshot(scope)?.events?.size)
        loop.closeAndJoin()
    }

    private fun newLoop(
        scope: TimelineScope,
        store: com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) = TimelineSyncLoop(
        messageApi = FakeSyncApi().let(::MessageApiTimelineTransport),
        conversationId = scope.conversationId,
        agentId = scope.agentId,
        scope = CoroutineScope(dispatcher),
        startStreamSubscriber = false,
        confirmedTimelineStore = store,
        timelineScope = scope,
        ioDispatcher = dispatcher,
    )

    /**
     * Delegating store that can force ONE `Stale` or ONE `Invalid` outcome and then behaves
     * exactly like the real Room store. Deliberately a thin decorator over the production
     * implementation rather than a hand-rolled fake, so these tests still exercise the real
     * commit path either side of the injected rejection.
     */
    private class OutcomeOverridingStore(
        private val delegate: RoomConfirmedTimelineStore,
    ) : com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore by delegate {
        private var staleOnce = false
        private var invalidOnce = false

        fun forceStaleOnce() {
            staleOnce = true
        }

        fun forceInvalidOnce() {
            invalidOnce = true
        }

        override suspend fun commitNormalized(
            plan: com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlan,
            fullEnvelope: com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope,
            checkpointLegacyEnvelope: Boolean,
        ): com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult {
            if (staleOnce) {
                staleOnce = false
                return com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult.Stale(
                    com.letta.mobile.data.timeline.snapshot.TimelineRevision(0L),
                )
            }
            if (invalidOnce) {
                invalidOnce = false
                return com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult.Invalid(
                    com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitFailure.OVERSIZED_ROW,
                )
            }
            return delegate.commitNormalized(plan, fullEnvelope, checkpointLegacyEnvelope)
        }
    }

    private companion object {
        const val FIXTURE_DATE = "2026-08-24T12:00:00Z"
    }
}
