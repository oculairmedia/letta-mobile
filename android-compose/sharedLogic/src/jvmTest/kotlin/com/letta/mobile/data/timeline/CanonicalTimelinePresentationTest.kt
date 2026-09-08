package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.snapshot.TimelineScope
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

    private class EmptyStore : TimelineBoundedStore {
        private val evidence = mutableMapOf<String, ByteArray>()
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
