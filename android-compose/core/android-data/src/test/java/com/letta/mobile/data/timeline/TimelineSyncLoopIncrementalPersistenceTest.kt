package com.letta.mobile.data.timeline

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.local.LettaDatabase
import com.letta.mobile.data.local.RoomConfirmedTimelineStore
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
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

    private companion object {
        const val FIXTURE_DATE = "2026-08-24T12:00:00Z"
    }
}
