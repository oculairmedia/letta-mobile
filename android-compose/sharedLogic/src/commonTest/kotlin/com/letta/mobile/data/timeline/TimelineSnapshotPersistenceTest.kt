package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineReadResult
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.InMemoryConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.SnapshotReadFailure
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

        val fp1 = TimelineSnapshotCodec.computeStoredEnvelopeFingerprint(env1)
        val fp2 = TimelineSnapshotCodec.computeStoredEnvelopeFingerprint(env2)
        assertEquals(fp1, fp2)
    }

    @Test
    fun envelopeFingerprintFramesAdjacentAndNullableStrings() {
        val scope = TimelineScope(backendId = "test-backend", conversationId = "conv-hash-framing")
        val event = StoredTimelineEvent(
            position = 1.0,
            otid = "ab",
            content = "c",
            serverId = "server",
            messageType = "USER",
            dateIso = "2026-08-24T12:00:00Z",
        )
        val envelope = StoredTimelineEnvelope(scope = scope, revision = 1L, events = listOf(event))

        val adjacentBoundaryChanged = envelope.copy(events = listOf(event.copy(otid = "a", content = "bc")))
        val nullCursor = envelope.copy(liveCursor = null)
        val emptyCursor = envelope.copy(liveCursor = "")

        assertNotEquals(
            TimelineSnapshotCodec.computeStoredEnvelopeFingerprint(envelope),
            TimelineSnapshotCodec.computeStoredEnvelopeFingerprint(adjacentBoundaryChanged),
        )
        assertNotEquals(
            TimelineSnapshotCodec.computeStoredEnvelopeFingerprint(nullCursor),
            TimelineSnapshotCodec.computeStoredEnvelopeFingerprint(emptyCursor),
        )
    }

    @Test
    fun envelopeFingerprintCanonicalizesMapIterationOrder() {
        val scope = TimelineScope(backendId = "test-backend", conversationId = "conv-hash-maps")
        val event = StoredTimelineEvent(
            position = 1.0,
            otid = "otid",
            serverId = "server",
            messageType = "TOOL_CALL",
            dateIso = "2026-08-24T12:00:00Z",
            toolReturnContentByCallId = linkedMapOf("a" to "first", "b" to "second"),
        )
        val reordered = event.copy(
            toolReturnContentByCallId = linkedMapOf("b" to "second", "a" to "first"),
        )

        assertEquals(
            TimelineSnapshotCodec.computeStoredEnvelopeFingerprint(
                StoredTimelineEnvelope(scope = scope, revision = 1L, events = listOf(event)),
            ),
            TimelineSnapshotCodec.computeStoredEnvelopeFingerprint(
                StoredTimelineEnvelope(scope = scope, revision = 2L, events = listOf(reordered)),
            ),
        )
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

    @Test
    fun repositoryKeepsFallbackVisibleWhenRemoteRecoveryFailsAndAdvancesFromHighWater() = runTest {
        val timelineScope = TimelineScope("backend", "conv-fallback", "agent")
        val fallback = StoredTimelineEnvelope(
            scope = timelineScope,
            revision = 5L,
            events = listOf(
                StoredTimelineEvent(
                    position = 1.0,
                    otid = "fallback-otid",
                    serverId = "fallback-server",
                    content = "last known good",
                    messageType = "ASSISTANT",
                    dateIso = "2026-08-24T12:00:00Z",
                )
            ),
        )
        val store = TypedRecoveryStore(
            ConfirmedTimelineReadResult.Fallback(
                snapshot = fallback,
                activeFailure = SnapshotReadFailure.CHECKSUM_MISMATCH,
                highWaterRevision = 9L,
            )
        )
        val transport = RecoveryTimelineTransport(failure = IllegalStateException("offline"))
        val repository = TimelineRepository(
            timelineTransport = transport,
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            confirmedTimelineStore = store,
            backendIdProvider = { "backend" },
            startLoopStreamSubscribers = false,
        )

        val loop = repository.getOrCreate("agent", "conv-fallback")

        assertEquals(1, transport.remoteReads)
        assertEquals("last known good", (loop.state.value.events.single() as TimelineEvent.Confirmed).content)
        loop.flushSnapshotNow()
        assertEquals(10L, store.lastWrite?.revision)
        repository.clearAll()
    }

    @Test
    fun missingSnapshotTriggersTypedRemoteRecovery() = runTest {
        val store = TypedRecoveryStore(
            ConfirmedTimelineReadResult.ReconciliationRequired(
                failure = SnapshotReadFailure.MISSING,
                highWaterRevision = 4L,
            )
        )
        val transport = RecoveryTimelineTransport(
            messages = listOf(
                UserMessage(
                    id = "remote-server",
                    date = "2026-08-24T12:00:00Z",
                    contentRaw = JsonPrimitive("recovered remotely"),
                )
            )
        )
        val repository = TimelineRepository(
            timelineTransport = transport,
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            confirmedTimelineStore = store,
            backendIdProvider = { "backend" },
            startLoopStreamSubscribers = false,
        )

        val loop = repository.getOrCreate("agent", "conv-missing")

        assertEquals(1, transport.remoteReads)
        assertEquals("recovered remotely", (loop.state.value.events.single() as TimelineEvent.Confirmed).content)
        loop.flushSnapshotNow()
        assertEquals(5L, store.lastWrite?.revision)
        repository.clearAll()
    }

    private class TypedRecoveryStore(
        private val readResult: ConfirmedTimelineReadResult,
    ) : ConfirmedTimelineStore {
        var lastWrite: StoredTimelineEnvelope? = null

        override suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope? = readResult.snapshot
        override suspend fun readSnapshotResult(scope: TimelineScope): ConfirmedTimelineReadResult = readResult
        override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean {
            lastWrite = envelope
            return true
        }
        override suspend fun deleteSnapshot(scope: TimelineScope) = Unit
        override suspend fun clearForBackend(backendId: String) = Unit
        override suspend fun prune(backendId: String, maxRetainedConversations: Int) = Unit
    }

    private class RecoveryTimelineTransport(
        private val messages: List<LettaMessage> = emptyList(),
        private val failure: Throwable? = null,
    ) : TimelineTransport by EmptyTimelineTransport {
        var remoteReads: Int = 0

        override suspend fun listConversationMessages(
            conversationId: String,
            limit: Int?,
            after: String?,
            order: String?,
        ): List<LettaMessage> {
            remoteReads += 1
            failure?.let { throw it }
            return messages
        }
    }
}
