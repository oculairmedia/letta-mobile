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
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(message("hello", "reply-id"))))
        // A still-streaming turn has no settlement revision, so nothing can acknowledge it.
        assertFalse(engine.acknowledgeSettlement(fence, mapOf(TimelineMessageId("reply-id") to 99L)))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        val live = assertNotNull(engine.live.value)
        assertEquals(SETTLEMENT, live.settlementRevision)
        assertEquals(1, live.block.events.size)
        for (presented in listOf(
            emptyMap<TimelineMessageId, Long>(),
            mapOf(TimelineMessageId("unrelated-id") to SETTLEMENT),
            mapOf(TimelineMessageId("unrelated-id") to 0L, TimelineMessageId("unrelated-id-2") to SETTLEMENT),
        )) {
            assertFalse(live.isSettled(presented))
            // Draining early would erase a reply that exists nowhere else, so keep every event.
            assertEquals(live.block.events, live.overlayEvents(presented))
            assertFalse(engine.acknowledgeSettlement(fence, presented))
            assertEquals(live, engine.live.value)
        }
    }

    @Test fun overlayDrainsWhenResidentRowsMatchTurnIdentity() = runTest {
        val engine = engine()
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(message("hello", "reply-1"))))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        val live = assertNotNull(engine.live.value)

        // Rows ahead of settlement revision with unrelated identity do NOT drain the turn
        val ahead = mapOf(TimelineMessageId("unrelated-id") to SETTLEMENT + 5)
        assertFalse(live.isSettled(ahead))
        assertEquals(live.block.events, live.overlayEvents(ahead))
        assertFalse(engine.acknowledgeSettlement(fence, ahead))
        assertEquals(live, engine.live.value)

        // Matching turn identity drains the overlay and acknowledges settlement
        val presented = mapOf(TimelineMessageId("reply-1") to SETTLEMENT)
        assertTrue(live.isSettled(presented))
        assertEquals(emptyList(), live.overlayEvents(presented))
        assertTrue(engine.acknowledgeSettlement(fence, presented))
        assertEquals(null, engine.live.value)
    }

    @Test fun strandGuardDrainsWhenLedgerRunsFarPastSettlementRevision() = runTest {
        val engine = engine()
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(message("hello", "reply-stranded"))))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        val live = assertNotNull(engine.live.value)

        // Head ahead by less than STRAND_GUARD_REVISION_DELTA (1024) does not trigger strand guard
        val aheadClose = mapOf(TimelineMessageId("unrelated") to SETTLEMENT + 1023)
        assertFalse(live.isSettled(aheadClose))
        assertFalse(engine.acknowledgeSettlement(fence, aheadClose))

        // Head ahead by >= 1024 triggers strand guard
        val aheadFar = mapOf(TimelineMessageId("unrelated") to SETTLEMENT + 1024)
        assertTrue(live.isSettled(aheadFar))
        assertEquals(emptyList(), live.overlayEvents(aheadFar))
        assertTrue(engine.acknowledgeSettlement(fence, aheadFar))
        assertEquals(null, engine.live.value)
    }

    @Test fun assistantReplyWithAliasedUiMessageIdDrainsOnCanonicalIdentity() = runTest {
        // In production: live event carries synthesized ui-msg-*, sync persists canonical msg-*
        // and records evidence: identity/serverId/<ui-msg-*> -> <canonical-id>
        val synthesizedId = "ui-msg-streamed"
        val canonicalId = "msg-canonical"
        val evidenceMap = mapOf(
            "identity/serverId/$synthesizedId" to canonicalId.encodeToByteArray(),
        )
        val store = FixedStore(evidenceMap)
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)

        // Live stream emits the reply with synthesized ui-msg-* id
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(message("hello", synthesizedId))))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        val live = assertNotNull(engine.live.value)
        assertEquals(1, live.block.events.size)
        assertEquals(synthesizedId, live.block.events[0].serverId)

        // Unrelated resident rows do NOT settle the turn; overlay retains the event
        val unrelated = mapOf(TimelineMessageId("unrelated-row") to SETTLEMENT)
        assertFalse(live.isSettled(unrelated))
        assertEquals(live.block.events, live.overlayEvents(unrelated))
        assertFalse(engine.acknowledgeSettlement(fence, unrelated))
        assertEquals(live, engine.live.value)

        // Paging presents the settled row carrying the canonical id
        val presented = mapOf(TimelineMessageId(canonicalId) to SETTLEMENT)

        // Without alias resolution, raw publication does not match canonical presented row
        assertFalse(live.isSettled(presented))
        assertEquals(live.block.events, live.overlayEvents(presented))

        // When publication carries the resolved alias, isSettled is true and overlayEvents drains
        val aliased = live.copy(aliases = mapOf(synthesizedId to TimelineMessageId(canonicalId)))
        assertTrue(aliased.isSettled(presented))
        assertEquals(emptyList(), aliased.overlayEvents(presented))

        // Engine resolves the alias from evidence, acknowledges settlement, and releases fence
        assertTrue(engine.acknowledgeSettlement(fence, presented))
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
        val presented = mapOf(TimelineMessageId("second-id") to SETTLEMENT)
        val stale = engine.beginLive(selection)
        assertTrue(engine.ingest(stale, TimelineStreamFrame.Message(message("first", "first-id"))))
        assertTrue(engine.ingest(stale, TimelineStreamFrame.Done))
        val current = engine.beginLive(selection)
        // The replacement turn owns the overlay; the finished turn's late acknowledgment is inert.
        assertFalse(engine.acknowledgeSettlement(stale, presented))
        assertTrue(engine.ingest(current, TimelineStreamFrame.Message(message("second", "second-id"))))
        assertTrue(engine.ingest(current, TimelineStreamFrame.Done))
        assertFalse(engine.acknowledgeSettlement(stale, presented))
        assertEquals(current, engine.live.value?.fence)
        assertTrue(engine.acknowledgeSettlement(current, presented))
        assertEquals(null, engine.live.value)
    }

    private fun engine() =
        CanonicalTimelineEngine(FixedStore(), TimelineExactCanonicalWriter(scope, 100_000), enabled = true)

    /** Live ingest may only read the checkpoint; every durable mutation here is a contract failure. */
    private class FixedStore(private val evidenceMap: Map<String, ByteArray> = emptyMap()) : TimelineBoundedStore {
        private val tx = object : TimelineStoreTransaction {
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
            override suspend fun evidence(key: String, maxBytes: Int): ByteArray? = evidenceMap[key]
            override suspend fun put(record: TimelineStoredRecord) = error("live ingest must not write")
            override suspend fun putEvidence(key: String, value: ByteArray) = error("live ingest must not write")
            override suspend fun deleteEvidence(key: String) = error("live ingest must not write")
            override suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean) = error("live ingest must not write")
            override suspend fun nextRevision(): Long = error("only the sync writer may allocate a revision")
            override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason) =
                error("live ingest must not write")
        }
        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T = block(tx)
        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T = block(tx)
    }

    companion object {
        private val scope = TimelineScope("backend", "conversation")
        private const val DURABLE = 0L
        /** One past the fixed durable revision: the first revision only the sync writer can reach. */
        private const val SETTLEMENT = DURABLE + 1
        private fun message(content: String, id: String = "id") = AssistantMessage(
            id = id, contentRaw = kotlinx.serialization.json.JsonPrimitive(content), date = "2026-01-01T00:00:00Z",
        )
    }
}
