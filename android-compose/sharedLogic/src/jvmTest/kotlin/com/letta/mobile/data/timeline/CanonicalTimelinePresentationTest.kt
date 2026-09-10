package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.ui.common.GroupPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanonicalTimelinePresentationTest {
    @Test fun missingSearchKeepsTailAndClosingOnlyDetaches() = runTest {
        val coordinator = CanonicalTimelineCoordinator(EmptyStore(), NoTransport)
        val owner = coordinator.acquire(TimelineScope("backend", "conversation"))
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, backgroundScope, TimelineMessageId("missing"))
        assertEquals("missing", presentation.missingTarget)
        assertFalse(coordinator.retire(owner))
        presentation.close()
        // The owner still belongs to the coordinator; closing did not retire ingestion.
        assertTrue(coordinator.retire(owner))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun parentCancellationDetachesEvenWithoutExplicitClose() = runTest {
        val coordinator = CanonicalTimelineCoordinator(EmptyStore(), NoTransport)
        val owner = coordinator.acquire(TimelineScope("backend", "conversation"))
        val parent = kotlinx.coroutines.Job()
        val ui = kotlinx.coroutines.CoroutineScope(coroutineContext + parent)
        CanonicalTimelinePresentation.open(coordinator, owner, ui)
        assertFalse(coordinator.retire(owner))
        parent.cancel()
        runCurrent()
        assertTrue(coordinator.retire(owner))
    }

    @Test fun missingNavigationPreservesViewportAndRepeatedCloseIsSafe() = runTest {
        val coordinator = CanonicalTimelineCoordinator(EmptyStore(), NoTransport)
        val owner = coordinator.acquire(TimelineScope("backend", "conversation"))
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, backgroundScope)
        presentation.viewport = "resident" to 23
        assertFalse(presentation.navigate("missing"))
        assertEquals("resident" to 23, presentation.viewport)
        assertTrue(presentation.navigate(null))
        assertEquals(null, presentation.viewport)
        presentation.close()
        presentation.close()
        assertTrue(coordinator.retire(owner))
    }

    @Test fun overlayStaysResidentUntilAResidentRowCarriesTheTurnsRevision() = runTest {
        val coordinator = CanonicalTimelineCoordinator(EmptyStore(), NoTransport)
        val owner = coordinator.acquire(TimelineScope("backend", "conversation"))
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, backgroundScope)
        val fence = coordinator.beginLive(owner)
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(assistant("hello", "reply"))))
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        runCurrent()
        // Live ingest wrote nothing durable, so the overlay is the only copy of this reply.
        assertEquals(listOf("hello"), contents(presentation.live.value))
        // Rows the sync writer has not caught up to must not drain it.
        presentation.onResidentRows(listOf(row("unrelated", 0L)))
        runCurrent()
        assertEquals(listOf("hello"), contents(presentation.live.value))
        assertEquals(fence, owner.session.live.value?.fence)
        // The rendered ledger reaches the turn's identity: the overlay drains and the fence releases.
        presentation.onResidentRows(listOf(row("reply", 1L)))
        runCurrent()
        assertEquals(emptyList(), contents(presentation.live.value))
        assertEquals(null, owner.session.live.value)
        presentation.close()
        assertTrue(coordinator.retire(owner))
    }

    @Test fun aliasedAssistantReplyDrainsWhenTheCanonicalRowBecomesResident() = runTest {
        val store = EmptyStore()
        store.putEvidence("identity/serverId/cm-stream-reply", "reply-canonical".encodeToByteArray())
        val coordinator = CanonicalTimelineCoordinator(store, NoTransport)
        val owner = coordinator.acquire(TimelineScope("backend", "conversation"))
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, backgroundScope)
        val fence = coordinator.beginLive(owner)
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(assistant("hello", "cm-stream-reply"))))
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        runCurrent()
        assertEquals(listOf("hello"), contents(presentation.live.value))

        // When the settled row carrying the canonical id becomes resident:
        // presentation acknowledges settlement, the alias is resolved in the engine, and the overlay drains
        presentation.onResidentRows(listOf(row("reply-canonical", 1L)))
        runCurrent()
        assertEquals(emptyList(), contents(presentation.live.value))
        assertEquals(null, owner.session.live.value)
        presentation.close()
        assertTrue(coordinator.retire(owner))
    }

    @Test fun optimisticBubbleStaysSuppressedAcrossTheWholeDrainWindow() = runTest {
        val coordinator = CanonicalTimelineCoordinator(EmptyStore(), NoTransport)
        val owner = coordinator.acquire(TimelineScope("backend", "conversation"))
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, backgroundScope)
        coordinator.appendPending(
            owner, CanonicalPendingLocalStore.Record("local-1", "question", emptyList(), "2026-01-01T00:00:00Z"),
        )
        runCurrent()
        assertEquals(listOf("question"), contents(presentation.live.value))
        val fence = coordinator.beginLive(owner)
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(echo("question", "echo", "local-1"))))
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(assistant("hello", "reply"))))
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        runCurrent()
        // The send is on screen exactly once, as the turn's own echoed row.
        assertEquals(listOf("hello", "question"), contents(presentation.live.value))
        presentation.onResidentRows(listOf(row("unrelated", 0L)))
        runCurrent()
        assertEquals(listOf("hello", "question"), contents(presentation.live.value))
        // Only a durable echo clears pending storage, and that write has not happened yet. The
        // local bubble must not reappear as the overlay drains out from under it.
        assertEquals(listOf("local-1"), owner.session.pending.value.map { it.otid })
        presentation.onResidentRows(listOf(row("reply", 1L), row("echo", 1L, otid = "local-1")))
        runCurrent()
        assertEquals(emptyList(), contents(presentation.live.value))
        assertEquals(listOf("local-1"), owner.session.pending.value.map { it.otid })
        presentation.close()
        assertTrue(coordinator.retire(owner))
    }

    @Test fun turnWithoutEventsReleasesTheFenceWithoutAnyResidentRow() = runTest {
        val coordinator = CanonicalTimelineCoordinator(EmptyStore(), NoTransport)
        val owner = coordinator.acquire(TimelineScope("backend", "conversation"))
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, backgroundScope)
        val fence = coordinator.beginLive(owner)
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        runCurrent()
        assertEquals(emptyList(), contents(presentation.live.value))
        // An empty settled ledger never reports this turn's revision, and the fence must not wait.
        presentation.onResidentRows(emptyList())
        runCurrent()
        assertEquals(null, owner.session.live.value)
        presentation.close()
        assertTrue(coordinator.retire(owner))
    }

    private fun contents(items: List<ChatRenderItem>) =
        items.map { (it as ChatRenderItem.Single).message.content }

    /** A settled row as the pager hands it back: identity plus the revision it was read at. */
    private fun row(identity: String, revision: Long, otid: String = "") = CanonicalTimelinePresentation.Row(
        TimelineMessageId(identity), revision,
        ChatRenderItem.Single(UiMessage(identity, "assistant", "settled", timestamp = ""), GroupPosition.None),
        otid = otid,
    )

    private fun assistant(content: String, id: String) = AssistantMessage(
        id = id, contentRaw = kotlinx.serialization.json.JsonPrimitive(content), date = "2026-01-01T00:00:00Z",
    )

    private fun echo(content: String, id: String, otid: String) = UserMessage(
        id = id, contentRaw = kotlinx.serialization.json.JsonPrimitive(content),
        date = "2026-01-01T00:00:00Z", otid = otid,
    )

    private class EmptyStore : TimelineBoundedStore {
        private val evidence = mutableMapOf<String, ByteArray>()
        fun putEvidence(key: String, value: ByteArray) { evidence[key] = value }
        private val tools = mutableMapOf<String, TimelineToolIndexEntry>()
        private var sweepGeneration = 0L
        private val reader = object : TimelineStoreTransaction {
            override suspend fun toolCall(callId: String) = tools[callId]
            override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int): List<TimelineToolIndexEntry> {
                require(maxRows > 0)
                return tools.values.asSequence()
                    .filter { it.owner != null && !it.returned && (afterCallId == null || it.callId > afterCallId) }
                    .sortedBy { it.callId }.take(maxRows).toList()
            }
            override suspend fun toolSweepGeneration() = sweepGeneration
            override suspend fun putToolCall(entry: TimelineToolIndexEntry) { tools[entry.callId] = entry }
            override suspend fun setToolSweepGeneration(next: Long) {
                require(next >= sweepGeneration)
                sweepGeneration = next
            }
            override suspend fun checkpoint() = TimelineDurableCheckpoint(0, null, false)
            override suspend fun locate(identity: TimelineMessageId): TimelinePageKey? = null
            override suspend fun metadata(position: TimelineReadPosition, maxRows: Int) = TimelineMetadataPage(emptyList(), null, null, 0)
            override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray = error("No body")
            override suspend fun evidence(key: String, maxBytes: Int): ByteArray? = evidence[key]
            override suspend fun put(record: TimelineStoredRecord) = error("Unexpected write")
            override suspend fun putEvidence(key: String, value: ByteArray) { evidence[key] = value }
            override suspend fun deleteEvidence(key: String) { evidence.remove(key) }
            override suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean) = Unit
            override suspend fun nextRevision() = 1L
            override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason) = Unit
        }
        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T) = block(reader)
        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T) = block(reader)
    }

    private object NoTransport : TimelineTransport {
        override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?): TimelineRemotePageResult = error("Presentation must not eagerly fetch")
        override suspend fun sendConversationMessage(conversationId: String, request: MessageCreateRequest): Flow<LettaMessage> = error("No send")
        override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = error("No stream")
        override suspend fun listConversationMessages(conversationId: String, limit: Int?, after: String?, order: String?): List<LettaMessage> = error("No legacy hydration")
        override suspend fun listAgentMessages(agentId: String, limit: Int?, order: String?, conversationId: String?): List<LettaMessage> = error("No legacy hydration")
    }
}
