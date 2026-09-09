package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live ingest writes no durable rows, so between a turn's terminal frame and the sync writer's
 * commit the overlay is the only copy of the reply. The handoff that ends that window — resident
 * revisions in, drained overlay and released fence out — is its own contract, exercised here
 * against a ledger that fails any durable write attempted from the live path.
 */
class TimelineLiveOverlayDrainTest {
    @Test fun overlayStaysFullyResidentWhileSettledRowsLagSettlementRevision() = runTest {
        val engine = engine()
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(message("hello"))))
        // A still-streaming turn has no settlement revision, so nothing can acknowledge it.
        assertFalse(engine.acknowledgeSettlement(fence, mapOf(TimelineMessageId("ui-msg-1") to 99L)))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        val live = assertNotNull(engine.live.value)
        assertEquals(SETTLEMENT, live.settlementRevision)
        assertEquals(1, live.block.events.size)
        for (presented in listOf(
            emptyMap<TimelineMessageId, Long>(),
            mapOf(TimelineMessageId("ui-msg-1") to SETTLEMENT - 1),
            mapOf(TimelineMessageId("ui-msg-1") to 0L, TimelineMessageId("ui-msg-2") to SETTLEMENT - 1),
        )) {
            assertFalse(live.isSettled(presented))
            // Draining early would erase a reply that exists nowhere else, so keep every event.
            assertEquals(live.block.events, live.overlayEvents(presented))
            assertFalse(engine.acknowledgeSettlement(fence, presented))
            assertEquals(live, engine.live.value)
        }
    }

    @Test fun overlayDrainsWhenAnyResidentRowReachesSettlementRevision() = runTest {
        val engine = engine()
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(message("hello"))))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        val live = assertNotNull(engine.live.value)
        // The sync writer owns identities the stream never used, so only the revision can decide.
        val presented = mapOf(TimelineMessageId("ui-msg-1") to SETTLEMENT)
        assertTrue(live.isSettled(presented))
        assertEquals(emptyList(), live.overlayEvents(presented))
        assertTrue(engine.acknowledgeSettlement(fence, presented))
        assertEquals(null, engine.live.value)

        // A ledger that ran further ahead than this turn's revision settles it just as well.
        val next = engine.beginLive(selection)
        assertTrue(engine.ingest(next, TimelineStreamFrame.Message(message("second"))))
        assertTrue(engine.ingest(next, TimelineStreamFrame.Done))
        val ahead = mapOf(TimelineMessageId("ui-msg-2") to SETTLEMENT + 5)
        assertTrue(assertNotNull(engine.live.value).isSettled(ahead))
        assertTrue(engine.acknowledgeSettlement(next, ahead))
        assertEquals(null, engine.live.value)
    }

    @Test fun turnProducingNoEventsSettlesWithoutStrandingTheFence() = runTest {
        val engine = engine()
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Heartbeat))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        val live = assertNotNull(engine.live.value)
        assertEquals(emptyList(), live.block.events)
        // Nothing will ever carry this turn into the ledger, so waiting would hold the fence forever.
        assertTrue(live.isSettled(emptyMap()))
        assertEquals(emptyList(), live.overlayEvents(emptyMap()))
        assertTrue(engine.acknowledgeSettlement(fence, emptyMap()))
        assertEquals(null, engine.live.value)
        // The fence really was released: the same selection can start and settle another turn.
        val next = engine.beginLive(selection)
        assertTrue(engine.ingest(next, TimelineStreamFrame.Message(message("after"))))
    }

    @Test fun staleFenceCannotAcknowledgeSettlement() = runTest {
        val engine = engine()
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val presented = mapOf(TimelineMessageId("ui-msg-1") to SETTLEMENT)
        val stale = engine.beginLive(selection)
        assertTrue(engine.ingest(stale, TimelineStreamFrame.Message(message("first"))))
        assertTrue(engine.ingest(stale, TimelineStreamFrame.Done))
        val current = engine.beginLive(selection)
        // The replacement turn owns the overlay; the finished turn's late acknowledgment is inert.
        assertFalse(engine.acknowledgeSettlement(stale, presented))
        assertTrue(engine.ingest(current, TimelineStreamFrame.Message(message("second"))))
        assertTrue(engine.ingest(current, TimelineStreamFrame.Done))
        assertFalse(engine.acknowledgeSettlement(stale, presented))
        assertEquals(current, engine.live.value?.fence)
        assertTrue(engine.acknowledgeSettlement(current, presented))
        assertEquals(null, engine.live.value)
    }

    private fun engine() =
        CanonicalTimelineEngine(FixedStore(), TimelineExactCanonicalWriter(scope, 100_000), enabled = true)

    /** Live ingest may only read the checkpoint; every durable mutation here is a contract failure. */
    private class FixedStore : TimelineBoundedStore {
        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T = block(Tx)
        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T = block(Tx)
        private object Tx : TimelineStoreTransaction {
            override suspend fun toolCall(callId: String): TimelineToolIndexEntry? = null
            override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int) = emptyList<TimelineToolIndexEntry>()
            override suspend fun toolSweepGeneration() = 0L
            override suspend fun putToolCall(entry: TimelineToolIndexEntry) = error("live ingest must not write")
            override suspend fun setToolSweepGeneration(next: Long) = error("live ingest must not write")
            override suspend fun checkpoint() = TimelineDurableCheckpoint(DURABLE, TimelineContinuation.Initial, false)
            override suspend fun locate(identity: TimelineMessageId): TimelinePageKey? = null
            override suspend fun metadata(position: TimelineReadPosition, maxRows: Int) =
                TimelineMetadataPage(emptyList(), null, null, DURABLE)
            override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray =
                error("no settled bodies")
            override suspend fun evidence(key: String, maxBytes: Int): ByteArray? = null
            override suspend fun put(record: TimelineStoredRecord) = error("live ingest must not write")
            override suspend fun putEvidence(key: String, value: ByteArray) = error("live ingest must not write")
            override suspend fun deleteEvidence(key: String) = error("live ingest must not write")
            override suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean) = error("live ingest must not write")
            override suspend fun nextRevision(): Long = error("only the sync writer may allocate a revision")
            override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason) =
                error("live ingest must not write")
        }
    }

    companion object {
        private val scope = TimelineScope("backend", "conversation")
        private const val DURABLE = 0L
        /** One past the fixed durable revision: the first revision only the sync writer can reach. */
        private const val SETTLEMENT = DURABLE + 1
        private fun message(content: String) = AssistantMessage(
            id = "id", contentRaw = kotlinx.serialization.json.JsonPrimitive(content), date = "2026-01-01T00:00:00Z",
        )
    }
}
