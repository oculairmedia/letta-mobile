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
import kotlinx.coroutines.test.advanceTimeBy
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
import org.junit.Assert.assertFalse
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.TimelineRevision
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlan
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitFailure

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
        assertEquals(3, persisted?.events?.size)
        // Review round 2 item 3. `revision == events.size` was the wrong invariant: it
        // conflates "no revision was burned" with "no coalescing happened", and coalescing is
        // a property this PR deliberately introduces. Whether the retried content lands in its
        // own commit or coalesced with the next one is timing-dependent, so ANY hard-coded
        // number here is incidental -- both 2 and 3 are legitimate outcomes.
        //
        // Assert the property itself instead: the durable revision equals the number of
        // SUCCESSFUL commits, never successes + rejections. That holds under any coalescing.
        assertEquals(
            "a rejected write must not consume a revision",
            store.successfulCommits.toLong(),
            persisted?.revision,
        )
        assertTrue("the forced rejection must actually have been exercised", store.rejections == 1)
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

    /**
     * Dogfood round 2, item 1: a long streamed response must not persist per delta.
     *
     * Before this, every applied stream frame scheduled a persist behind a 100 ms debounce.
     * On the Pixel a 2,161-event conversation took 589-721 ms per commit, so commits queued
     * faster than they drained: back-to-back persistence windows, heap to the 256 MiB ceiling
     * and 34-79 skipped frames. Fail-on-revert: remove the streaming defer and the commit
     * count here rises with the frame count instead of staying bounded.
     */
    @Test
    fun aLongStreamedResponseYieldsABoundedCommitCount() = runTest {
        val db = inMemoryDatabase()
        val store = CountingStore(RoomConfirmedTimelineStore(db))
        val scope = TimelineScope(backendId = "backend", conversationId = "conv-stream", agentId = "agent")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loop = newLoop(scope, store, dispatcher)

        loop.turnStarted()
        // advanceUntilIdle() would ALSO run the armed 5 s deadline on every iteration, which
        // makes the bound unobservable. Advance by the frame interval instead so virtual time
        // is controlled: 40 deltas x 100 ms = 4 s, which is inside one safety window.
        repeat(40) { index ->
            loop.ingestStreamEvent(
                UserMessage(id = "msg-$index", date = FIXTURE_DATE, contentRaw = JsonPrimitive("delta $index")),
            )
            advanceTimeBy(100)
        }
        val commitsInsideFirstWindow = store.commits
        assertEquals(
            "40 deltas inside one 5 s window must produce no streaming commits at all",
            0,
            commitsInsideFirstWindow,
        )

        // Cross the deadline: exactly one safety flush, regardless of how many deltas arrived.
        advanceTimeBy(1_500)
        advanceUntilIdle()
        val commitsAfterOneWindow = store.commits
        assertEquals(
            "crossing the safety deadline must produce exactly one commit",
            1,
            commitsAfterOneWindow,
        )
        val commitsDuringStreaming = commitsAfterOneWindow

        // Review round 2 item 1: deliberately NO manual flushSnapshotNow() here. Calling the
        // internal flush seam is what masked the real defect -- turnEnded cleared turnActive
        // but scheduled nothing, so a turn whose last delta was deferred stayed memory-only
        // and the test passed anyway. Durability must be proven through the production
        // turn-end path alone.
        loop.turnEnded(clean = true)

        // Await the BACKGROUND persist that turnEnded scheduled. Two things must both be
        // driven, which is why this loop does both on every iteration:
        //  - advanceUntilIdle() runs the persist coroutine, which lives on the test scheduler;
        //  - Thread.sleep yields the JVM thread so Room's internal Dispatchers.IO hop, which
        //    the test scheduler does NOT drive, can actually complete.
        // Driving only one of them makes this pass in isolation and fail under a loaded suite.
        //
        // Deliberately NOT flushSnapshotNow(): awaiting that suspend seam is what let the
        // missing turnEnded scheduling go unnoticed in the first place.
        var persistedEvents: Int? = null
        // Budget deliberately generous: this settles a real Dispatchers.IO write, and a
        // sibling test in this class stages 600 KiB payloads, so the shared dispatcher can be
        // busy. The loop exits as soon as the expected state is observed, so a larger bound
        // costs nothing when the write is quick -- it only stops a loaded run from failing on
        // a deadline rather than on the property under test.
        for (attempt in 0 until 1_000) {
            advanceUntilIdle()
            persistedEvents = store.readSnapshot(scope)?.events?.size
            if (persistedEvents == 40) break
            @Suppress("BlockingMethodInNonBlockingContext")
            Thread.sleep(10)
        }

        assertEquals(
            "turnEnded alone must durably persist the completed turn",
            40,
            persistedEvents,
        )
        loop.closeAndJoin()
    }

    /**
     * Dogfood round 2, item 2: a stale holder must stop, not spin.
     *
     * The capture showed duplicate holders repeatedly planning and being rejected, each
     * rejection costing a full O(N) plan that could never commit. Fail-on-revert: without the
     * detach, further ingests keep reaching the store.
     */
    @Test
    fun aStaleHolderStopsSchedulingInsteadOfReplanningForever() = runTest {
        val db = inMemoryDatabase()
        val store = CountingStore(RoomConfirmedTimelineStore(db), alwaysStale = true)
        val scope = TimelineScope(backendId = "backend", conversationId = "conv-detach", agentId = "agent")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loop = newLoop(scope, store, dispatcher)

        loop.ingestStreamEvent(UserMessage(id = "msg-0", date = FIXTURE_DATE, contentRaw = JsonPrimitive("first")))
        loop.flushSnapshotNow()
        advanceUntilIdle()
        // Round 4: detachment is bounded at MAX_CONSECUTIVE_STALE_REJECTIONS rather than firing
        // on the first Stale, because first-stale detachment permanently stops a writer that
        // lost one transient race. Drive more attempts than the threshold and assert the spin
        // is capped -- the point is boundedness, not a specific count.
        repeat(6) { index ->
            loop.ingestStreamEvent(
                UserMessage(id = "spin-$index", date = FIXTURE_DATE, contentRaw = JsonPrimitive("spin $index")),
            )
            loop.flushSnapshotNow()
            advanceUntilIdle()
        }
        val commitsAfterFirstStale = store.commits
        assertTrue(
            "a holder that can never commit must stop within the threshold; observed $commitsAfterFirstStale",
            commitsAfterFirstStale <= STALE_DETACH_THRESHOLD + 1,
        )

        repeat(10) { index ->
            loop.ingestStreamEvent(
                UserMessage(id = "later-$index", date = FIXTURE_DATE, contentRaw = JsonPrimitive("later $index")),
            )
            advanceUntilIdle()
        }

        assertEquals(
            "a detached stale writer must not keep planning and rejecting",
            commitsAfterFirstStale,
            store.commits,
        )

        // Round 4 gap 2: the detach guard was only on scheduleSnapshotPersist, so a DIRECT
        // flush -- and closeAndJoin, which flushes -- still ran a full plan and a rejected
        // commit. Both must now cost nothing.
        loop.flushSnapshotNow()
        advanceUntilIdle()
        assertEquals(
            "an explicit flush must not bypass stale detachment",
            commitsAfterFirstStale,
            store.commits,
        )

        loop.closeAndJoin()
        advanceUntilIdle()
        assertEquals(
            "closing a detached writer must not attempt another commit",
            commitsAfterFirstStale,
            store.commits,
        )
    }

    /**
     * Round 5 item 1: the safety deadline must RE-ARM across successive windows.
     *
     * The one-shot timer used to schedule its safety write while `turnSafetyFlushJob` still
     * pointed at the executing coroutine, so a delta arriving before it completed saw
     * `isActive == true` and skipped arming the next window -- leaving the rest of a long turn
     * with no further bounded write. A first-window-only test cannot see that; this walks
     * several windows.
     */
    @Test
    fun theSafetyDeadlineReArmsAcrossSuccessiveWindows() = runTest {
        val db = inMemoryDatabase()
        val store = CountingStore(RoomConfirmedTimelineStore(db))
        val scope = TimelineScope(backendId = "backend", conversationId = "conv-rearm", agentId = "agent")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loop = newLoop(scope, store, dispatcher)

        com.letta.mobile.util.Telemetry.clear()
        loop.turnStarted()

        // Window 1: continuous deltas, nothing may be written before the deadline.
        repeat(40) { index ->
            loop.ingestStreamEvent(
                UserMessage(id = "w1-$index", date = FIXTURE_DATE, contentRaw = JsonPrimitive("w1 $index")),
            )
            advanceTimeBy(100)
        }
        assertEquals("no write may occur before the first deadline", 0, safetyFlushes(scope.conversationId))

        advanceTimeBy(1_500)
        // The commit hops to real Dispatchers.IO and holds persistMutex while it runs, which
        // virtual time cannot drive -- without settling here the NEXT window blocks on the
        // mutex and looks like a re-arm failure.
        assertEquals("exactly one safety flush in the first dirty window", 1, safetyFlushes(scope.conversationId))

        // Window 2: more deltas must re-arm and produce exactly one more.
        repeat(40) { index ->
            loop.ingestStreamEvent(
                UserMessage(id = "w2-$index", date = FIXTURE_DATE, contentRaw = JsonPrimitive("w2 $index")),
            )
            advanceTimeBy(100)
        }
        assertEquals("still one until the second deadline elapses", 1, safetyFlushes(scope.conversationId))
        advanceTimeBy(1_500)
        assertEquals("the deadline must RE-ARM: a second dirty window flushes exactly once", 2, safetyFlushes(scope.conversationId))

        // A CLEAN window -- no new deferred work -- must not write at all.
        advanceTimeBy(6_000)
        advanceUntilIdle()
        assertEquals("a clean window must not produce a flush", 2, safetyFlushes(scope.conversationId))

        // One more delta proves a fresh deadline still arms after an idle window.
        loop.ingestStreamEvent(UserMessage(id = "w3", date = FIXTURE_DATE, contentRaw = JsonPrimitive("w3")))
        advanceTimeBy(100)

        loop.turnEnded(clean = true)
        var persisted: Int? = null
        for (attempt in 0 until 200) {
            advanceUntilIdle()
            persisted = store.readSnapshot(scope)?.events?.size
            if (persisted == 81) break
            @Suppress("BlockingMethodInNonBlockingContext")
            Thread.sleep(10)
        }
        assertEquals("turn end must durably persist the whole turn", 81, persisted)
        val afterTerminal = safetyFlushes(scope.conversationId)

        // A timer from the last armed window must not fire a post-terminal write.
        advanceTimeBy(10_000)
        advanceUntilIdle()
        assertEquals("no late safety write may occur after the turn ended", afterTerminal, safetyFlushes(scope.conversationId))
        loop.closeAndJoin()
    }

    /**
     * Round 5 item 3: a durable legacy fallback breaks the consecutive-stale sequence.
     *
     * `Stale -> Stale -> durable fallback success -> Stale` used to reach the detach threshold
     * even though the writer had just made durable progress, because only `onDurableCommit`
     * reset the counter.
     */
    @Test
    fun aDurableFallbackResetsStaleHistoryAndKeepsTheWriterAttached() = runTest {
        val db = inMemoryDatabase()
        val store = OutcomeOverridingStore(RoomConfirmedTimelineStore(db))
        val scope = TimelineScope(backendId = "backend", conversationId = "conv-reset", agentId = "agent")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loop = newLoop(scope, store, dispatcher)

        loop.ingestStreamEvent(UserMessage(id = "m0", date = FIXTURE_DATE, contentRaw = JsonPrimitive("first")))
        loop.flushSnapshotNow()
        advanceUntilIdle()

        repeat(2) { index ->
            store.forceStaleOnce()
            loop.ingestStreamEvent(
                UserMessage(id = "s-$index", date = FIXTURE_DATE, contentRaw = JsonPrimitive("stale $index")),
            )
            loop.flushSnapshotNow()
            advanceUntilIdle()
        }

        // Durable progress via the legacy fallback.
        store.forceInvalidOnce()
        loop.ingestStreamEvent(UserMessage(id = "fb", date = FIXTURE_DATE, contentRaw = JsonPrimitive("fallback")))
        loop.flushSnapshotNow()
        advanceUntilIdle()

        // One more stale must NOT reach the threshold, because the fallback reset the run.
        store.forceStaleOnce()
        loop.ingestStreamEvent(UserMessage(id = "s-last", date = FIXTURE_DATE, contentRaw = JsonPrimitive("stale last")))
        loop.flushSnapshotNow()
        advanceUntilIdle()

        // A detached writer would persist nothing further, so durable content growing is the
        // property that actually proves it is still attached.
        val eventsBefore = store.readSnapshot(scope)?.events?.size ?: 0
        loop.ingestStreamEvent(UserMessage(id = "after", date = FIXTURE_DATE, contentRaw = JsonPrimitive("after")))
        loop.flushSnapshotNow()
        advanceUntilIdle()
        assertTrue(
            "the writer must remain attached and still persist normally",
            (store.readSnapshot(scope)?.events?.size ?: 0) > eventsBefore,
        )
        assertNotNull(store.readSnapshot(scope))
        loop.closeAndJoin()
    }

    /**
     * Counts SAFETY_FLUSH persist requests the loop ENQUEUED -- scheduling events, not
     * completed durable writes. The distinction matters: a scheduled request may still be
     * coalesced downstream, so this number bounds writes from above rather than equalling
     * them, and the assertions here are written against that meaning.
     *
     * Scoped by conversation because `Telemetry` is process-global: an unscoped version of
     * this helper passed in isolation and failed in the full suite.
     *
     * Deliberately measured from the loop's own telemetry rather than from store commit
     * counts: the commit hops to Room's real IO dispatcher, so under virtual time the test
     * clock races ahead of the write and a later flush can find the timeline already
     * persisted. The scheduling event is the deterministic signal for the property under test
     * -- whether the deadline re-armed -- and it cannot be faked by coalescing downstream.
     */
    private fun safetyFlushes(conversationId: String): Int =
        com.letta.mobile.util.Telemetry.snapshot().count {
            it.name == "snapshotPersist.scheduled" &&
                it.attrs["reason"] == SnapshotPersistReason.SAFETY_FLUSH.name &&
                it.attrs["conversationId"] == conversationId
        }

    /**
     * Round 5 item 2: telemetry must distinguish two concurrent holders of the SAME
     * agent + conversation.
     *
     * That pair is exactly what the device capture could not tell apart, so a write could not
     * be attributed to a holder. A stable per-loop id makes duplicate-holder behaviour
     * mechanically visible in a log.
     */
    @Test
    fun telemetryDistinguishesTwoHoldersOfTheSameAgentAndConversation() = runTest {
        val db = inMemoryDatabase()
        val scope = TimelineScope(backendId = "backend", conversationId = "conv-holders", agentId = "agent")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = newLoop(scope, RoomConfirmedTimelineStore(db), dispatcher)
        val second = newLoop(scope, RoomConfirmedTimelineStore(db), dispatcher)

        com.letta.mobile.util.Telemetry.clear()
        first.ingestStreamEvent(UserMessage(id = "a", date = FIXTURE_DATE, contentRaw = JsonPrimitive("a")))
        first.flushSnapshotNow()
        advanceUntilIdle()
        second.ingestStreamEvent(UserMessage(id = "b", date = FIXTURE_DATE, contentRaw = JsonPrimitive("b")))
        second.flushSnapshotNow()
        advanceUntilIdle()

        val holders = com.letta.mobile.util.Telemetry.snapshot()
            .filter { it.name.startsWith("snapshotPersist.") && it.attrs["conversationId"] == scope.conversationId }
            .mapNotNull { it.attrs["holderId"]?.toString() }
            .toSet()

        assertEquals(
            "two concurrent holders of one agent+conversation must be distinguishable in telemetry",
            2,
            holders.size,
        )
        // conversationId + agentId alone cannot do this -- that is the whole point.
        assertTrue(holders.all { it.isNotBlank() })

        first.closeAndJoin()
        second.closeAndJoin()
    }

    /** Counts commit attempts that actually reach the store, and can force permanent Stale. */
    private class CountingStore(
        private val delegate: RoomConfirmedTimelineStore,
        private val alwaysStale: Boolean = false,
    ) : com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore by delegate {
        var commits: Int = 0
            private set

        override suspend fun commitNormalized(
            plan: com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlan,
            fullEnvelope: com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope,
            checkpointLegacyEnvelope: Boolean,
        ): com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult {
            commits += 1
            if (alwaysStale) {
                return com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult.Stale(
                    com.letta.mobile.data.timeline.snapshot.TimelineRevision(999L),
                )
            }
            return delegate.commitNormalized(plan, fullEnvelope, checkpointLegacyEnvelope)
        }
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
    /**
     * Round 6 item 5: the legacy fallback must not STRAND normalized state behind it.
     *
     * The fallback writes LEGACY only, but it advances the loop's acknowledged envelope to the
     * fallback revision. Normalized state stays at the previous revision, so every later commit
     * plans baseRevision = N against a normalized head still at N-1, is rejected Stale forever,
     * and eventually detaches the writer -- one durable fallback permanently disabling
     * durability. onInvalidPlan now reconciles through the store's read path, which bootstraps
     * normalized rows from the validated legacy snapshot.
     *
     * Proven on the PRODUCTION path (no direct store calls): after the fallback, ordinary
     * appends must keep reaching durable normalized state.
     */
    @Test
    fun aLegacyFallbackDoesNotStrandNormalizedStateBehindIt() = runTest {
        val db = inMemoryDatabase()
        val scope = TimelineScope(backendId = "backend", conversationId = "conv-strand", agentId = "agent")
        val store = OutcomeOverridingStore(RoomConfirmedTimelineStore(db))
        val loop = newLoop(scope, store, StandardTestDispatcher(testScheduler))
        val dao = db.confirmedTimelineSnapshotDao()

        loop.ingestStreamEvent(UserMessage(id = "m0", date = FIXTURE_DATE, contentRaw = JsonPrimitive("first")))
        loop.flushSnapshotNow()
        advanceUntilIdle()

        store.forceInvalidOnce()
        loop.ingestStreamEvent(UserMessage(id = "m1", date = FIXTURE_DATE, contentRaw = JsonPrimitive("fallback")))
        loop.flushSnapshotNow()
        advanceUntilIdle()

        val commitsAfterFallback = store.successfulCommits
        repeat(3) { index ->
            loop.ingestStreamEvent(
                UserMessage(id = "after-$index", date = FIXTURE_DATE, contentRaw = JsonPrimitive("after")),
            )
            loop.flushSnapshotNow()
            advanceUntilIdle()
        }

        assertTrue(
            "normalized commits must keep succeeding after a legacy fallback -- " +
                "permanent Stale here is the stranding regression",
            store.successfulCommits > commitsAfterFallback,
        )
        assertEquals(
            "the normalized head must carry the post-fallback events, not be frozen behind them",
            5,
            store.readSnapshot(scope)?.events?.size,
        )
        assertEquals(
            "the head must still be owned by this agent",
            scope.agentId,
            dao.getNormalizedHead(scope.backendId, scope.conversationId)?.agentId,
        )
        loop.closeAndJoin()
    }

    /**
     * Round 6 item 5, ownership half: a fallback from agent B must not overwrite agent A's
     * legacy state. publishHead's ownership guard (PR #1439) is the production guard; this
     * pins it from the loop's own fallback path rather than by calling the store directly.
     */
    @Test
    fun aLegacyFallbackCannotOverwriteAnotherAgentsState() = runTest {
        val db = inMemoryDatabase()
        val owner = TimelineScope(backendId = "backend", conversationId = "conv-shared", agentId = "agent-a")
        val intruder = TimelineScope(backendId = "backend", conversationId = "conv-shared", agentId = "agent-b")

        val ownerLoop = newLoop(owner, RoomConfirmedTimelineStore(db), StandardTestDispatcher(testScheduler))
        ownerLoop.ingestStreamEvent(UserMessage(id = "a1", date = FIXTURE_DATE, contentRaw = JsonPrimitive("owned")))
        ownerLoop.flushSnapshotNow()
        advanceUntilIdle()
        val ownerEvents = RoomConfirmedTimelineStore(db).readSnapshot(owner)?.events?.size
        assertEquals(1, ownerEvents)

        val intruderStore = OutcomeOverridingStore(RoomConfirmedTimelineStore(db))
        val intruderLoop = newLoop(intruder, intruderStore, StandardTestDispatcher(testScheduler))
        intruderStore.forceInvalidOnce()
        intruderLoop.ingestStreamEvent(
            UserMessage(id = "b1", date = FIXTURE_DATE, contentRaw = JsonPrimitive("intruding")),
        )
        intruderLoop.flushSnapshotNow()
        advanceUntilIdle()

        assertEquals(
            "agent A's timeline must survive agent B's legacy fallback untouched",
            ownerEvents,
            RoomConfirmedTimelineStore(db).readSnapshot(owner)?.events?.size,
        )
        assertEquals(
            "ownership must remain with agent A",
            owner.agentId,
            db.confirmedTimelineSnapshotDao()
                .getNormalizedHead(owner.backendId, owner.conversationId)?.agentId,
        )
        ownerLoop.closeAndJoin()
        intruderLoop.closeAndJoin()
    }

    /**
     * Round 7 blocker 1: the PRODUCTION-shaped stranding case -- a REAL oversized event.
     *
     * The round-6 test used a synthetic Invalid over a perfectly representable envelope, so the
     * post-fallback read bootstrapped normalized to N and the bug could not appear. That was a
     * false pass. With a genuine OVERSIZED_ROW normalized cannot represent the envelope at all:
     * legacy persists N, normalized is stuck at N-1, and advancing the planning baseline to N
     * makes every later commit fail CAS forever.
     *
     * Sequence: commit -> oversized event forces the fallback -> REMOVE the oversized event ->
     * the next commit must reach durable normalized state.
     */
    @Test
    fun aRealOversizedEventDoesNotStrandNormalizedPlanningOnceItIsRemoved() = runTest {
        val db = inMemoryDatabase()
        val scope = TimelineScope(backendId = "backend", conversationId = "conv-oversized", agentId = "agent")
        val store = RoomConfirmedTimelineStore(db)
        val loop = newLoop(scope, store, StandardTestDispatcher(testScheduler))
        com.letta.mobile.util.Telemetry.clear()
        val dao = db.confirmedTimelineSnapshotDao()

        loop.ingestStreamEvent(UserMessage(id = "keep", date = FIXTURE_DATE, contentRaw = JsonPrimitive("keep")))
        loop.flushSnapshotNow()
        advanceUntilIdle()
        val baselineRevision = dao.getNormalizedHead(scope.backendId, scope.conversationId)?.revision
        assertNotNull(baselineRevision)

        // A genuinely unrepresentable row: over NORMALIZED_MAX_ROW_PAYLOAD_BYTES (512 KiB).
        // Sent as an OPTIMISTIC LOCAL so it can later collapse into its server version -- that
        // collapse is how an event actually leaves this timeline. Re-ingesting a stream message
        // under the same id cannot express removal: shouldDropDuplicateStreamMessage discards
        // the repeat, so the oversized body would simply persist.
        loop.ingestStreamEvent(
            UserMessage(
                id = "huge",
                date = FIXTURE_DATE,
                contentRaw = JsonPrimitive("x".repeat(600 * 1024)),
                otid = "huge-otid",
            ),
        )
        loop.flushSnapshotNow()
        advanceUntilIdle()

        assertEquals(
            "normalized cannot represent the oversized envelope, so it must stay put",
            baselineRevision,
            dao.getNormalizedHead(scope.backendId, scope.conversationId)?.revision,
        )
        assertTrue(
            "the oversized row must actually have forced the legacy fallback -- without this " +
                "the test proves nothing about stranding",
            com.letta.mobile.util.Telemetry.snapshot().any {
                it.name == "snapshotPersist.invalidRejected" &&
                    it.attrs["conversationId"] == scope.conversationId
            },
        )

        // NOTE: the recovery leg ("remove the oversized event, commit succeeds") is NOT driven
        // here. TimelineSyncLoop exposes no event-removal API; re-ingesting the same id is
        // discarded by shouldDropDuplicateStreamMessage, and an optimistic-local collapse does
        // not evict the row either. Attempting it produced a VACUOUS test -- the oversized guard
        // never even fired -- which is the same false-pass shape flagged in review, so it is not
        // left in. The baseline property that recovery depends on is proven deterministically in
        // aStrandedNormalizedRevisionKeepsThePlanningBaselineBehind below.
        loop.closeAndJoin()
    }

    /**
     * Round 7 blocker 2: a cross-agent fallback at a STRICTLY HIGHER revision.
     *
     * The round-6 version had both agents at the same revision, so publishHead's revision guard
     * fired first and the ownership branch was never reached -- a false pass. publishHead in
     * fact had NO ownership check, so a higher-revision cross-agent write replaced the head and
     * restamped agent_id to the intruder.
     */
    @Test
    fun aHigherRevisionCrossAgentFallbackCannotReplaceTheLegacyHead() = runTest {
        val db = inMemoryDatabase()
        val owner = TimelineScope(backendId = "backend", conversationId = "conv-cross", agentId = "agent-a")
        val intruder = TimelineScope(backendId = "backend", conversationId = "conv-cross", agentId = "agent-b")
        val dao = db.confirmedTimelineSnapshotDao()

        val ownerStore = RoomConfirmedTimelineStore(db)
        val ownerLoop = newLoop(owner, ownerStore, StandardTestDispatcher(testScheduler))
        repeat(2) { index ->
            ownerLoop.ingestStreamEvent(
                UserMessage(id = "a$index", date = FIXTURE_DATE, contentRaw = JsonPrimitive("owned")),
            )
            ownerLoop.flushSnapshotNow()
            advanceUntilIdle()
        }
        val headBefore = requireNotNull(dao.getHeadMetadata(owner.backendId, owner.conversationId))
        val rowsBefore = dao.getNormalizedRowDigestProjection(owner.backendId, owner.conversationId)
        val readBefore = requireNotNull(ownerStore.readSnapshot(owner)).events.size

        // Agent B writes at a STRICTLY HIGHER revision -- past the revision guard.
        val intruderEnvelope = StoredTimelineEnvelope(
            scope = intruder,
            revision = headBefore.highWaterRevision + 5,
            events = listOf(
                StoredTimelineEvent(
                    position = 1.0,
                    otid = "intruder",
                    content = "intruding",
                    serverId = "server-intruder",
                    messageType = "USER",
                    dateIso = "2026-08-24T00:00:00Z",
                ),
            ),
            writtenAtMillis = 9_000L,
        )
        assertFalse(
            "a higher-revision write by another agent must be refused",
            RoomConfirmedTimelineStore(db).writeSnapshot(intruderEnvelope),
        )

        assertEquals(
            "agent A's legacy head must be untouched -- owner, revision, manifests, all of it",
            headBefore,
            dao.getHeadMetadata(owner.backendId, owner.conversationId),
        )
        assertEquals(
            "agent A's normalized rows must be untouched",
            rowsBefore,
            dao.getNormalizedRowDigestProjection(owner.backendId, owner.conversationId),
        )
        assertEquals(
            "agent A must still read its own timeline",
            readBefore,
            ownerStore.readSnapshot(owner)?.events?.size,
        )
        ownerLoop.closeAndJoin()
    }

    /**
     * Round 7 blocker 1, the production hunk under deterministic control.
     *
     * A legacy fallback leaves LEGACY at N and NORMALIZED at N-1. The planning baseline must
     * follow NORMALIZED, not legacy: advance it to N and every later commit plans against a
     * revision normalized never reached, is rejected Stale, and the writer eventually detaches.
     *
     * The store double reports the lagging normalized revision and enforces CAS exactly as Room
     * does, so the next commit's fate is decided purely by which baseline the loop kept.
     *
     * FAIL-ON-REVERT: making the advance unconditional turns the final commit into a Stale
     * rejection and fails this test.
     */
    @Test
    fun aStrandedNormalizedRevisionKeepsThePlanningBaselineBehind() = runTest {
        val scope = TimelineScope(backendId = "backend", conversationId = "conv-baseline", agentId = "agent")
        val store = StrandingStore()
        val loop = newLoop(scope, store, StandardTestDispatcher(testScheduler))

        loop.ingestStreamEvent(UserMessage(id = "e1", date = FIXTURE_DATE, contentRaw = JsonPrimitive("one")))
        loop.flushSnapshotNow()
        advanceUntilIdle()
        assertEquals("the first commit must reach normalized", 1L, store.normalizedRevision)

        // This one cannot be represented: legacy advances, normalized cannot.
        store.forceInvalidOnce()
        loop.ingestStreamEvent(UserMessage(id = "e2", date = FIXTURE_DATE, contentRaw = JsonPrimitive("two")))
        loop.flushSnapshotNow()
        advanceUntilIdle()
        assertTrue("the fallback must have written legacy", store.legacyWrites > 0)
        assertEquals("normalized must be stranded one revision behind", 1L, store.normalizedRevision)

        // The next commit decides it: baseline N-1 matches normalized and commits; baseline N
        // does not and is rejected Stale.
        loop.ingestStreamEvent(UserMessage(id = "e3", date = FIXTURE_DATE, contentRaw = JsonPrimitive("three")))
        loop.flushSnapshotNow()
        advanceUntilIdle()

        assertEquals(
            "the commit after a fallback must be planned against the revision NORMALIZED holds; " +
                "a stale rejection here means the baseline followed legacy instead",
            0,
            store.staleRejections,
        )
        assertTrue("normalized must have advanced past the stranding", store.normalizedRevision > 1L)
        loop.closeAndJoin()
    }

    /**
     * Models the asymmetry that makes stranding possible: legacy accepts a write normalized
     * cannot represent, and normalized enforces CAS against its own revision.
     */
    private class StrandingStore : com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore {
        var normalizedRevision: Long = 0L
            private set
        var legacyWrites: Int = 0
            private set
        var staleRejections: Int = 0
            private set
        private var invalidOnce = false
        private var latest: StoredTimelineEnvelope? = null

        fun forceInvalidOnce() { invalidOnce = true }

        override suspend fun normalizedHeadRevision(scope: TimelineScope): Long? =
            normalizedRevision.takeIf { it > 0L }

        override suspend fun commitNormalized(
            plan: NormalizedTimelineCommitPlan,
            fullEnvelope: StoredTimelineEnvelope,
            checkpointLegacyEnvelope: Boolean,
        ): NormalizedTimelineWriteResult {
            if (invalidOnce) {
                invalidOnce = false
                return NormalizedTimelineWriteResult.Invalid(NormalizedTimelineCommitFailure.OVERSIZED_ROW)
            }
            val base = when (plan) {
                is NormalizedTimelineCommitPlan.Apply -> plan.commit.baseRevision.value
                is NormalizedTimelineCommitPlan.NoOp -> plan.baseRevision.value
                else -> return NormalizedTimelineWriteResult.Invalid(
                    NormalizedTimelineCommitFailure.OVERSIZED_ROW,
                )
            }
            if (base != normalizedRevision) {
                staleRejections++
                return NormalizedTimelineWriteResult.Stale(TimelineRevision(normalizedRevision))
            }
            normalizedRevision = fullEnvelope.revision
            latest = fullEnvelope
            return NormalizedTimelineWriteResult.Committed(TimelineRevision(fullEnvelope.revision))
        }

        override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean {
            legacyWrites++
            latest = envelope
            return true
        }

        override suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope? = latest
        override suspend fun deleteSnapshot(scope: TimelineScope) { latest = null }
        override suspend fun clearForBackend(backendId: String) { latest = null }
        override suspend fun prune(backendId: String, maxRetainedConversations: Int) = Unit
    }

    private class OutcomeOverridingStore(
        private val delegate: RoomConfirmedTimelineStore,
    ) : com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore by delegate {
        private var staleOnce = false
        private var invalidOnce = false

        /** Commits the store actually accepted, for the revision-accounting invariant. */
        var successfulCommits: Int = 0
            private set

        /** Forced rejections, so a test can prove the rejection path was exercised at all. */
        var rejections: Int = 0
            private set

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
                rejections += 1
                return com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult.Stale(
                    com.letta.mobile.data.timeline.snapshot.TimelineRevision(0L),
                )
            }
            if (invalidOnce) {
                invalidOnce = false
                rejections += 1
                return com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult.Invalid(
                    com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitFailure.OVERSIZED_ROW,
                )
            }
            return delegate.commitNormalized(plan, fullEnvelope, checkpointLegacyEnvelope).also { result ->
                val landed = result is com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult.Committed ||
                    result is com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult.NoOp
                if (landed) successfulCommits += 1
            }
        }
    }

    private companion object {
        const val FIXTURE_DATE = "2026-08-24T12:00:00Z"

        /** Mirrors TimelineSyncLoop.MAX_CONSECUTIVE_STALE_REJECTIONS, which is internal to sharedLogic. */
        const val STALE_DETACH_THRESHOLD = 3
    }
}
