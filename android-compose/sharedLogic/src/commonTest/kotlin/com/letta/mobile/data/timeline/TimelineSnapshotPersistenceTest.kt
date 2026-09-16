package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineReadResult
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.InMemoryConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.SnapshotReadFailure
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
    private data class SnapshotIdentity(
        val conversationId: String,
        val backendId: String = "test-backend",
    ) {
        val scope = TimelineScope(backendId, conversationId)
    }

    private data class ConfirmedMessageFixture(
        val serverId: String,
        val content: String,
    ) {
        fun message() = UserMessage(
            id = serverId,
            date = FIXTURE_DATE,
            contentRaw = JsonPrimitive(content),
        )
    }

    private class SnapshotLoopFixture(
        coroutineScope: CoroutineScope,
        identity: SnapshotIdentity,
        val store: ConfirmedTimelineStore = InMemoryConfirmedTimelineStore(),
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) {
        val scope = identity.scope
        val loop = TimelineSyncLoop(
            messageApi = EmptyTimelineTransport,
            conversationId = scope.conversationId,
            scope = coroutineScope,
            startStreamSubscriber = false,
            confirmedTimelineStore = store,
            timelineScope = scope,
            ioDispatcher = ioDispatcher,
        )
    }

    private class GatedConfirmedTimelineStore(
        private val delegate: ConfirmedTimelineStore = InMemoryConfirmedTimelineStore(),
    ) : ConfirmedTimelineStore by delegate {
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val firstWriteCompleted = CompletableDeferred<Unit>()
        var writeCount = 0
            private set
        val writes = mutableListOf<StoredTimelineEnvelope>()

        override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean {
            writeCount += 1
            val writeIndex = writeCount
            if (writeIndex == 1) {
                firstWriteStarted.complete(Unit)
                releaseFirstWrite.await()
            }
            return delegate.writeSnapshot(envelope).also {
                writes += envelope
                if (writeIndex == 1) firstWriteCompleted.complete(Unit)
            }
        }

        // Kotlin's `by delegate` forwards EVERY interface member not explicitly overridden
        // here -- including default-bodied ones -- straight to `delegate`, bypassing the
        // `writeSnapshot` override above entirely. Production now calls `commitNormalized`,
        // not `writeSnapshot`, so without this explicit re-declaration the gating in this
        // fixture (firstWriteStarted/releaseFirstWrite/writeCount) would silently stop being
        // exercised. `super.commitNormalized` resolves the interface's default body, which
        // dispatches back to `this.writeSnapshot`/`this.readSnapshot` -- i.e. the overrides
        // above -- because default interface methods dispatch dynamically on the receiver.
        override suspend fun commitNormalized(
            plan: com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlan,
            fullEnvelope: StoredTimelineEnvelope,
            checkpointLegacyEnvelope: Boolean,
        ) = super.commitNormalized(plan, fullEnvelope, checkpointLegacyEnvelope)
    }

    @Test
    fun aChangeScheduledDuringPersistenceIsFlushedAfterTheInFlightWriteWhenMutated() = runTest {
        val store = GatedConfirmedTimelineStore()
        val fixture = SnapshotLoopFixture(
            coroutineScope = this,
            identity = SnapshotIdentity("conv-pending"),
            store = store,
            ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )

        fixture.loop.scheduleSnapshotPersist(SnapshotPersistReason.LOCAL_MUTATION)
        runCurrent()
        store.firstWriteStarted.await()
        fixture.loop.ingestStreamEvent(ConfirmedMessageFixture("msg-1", "confirmed hello").message())
        fixture.loop.scheduleSnapshotPersist(SnapshotPersistReason.STREAM_FRAME)
        store.releaseFirstWrite.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, store.writeCount)
        assertEquals(listOf(1L, 2L), store.writes.map { it.revision })
        assertTrue(store.writes.first().events.isEmpty())
        assertEquals(listOf("msg-1"), store.writes.last().events.map { it.serverId })
        val finalSnapshot = store.readSnapshot(fixture.scope)
        assertNotNull(finalSnapshot)
        assertEquals(1, finalSnapshot.events.size)
        fixture.loop.closeAndJoin()
    }

    @Test
    fun duplicateOrNoOpMutationsDoNotWriteNewSnapshots() = runTest {
        val store = GatedConfirmedTimelineStore()
        val fixture = SnapshotLoopFixture(
            coroutineScope = this,
            identity = SnapshotIdentity("conv-dedupe"),
            store = store,
            ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )

        fixture.loop.scheduleSnapshotPersist(SnapshotPersistReason.LOCAL_MUTATION)
        runCurrent()
        store.firstWriteStarted.await()
        store.releaseFirstWrite.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, store.writeCount)

        // Trigger 100 un-mutated persist schedules
        repeat(100) {
            fixture.loop.scheduleSnapshotPersist(SnapshotPersistReason.LOCAL_MUTATION)
            advanceUntilIdle()
        }

        // writeCount must remain 1 because content was identical
        assertEquals(1, store.writeCount)
        fixture.loop.closeAndJoin()
    }

    @Test
    fun rejectedSnapshotWriteDoesNotAdvanceMutationBaselineOrExposeContent() = runTest {
        val store = RejectingConfirmedTimelineStore()
        val fixture = SnapshotLoopFixture(
            coroutineScope = this,
            identity = SnapshotIdentity("conv-mutation-baseline"),
            store = store,
            ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )
        Telemetry.clear()

        fixture.loop.scheduleSnapshotPersist(SnapshotPersistReason.LOCAL_MUTATION)
        advanceUntilIdle()
        val first = mutationShapeEvents()
        assertEquals(1, first.size)
        assertEquals(0, first.single().attrs["previousCount"])
        assertEquals(0, first.single().attrs["eventCount"])
        assertEquals(0, first.single().attrs["inserted"])
        assertEquals(0, first.single().attrs["durableRows"])
        assertEquals(0, first.single().attrs["residentRows"])
        assertEquals(0, first.single().attrs["settledEventsVisited"])

        fixture.loop.ingestStreamEvent(ConfirmedMessageFixture("raw-server-id", "private message content").message())
        store.rejectNextWrite = true
        fixture.loop.flushSnapshotNow()
        assertEquals(1, mutationShapeEvents().size)

        fixture.loop.scheduleSnapshotPersist(SnapshotPersistReason.LOCAL_MUTATION)
        advanceUntilIdle()
        val shapes = mutationShapeEvents()
        assertEquals(2, shapes.size)
        val afterRejectedWrite = shapes.last()
        assertEquals(0, afterRejectedWrite.attrs["previousCount"])
        // A successful follow-up is the evidence the rejected write did not
        // advance the baseline; conflation may legitimately persist either
        // the pending event or the latest empty state.
        assertEquals(afterRejectedWrite.attrs["eventCount"], afterRejectedWrite.attrs["inserted"])
        assertEquals(afterRejectedWrite.attrs["eventCount"], afterRejectedWrite.attrs["durableRows"])
        assertEquals(afterRejectedWrite.attrs["eventCount"], afterRejectedWrite.attrs["residentRows"])
        assertEquals(
            afterRejectedWrite.attrs["comparisonEvents"],
            afterRejectedWrite.attrs["settledEventsVisited"],
        )
        assertMutationTelemetryIsBounded(shapes)
        fixture.loop.closeAndJoin()
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
            dateIso = FIXTURE_DATE,
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
            dateIso = FIXTURE_DATE,
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

        loop.scheduleSnapshotPersist(SnapshotPersistReason.LOCAL_MUTATION)
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
    fun closeDrainsQueuedIngressAndRestoresPersistedMutations() = runTest {
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
                date = FIXTURE_DATE,
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

        val closeRaceStore = InMemoryConfirmedTimelineStore()
        val closeRaceScope = TimelineScope(backendId = "test-backend", conversationId = "conv-close-race")
        val closeRaceLoop = TimelineSyncLoop(
            messageApi = EmptyTimelineTransport,
            conversationId = closeRaceScope.conversationId,
            scope = this,
            startStreamSubscriber = false,
            confirmedTimelineStore = closeRaceStore,
            timelineScope = closeRaceScope,
            ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )
        closeRaceLoop.submitStreamEvent(
            com.letta.mobile.data.model.UserMessage(
                id = "queued-before-close",
                date = FIXTURE_DATE,
                contentRaw = kotlinx.serialization.json.JsonPrimitive("must persist"),
            ),
        )
        closeRaceLoop.closeAndJoin()

        val closeRaceSnapshot = assertNotNull(closeRaceStore.readSnapshot(closeRaceScope))
        assertEquals(listOf("queued-before-close"), closeRaceSnapshot.events.map { it.serverId })
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
                    dateIso = FIXTURE_DATE,
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
        val transport = SnapshotRecoveryTransport(
            RecoveryTransportFixture(failure = IllegalStateException("offline")),
        )
        val repository = TimelineRepository(
            timelineTransport = transport,
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            confirmedTimelineStore = store,
            backendIdProvider = { "backend" },
            repositoryScope = backgroundScope,
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
        val transport = SnapshotRecoveryTransport(
            RecoveryTransportFixture(
                messages = listOf(
                    UserMessage(
                        id = "remote-server",
                        date = FIXTURE_DATE,
                        contentRaw = JsonPrimitive("recovered remotely"),
                    )
                ),
            ),
        )
        val repository = TimelineRepository(
            timelineTransport = transport,
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            confirmedTimelineStore = store,
            backendIdProvider = { "backend" },
            repositoryScope = backgroundScope,
            startLoopStreamSubscribers = false,
        )

        val loop = repository.getOrCreate("agent", "conv-missing")

        assertEquals(1, transport.remoteReads)
        assertEquals("recovered remotely", (loop.state.value.events.single() as TimelineEvent.Confirmed).content)
        loop.flushSnapshotNow()
        assertEquals(5L, store.lastWrite?.revision)
        repository.clearAll()
    }

    private class RejectingConfirmedTimelineStore : ConfirmedTimelineStore {
        private val delegate = InMemoryConfirmedTimelineStore()
        var rejectNextWrite = false

        override suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope? = delegate.readSnapshot(scope)

        override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean {
            if (rejectNextWrite) {
                rejectNextWrite = false
                return false
            }
            return delegate.writeSnapshot(envelope)
        }

        override suspend fun deleteSnapshot(scope: TimelineScope) = delegate.deleteSnapshot(scope)
        override suspend fun clearForBackend(backendId: String) = delegate.clearForBackend(backendId)
        override suspend fun prune(backendId: String, maxRetainedConversations: Int) =
            delegate.prune(backendId, maxRetainedConversations)
    }

    private fun mutationShapeEvents() = Telemetry.snapshot().filter {
        it.name == "snapshotPersist.mutationShape"
    }

    private fun assertMutationTelemetryIsBounded(events: List<Telemetry.Event>) {
        val expectedAttrs = setOf(
            "revision", "previousCount", "eventCount", "inserted", "updated", "deleted", "moved",
            "cursorMetadataChanged", "noOp", "unclassifiable", "comparisonEvents", "fullEnvelopeEncodes",
            "durableRows", "residentRows", "settledEventsVisited", "pagesCopied", "liveProjected", "boundaryRebuilt",
        )
        events.forEach { event ->
            assertEquals(expectedAttrs, event.attrs.keys)
            assertTrue(event.attrs.values.all {
                it is Int || it is Long || it is Boolean || it == "unavailable"
            })
            assertEquals("unavailable", event.attrs["pagesCopied"])
            assertEquals("unavailable", event.attrs["liveProjected"])
            assertEquals("unavailable", event.attrs["boundaryRebuilt"])
            assertFalse(event.attrs.values.any { value ->
                value.toString().contains("private message content") || value.toString().contains("raw-server-id")
            })
        }
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

    private companion object {
        const val FIXTURE_DATE = "2026-08-24T12:00:00Z"
    }
}
