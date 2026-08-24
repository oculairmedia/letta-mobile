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
    fun aChangeScheduledDuringPersistenceIsFlushedAfterTheInFlightWrite() = runTest {
        val store = GatedConfirmedTimelineStore()
        val scope = TimelineScope(backendId = "test-backend", conversationId = "conv-pending")
        val loop = TimelineSyncLoop(
            messageApi = EmptyTimelineTransport,
            conversationId = scope.conversationId,
            scope = this,
            startStreamSubscriber = false,
            confirmedTimelineStore = store,
            timelineScope = scope,
        )

        loop.scheduleSnapshotPersist(immediate = true)
        runCurrent()
        store.firstWriteStarted.await()
        loop.scheduleSnapshotPersist(immediate = false)
        store.releaseFirstWrite.complete(Unit)
        advanceUntilIdle()

        assertTrue(store.writeCount >= 2)
        loop.closeAndJoin()
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
}
