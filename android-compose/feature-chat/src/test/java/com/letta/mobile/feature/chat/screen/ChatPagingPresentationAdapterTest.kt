package com.letta.mobile.feature.chat.screen

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.TimelineBodyPointer
import com.letta.mobile.data.timeline.TimelineBoundedStore
import com.letta.mobile.data.timeline.TimelineContinuation
import com.letta.mobile.data.timeline.TimelineDurableCheckpoint
import com.letta.mobile.data.timeline.TimelineDurableDeleteReason
import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.data.timeline.TimelineMetadataPage
import com.letta.mobile.data.timeline.TimelinePageKey
import com.letta.mobile.data.timeline.TimelineReadPosition
import com.letta.mobile.data.timeline.TimelineStoredRecord
import com.letta.mobile.data.timeline.TimelineStoreReader
import com.letta.mobile.data.timeline.TimelineStoreTransaction
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineToolIndexEntry
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ChatPagingPresentationAdapterTest {
    @Test fun missingTargetKeepsUsableViewAndCloseDoesNotRetireIngestion() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = CanonicalTimelineCoordinator(EmptyStore(), NoTransport)
        val owner = coordinator.acquire(TimelineScope("backend", "conversation", "agent"))
        val first = createCanonicalChatPagingPresentation(coordinator, owner, backgroundScope, "missing")
        assertEquals("missing", first.missingTarget.value)
        assertNull(first.openError)
        assertFalse(first.opening)
        assertFalse(coordinator.retire(owner))
        val second = createCanonicalChatPagingPresentation(coordinator, owner, backgroundScope, null)
        assertNull(second.missingTarget.value)
        assertNotSame(first.settled, second.settled)
        first.close()
        advanceUntilIdle()
        assertFalse(coordinator.retire(owner))
        second.close()
        advanceUntilIdle()
        assertTrue(coordinator.retire(owner))
    }

    @Test fun rapidConversationSwitchRetiresOnlyTheDetachedOwner() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = CanonicalTimelineCoordinator(EmptyStore(), NoTransport)
        val firstOwner = coordinator.acquire(TimelineScope("backend", "first", "agent"))
        val secondOwner = coordinator.acquire(TimelineScope("backend", "second", "agent"))
        val first = createCanonicalChatPagingPresentation(coordinator, firstOwner, backgroundScope, null)
        val second = createCanonicalChatPagingPresentation(coordinator, secondOwner, backgroundScope, "missing")
        assertEquals("missing", second.missingTarget.value)
        first.close()
        advanceUntilIdle()
        assertTrue(coordinator.retire(firstOwner))
        assertFalse(coordinator.retire(secondOwner))
        second.close()
        advanceUntilIdle()
        assertTrue(coordinator.retire(secondOwner))
    }

    @Test fun privateHostBindingDoesNotMutateUnboundRouter() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = CanonicalTimelineCoordinator(EmptyStore(), NoTransport)
        val owner = coordinator.acquire(TimelineScope("backend", "conversation", "agent"))
        val privateHost = ChatPagingHost()
        val unbound = ChatPagingHost()
        privateHost.bindCanonical(coordinator) { agent, conversation ->
            assertEquals("agent", agent)
            assertEquals("conversation", conversation)
            owner
        }
        assertNull(unbound.openCanonical)
        val presentation = checkNotNull(privateHost.openCanonical).invoke("agent", "conversation", "missing", backgroundScope)
        assertEquals("missing", presentation.missingTarget.value)
        presentation.close()
        advanceUntilIdle()
        assertTrue(coordinator.retire(owner))
    }

    @Test fun lateResidentRowsAfterCloseDoNotAffectReplacement() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = CanonicalTimelineCoordinator(EmptyStore(), NoTransport)
        val owner = coordinator.acquire(TimelineScope("backend", "conversation", "agent"))
        val first = createCanonicalChatPagingPresentation(coordinator, owner, backgroundScope, null)
        val second = createCanonicalChatPagingPresentation(coordinator, owner, backgroundScope, "missing")
        assertEquals("missing", second.missingTarget.value)
        first.close()
        first.onResidentRows(
            listOf(
                com.letta.mobile.data.chat.projection.ChatRenderItem.Single(
                    com.letta.mobile.data.model.UiMessage(
                        id = "stale", role = "user", content = "stale", timestamp = "2026-09-07T00:00:00Z",
                    ),
                    com.letta.mobile.ui.common.GroupPosition.None,
                ),
            ),
        )
        assertEquals("missing", second.missingTarget.value)
        assertFalse(second.opening)
        second.close()
        advanceUntilIdle()
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
            override suspend fun metadata(position: TimelineReadPosition, maxRows: Int) =
                TimelineMetadataPage(emptyList(), null, null, 0)
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
        override suspend fun listConversationMessagePage(
            request: com.letta.mobile.data.timeline.TimelineRemotePageRequest,
            progress: com.letta.mobile.data.timeline.TimelinePageProgress?,
        ) = error("Presentation must not eagerly fetch")
        override suspend fun sendConversationMessage(conversationId: String, request: MessageCreateRequest): Flow<LettaMessage> =
            error("No send")
        override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = error("No stream")
        override suspend fun listConversationMessages(
            conversationId: String, limit: Int?, after: String?, order: String?,
        ): List<LettaMessage> = error("No legacy hydration")
        override suspend fun listAgentMessages(
            agentId: String, limit: Int?, order: String?, conversationId: String?,
        ): List<LettaMessage> = error("No legacy hydration")
    }
}
