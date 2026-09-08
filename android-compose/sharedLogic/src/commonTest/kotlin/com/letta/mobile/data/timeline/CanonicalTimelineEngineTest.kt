package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CanonicalTimelineEngineTest {
    @Test fun chunkResolutionRejectsForeignScopeAndReleasedSelectionBeforeStorage() = runTest {
        val store = Store()
        val engine = engine(store)
        val selection = open(engine)
        val reference = TimelineBodyReference(
            TimelineScope("foreign-backend", scope.conversationId),
            TimelinePageKey(0, TimelineMessageId("message")),
            TimelineBodyPointer("body", 8), "application/json", 0,
        )
        val reads = store.reads
        assertFailsWith<IllegalArgumentException> { engine.resolveBodyChunk(selection, reference, 0, 8) }
        engine.release(selection)
        assertFailsWith<IllegalStateException> {
            engine.resolveBodyChunk(selection, reference.copy(scope = scope), 0, 8)
        }
        assertEquals(reads, store.reads)
    }

    @Test fun disabledDoesNotReadStorage() = runTest {
        val store = Store()
        assertIs<TimelineEngineOpen.Disabled>(CanonicalTimelineEngine(store, writer).open(scope))
        assertEquals(0, store.reads)
    }

    @Test fun cursorOnlyCommitAdvancesWatermarkAndSurvivesReopen() = runTest {
        val store = Store()
        val engine = engine(store)
        val selection = open(engine)
        val request = engine.beginPage(selection)
        assertEquals(TimelineEnginePageOutcome.Applied, engine.applyPage(request, page(request, null)))
        assertEquals(1L, engine.publication.value.durableRevision)
        engine.release(selection)
        val reopened = open(engine(store))
        assertEquals(scope, reopened.scope)
        assertEquals(TimelineDurableCheckpoint(1, null, false), store.checkpoints[scope])
    }

    @Test fun staleRequestAndConversationCannotCommit() = runTest {
        val store = Store()
        val engine = engine(store)
        val first = open(engine)
        val stale = engine.beginPage(first)
        val current = engine.beginPage(first)
        assertEquals(TimelineEnginePageOutcome.Stale, engine.applyPage(stale, page(stale, null)))
        open(engine, TimelineScope("backend", "other"))
        assertEquals(TimelineEnginePageOutcome.Stale, engine.applyPage(current, page(current, null)))
        assertEquals(0, store.commits)
    }

    @Test fun emptyUnadvancedCursorIsNoProgressNotExhaustion() = runTest {
        val store = Store()
        val engine = engine(store)
        val request = engine.beginPage(open(engine))
        assertEquals(TimelineEnginePageOutcome.NoProgress, engine.applyPage(request, page(request, TimelineContinuation.Initial)))
        assertEquals(0, store.commits)
    }

    @Test fun cancellationRollsBackCursorAndAllowsRetry() = runTest {
        val store = Store()
        val engine = engine(store)
        val request = engine.beginPage(open(engine))
        store.cancel = true
        assertFailsWith<CancellationException> { engine.applyPage(request, page(request, null)) }
        assertEquals(0L, engine.publication.value.durableRevision)
        assertEquals(0, store.commits)
        store.cancel = false
        assertEquals(TimelineEnginePageOutcome.Applied, engine.applyPage(request, page(request, null)))
    }

    @Test fun malformedByteAccountingCannotCommit() = runTest {
        val store = Store()
        val engine = engine(store)
        val request = engine.beginPage(open(engine))
        assertFailsWith<IllegalArgumentException> { engine.applyPage(request, page(request, null).copy(decodedBodyBytes = 1)) }
        assertEquals(0, store.commits)
    }

    @Test fun missingTargetDoesNotSupersedeSelection() = runTest {
        val engine = engine(Store())
        val selected = open(engine)
        assertIs<TimelineEngineOpen.MissingTarget>(engine.open(scope, TimelineMessageId("absent")))
        assertEquals(selected, engine.publication.value.selection)
    }

    @Test fun tokenPublicationsDoNotChangeSettledWatermarkAndOldRunIsRejected() = runTest {
        val store = Store()
        val engine = engine(store)
        val selected = open(engine)
        val before = engine.publication.value
        val old = engine.beginLive(selected)
        val current = engine.beginLive(selected)
        assertEquals(false, engine.publishLive(old, TimelineLiveBlock(emptyList(), false)))
        repeat(100) { assertEquals(true, engine.publishLive(current, TimelineLiveBlock(emptyList(), false))) }
        assertEquals(before, engine.publication.value)
        assertEquals(0, store.commits)
        engine.release(selected)
        assertEquals(null, engine.live.value)
        assertEquals(false, engine.publishLive(current, TimelineLiveBlock(emptyList(), true)))
    }

    @Test fun conversationOwnersSurviveOtherSelectionsAndRejectRetiredOwners() = runTest {
        val store = Store()
        val transport = object : TimelineTransport {
            override suspend fun sendConversationMessage(
                conversationId: String,
                request: com.letta.mobile.data.model.MessageCreateRequest,
            ): kotlinx.coroutines.flow.Flow<com.letta.mobile.data.model.LettaMessage> = error("unexpected send")
            override suspend fun streamConversation(conversationId: String): kotlinx.coroutines.flow.Flow<TimelineStreamFrame> =
                error("selection must not start transport")
            override suspend fun listConversationMessages(
                conversationId: String, limit: Int?, after: String?, order: String?,
            ): List<com.letta.mobile.data.model.LettaMessage> = error("unexpected legacy hydration")
            override suspend fun listAgentMessages(
                agentId: String, limit: Int?, order: String?, conversationId: String?,
            ): List<com.letta.mobile.data.model.LettaMessage> = error("unexpected agent hydration")
        }
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val first = coordinator.acquire(scope)
        val screen = kotlin.test.assertNotNull(coordinator.attach(first))
        assertEquals(false, coordinator.retire(first))
        val request = first.session.engine.beginPage(first.selection)
        val fence = coordinator.beginLive(first)
        coordinator.detach(screen)
        coordinator.detach(screen) // Disposal is idempotent and does not terminate the run.
        assertEquals(first, coordinator.acquire(scope))
        val otherScope = TimelineScope("another-backend", scope.conversationId)
        val other = coordinator.acquire(otherScope)
        assertEquals(null, coordinator.locate(first, TimelineMessageId("missing")))
        assertEquals(first, coordinator.current(scope))
        assertEquals(other, coordinator.current(otherScope))
        assertEquals(false, coordinator.retire(first))
        assertEquals(true, coordinator.ingest(first, fence, TimelineStreamFrame.Heartbeat))
        assertEquals(false, coordinator.ingest(other, fence, TimelineStreamFrame.Heartbeat))
        assertEquals(true, coordinator.retire(other))
        assertEquals(true, first.session.engine.publishLive(fence, TimelineLiveBlock(emptyList(), true)))
        assertEquals(false, coordinator.retire(first))
        assertEquals(true, coordinator.acknowledgeSettlement(first, fence, emptyMap()))
        assertEquals(true, coordinator.retire(first))
        val replacement = coordinator.acquire(scope)
        assertEquals(false, coordinator.ingest(first, fence, TimelineStreamFrame.Heartbeat))
        assertEquals(false, coordinator.acknowledgeSettlement(first, fence, emptyMap()))
        assertFailsWith<IllegalStateException> { coordinator.beginLive(first) }
        assertEquals(TimelineEnginePageOutcome.Stale, first.session.engine.applyPage(request, page(request, null)))
        assertEquals(replacement, coordinator.current(scope))
        assertEquals(true, coordinator.retire(replacement))
        assertEquals(1, store.commits)
        assertEquals(5, store.reads)
    }

    private fun engine(store: Store) = CanonicalTimelineEngine(store, writer, enabled = true)
    private suspend fun open(engine: CanonicalTimelineEngine, selectedScope: TimelineScope = scope) =
        assertIs<TimelineEngineOpen.Opened>(engine.open(selectedScope)).selection
    private fun page(request: TimelineEngineRequest, next: TimelineContinuation?) = TimelineRemotePageResult.Page(
        request.remote.requestId, request.selection.generation, emptyList(), next, next != null, 0,
    )

    private class Store : TimelineBoundedStore {
        val checkpoints = mutableMapOf<TimelineScope, TimelineDurableCheckpoint>()
        var reads = 0
        var commits = 0
        var cancel = false
        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T {
            reads++
            return block(Transaction(checkpoints[scope] ?: initial))
        }
        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T {
            val tx = Transaction(checkpoints[scope] ?: initial)
            val result = block(tx)
            if (cancel) throw CancellationException("before commit")
            checkpoints[scope] = tx.current
            commits++
            return result
        }
        private class Transaction(var current: TimelineDurableCheckpoint) : TimelineStoreTransaction {
            override suspend fun checkpoint() = current
            override suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean) {
                current = current.copy(continuation = continuation, hasMore = hasMore)
            }
            override suspend fun nextRevision(): Long {
                current = current.copy(revision = current.revision + 1)
                return current.revision
            }
            override suspend fun locate(identity: TimelineMessageId): TimelinePageKey? = null
            override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage = error("unused")
            override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray = error("unused")
            override suspend fun evidence(key: String, maxBytes: Int): ByteArray? = error("unused")
            override suspend fun put(record: TimelineStoredRecord): Unit = error("unused")
            override suspend fun putEvidence(key: String, value: ByteArray): Unit = error("unused")
            override suspend fun deleteEvidence(key: String): Unit = error("unused")
            override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason): Unit = error("release deleted history")
        }
    }

    companion object {
        private val scope = TimelineScope("backend", "conversation")
        private val initial = TimelineDurableCheckpoint(0, TimelineContinuation.Initial, true)
        private val writer = TimelineCanonicalWriter { _, _ -> error("empty page must not invoke writer") }
    }
}
