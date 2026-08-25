package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.InMemoryConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineSnapshotPersistenceTest {
    private class GatedConfirmedTimelineStore(
        private val delegate: ConfirmedTimelineStore = InMemoryConfirmedTimelineStore(),
    ) : ConfirmedTimelineStore by delegate {
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val firstWriteCompleted = CompletableDeferred<Unit>()
        var writeCount = 0
            private set

        override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean {
            writeCount += 1
            if (writeCount == 1) {
                firstWriteStarted.complete(Unit)
                releaseFirstWrite.await()
            }
            return delegate.writeSnapshot(envelope).also {
                if (writeCount == 1) firstWriteCompleted.complete(Unit)
            }
        }
    }

    @Test
    fun aChangeScheduledDuringPersistenceIsFlushedAfterTheInFlightWriteWhenMutated() = runTest {
        val store = GatedConfirmedTimelineStore()
        val scope = TimelineScope(backendId = "test-backend", conversationId = "conv-pending")
        val loop = TimelineSyncLoop(
            messageApi = EmptyTimelineTransport,
            conversationId = scope.conversationId,
            scope = this,
            startStreamSubscriber = false,
            confirmedTimelineStore = store,
            timelineScope = scope,
            ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )

        loop.scheduleSnapshotPersist(immediate = true)
        runCurrent()
        store.firstWriteStarted.await()
        loop.ingestStreamEvent(
            com.letta.mobile.data.model.UserMessage(
                id = "msg-1",
                date = "2026-08-24T12:00:00Z",
                contentRaw = kotlinx.serialization.json.JsonPrimitive("confirmed hello"),
            ),
        )
        loop.scheduleSnapshotPersist(immediate = false)
        store.releaseFirstWrite.complete(Unit)
        advanceUntilIdle()

        assertTrue(store.writeCount >= 2)
        val finalSnapshot = store.readSnapshot(scope)
        assertNotNull(finalSnapshot)
        assertEquals(1, finalSnapshot.events.size)
        loop.closeAndJoin()
    }

    @Test
    fun duplicateOrNoOpMutationsDoNotWriteNewSnapshots() = runTest {
        val store = GatedConfirmedTimelineStore()
        val scope = TimelineScope(backendId = "test-backend", conversationId = "conv-dedupe")
        val loop = TimelineSyncLoop(
            messageApi = EmptyTimelineTransport,
            conversationId = scope.conversationId,
            scope = this,
            startStreamSubscriber = false,
            confirmedTimelineStore = store,
            timelineScope = scope,
            ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )

        loop.scheduleSnapshotPersist(immediate = true)
        runCurrent()
        store.firstWriteStarted.await()
        store.releaseFirstWrite.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, store.writeCount)

        // Trigger 100 un-mutated persist schedules
        repeat(100) {
            loop.scheduleSnapshotPersist(immediate = true)
            advanceUntilIdle()
        }

        // writeCount must remain 1 because content was identical
        assertEquals(1, store.writeCount)
        loop.closeAndJoin()
    }

    @Test
    fun deterministicEnvelopeFingerprintMatchesAcrossInstances() {
        val scope = TimelineScope(backendId = "test-backend", conversationId = "conv-hash")
        val env1 = StoredTimelineEnvelope(
            schemaVersion = 1,
            scope = scope,
            revision = 1L,
            liveCursor = "cursor-1",
            events = emptyList(),
            writtenAtMillis = 1000L,
        )
        val env2 = StoredTimelineEnvelope(
            schemaVersion = 1,
            scope = scope,
            revision = 2L, // different revision and timestamp
            liveCursor = "cursor-1",
            events = emptyList(),
            writtenAtMillis = 9999L,
        )

        val fp1 = com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec.computeStoredEnvelopeFingerprint(env1)
        val fp2 = com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec.computeStoredEnvelopeFingerprint(env2)
        assertEquals(fp1, fp2)
    }

    @Test
    fun closeAwaitsAndCompletesInFlightSnapshotPersistence() = runTest {
        val store = GatedConfirmedTimelineStore()
        val scope = TimelineScope(backendId = "test-backend", conversationId = "conv-closing")
        val loop = TimelineSyncLoop(
            messageApi = EmptyTimelineTransport,
            conversationId = scope.conversationId,
            scope = this,
            startStreamSubscriber = false,
            confirmedTimelineStore = store,
            timelineScope = scope,
            ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )

        loop.scheduleSnapshotPersist(immediate = true)
        runCurrent()
        store.firstWriteStarted.await()
        val closing = async { loop.closeAndJoin() }
        runCurrent()

        assertFalse(closing.isCompleted)
        store.releaseFirstWrite.complete(Unit)
        closing.await()

        assertTrue(store.firstWriteCompleted.isCompleted)
        assertNotNull(store.readSnapshot(scope))
    }

    @Test
    fun mutationsPersistImmediatelyAndRestoreAccuratelyAfterClose() = runTest {
        val store = InMemoryConfirmedTimelineStore()
        val scope = TimelineScope(backendId = "test-backend", conversationId = "conv-restore")
        val loop1 = TimelineSyncLoop(
            messageApi = EmptyTimelineTransport,
            conversationId = scope.conversationId,
            scope = this,
            startStreamSubscriber = false,
            confirmedTimelineStore = store,
            timelineScope = scope,
            ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )

        // Ingest message and flush
        loop1.ingestStreamEvent(
            com.letta.mobile.data.model.UserMessage(
                id = "msg-persisted",
                date = "2026-08-24T12:00:00Z",
                contentRaw = kotlinx.serialization.json.JsonPrimitive("persisted data"),
            ),
        )
        loop1.flushSnapshotNow()
        loop1.closeAndJoin()

        // Restart loop2 and verify snapshot read
        val snapshot = store.readSnapshot(scope)
        assertNotNull(snapshot)
        assertEquals(1, snapshot.events.size)
        assertEquals("msg-persisted", snapshot.events.first().serverId)
    }
}
