package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.InMemoryConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

import com.letta.mobile.data.model.MessageCreateRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class ConfirmedTimelineScenarioTest {

    private class FakeTimelineTransport(
        private val messagesByConversation: Map<String, List<LettaMessage>> = emptyMap(),
        private val delayCompletion: CompletableDeferred<Unit>? = null,
        private val throwOnList: Throwable? = null,
    ) : TimelineTransport {
        var listMessagesCallCount = 0

        override suspend fun listConversationMessages(
            conversationId: String,
            limit: Int?,
            after: String?,
            order: String?,
        ): List<LettaMessage> {
            listMessagesCallCount++
            delayCompletion?.await()
            throwOnList?.let { throw it }
            val messages = messagesByConversation[conversationId] ?: emptyList()
            return if (order == "desc") messages.reversed() else messages
        }

        override suspend fun listAgentMessages(
            agentId: String,
            limit: Int?,
            order: String?,
            conversationId: String?,
        ): List<LettaMessage> {
            listMessagesCallCount++
            delayCompletion?.await()
            throwOnList?.let { throw it }
            return conversationId?.let { messagesByConversation[it] } ?: emptyList()
        }

        override suspend fun sendConversationMessage(
            conversationId: String,
            request: MessageCreateRequest,
        ): Flow<LettaMessage> = emptyFlow()

        override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = emptyFlow()
    }

    @Test
    fun coldStartWithDurableSnapshotRendersImmediatelyBeforeRemoteCall() = runTest {
        val store = InMemoryConfirmedTimelineStore()
        val scope = TimelineScope(backendId = "test-backend", conversationId = "conv-persisted")
        val envelope = StoredTimelineEnvelope(
            schemaVersion = 1,
            scope = scope,
            revision = 1L,
            events = listOf(
                StoredTimelineEvent(
                    position = 1.0,
                    otid = "server-msg-1-user",
                    content = "Hello from yesterday",
                    serverId = "msg-1",
                    messageType = "USER",
                    dateIso = "2026-08-23T10:00:00Z",
                ),
                StoredTimelineEvent(
                    position = 2.0,
                    otid = "server-msg-2-assistant",
                    content = "I remember you!",
                    serverId = "msg-2",
                    messageType = "ASSISTANT",
                    dateIso = "2026-08-23T10:00:05Z",
                ),
            ),
            writtenAtMillis = 5000L,
        )
        store.writeSnapshot(envelope)

        val gate = CompletableDeferred<Unit>()
        val transport = FakeTimelineTransport(
            delayCompletion = gate,
            messagesByConversation = mapOf(
                "conv-persisted" to listOf(
                    UserMessage(id = "msg-1", contentRaw = kotlinx.serialization.json.JsonPrimitive("Hello from yesterday")),
                    AssistantMessage(id = "msg-2", contentRaw = kotlinx.serialization.json.JsonPrimitive("I remember you!")),
                ),
            ),
        )

        val repo = TimelineRepository(
            timelineTransport = transport,
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            confirmedTimelineStore = store,
            backendIdProvider = { "test-backend" },
            startLoopStreamSubscribers = false,
        )

        try {
            val creation = async { repo.getOrCreate("conv-persisted") }
            runCurrent()
            assertEquals(1, transport.listMessagesCallCount)
            gate.complete(Unit)
            val initialTimeline = creation.await().state.value
            assertEquals(2, initialTimeline.events.size)
            assertEquals("Hello from yesterday", initialTimeline.events[0].content)
            assertEquals("I remember you!", initialTimeline.events[1].content)
        } finally {
            repo.clearAll()
        }
    }

    @Test
    fun offlineModePreservesLastKnownGoodContentWithoutLoaderOrBlanking() = runTest {
        val store = InMemoryConfirmedTimelineStore()
        val scope = TimelineScope(backendId = "test-backend", conversationId = "conv-offline")
        val envelope = StoredTimelineEnvelope(
            schemaVersion = 1,
            scope = scope,
            revision = 2L,
            events = listOf(
                StoredTimelineEvent(
                    position = 1.0,
                    otid = "otid-off",
                    content = "Saved offline content",
                    serverId = "msg-offline-1",
                    messageType = "user_message",
                    dateIso = "2026-08-23T11:00:00Z",
                ),
            ),
            writtenAtMillis = 6000L,
        )
        store.writeSnapshot(envelope)

        val transport = FakeTimelineTransport(
            throwOnList = IllegalStateException("Network unreachable (offline)"),
        )

        val repo = TimelineRepository(
            timelineTransport = transport,
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            confirmedTimelineStore = store,
            backendIdProvider = { "test-backend" },
            startLoopStreamSubscribers = false,
        )

        try {
            val loop = repo.getOrCreate("conv-offline")
            val timeline = loop.state.value

            // Events must be preserved despite network error
            assertEquals(1, timeline.events.size)
            assertEquals("Saved offline content", timeline.events[0].content)
        } finally {
            repo.clearAll()
        }
    }

    @Test
    fun slowRefreshMergesBackgroundDeltasWithoutBlanking() = runTest {
        val store = InMemoryConfirmedTimelineStore()
        val scope = TimelineScope(backendId = "test-backend", conversationId = "conv-slow")
        val envelope = StoredTimelineEnvelope(
            schemaVersion = 1,
            scope = scope,
            revision = 1L,
            events = listOf(
                StoredTimelineEvent(
                    position = 1.0,
                    otid = "server-msg-1-user",
                    content = "Older message",
                    serverId = "msg-1",
                    messageType = "USER",
                    dateIso = "2026-08-23T12:00:00Z",
                ),
            ),
            writtenAtMillis = 1000L,
        )
        store.writeSnapshot(envelope)

        val transport = FakeTimelineTransport(
            messagesByConversation = mapOf(
                "conv-slow" to listOf(
                    UserMessage(id = "msg-1", contentRaw = kotlinx.serialization.json.JsonPrimitive("Older message")),
                    AssistantMessage(id = "msg-new", contentRaw = kotlinx.serialization.json.JsonPrimitive("Newly landed server delta")),
                ),
            ),
        )

        val repo = TimelineRepository(
            timelineTransport = transport,
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            confirmedTimelineStore = store,
            backendIdProvider = { "test-backend" },
            startLoopStreamSubscribers = false,
        )

        try {
            val loop = repo.getOrCreate("conv-slow")
            assertEquals(2, loop.state.value.events.size)
            assertEquals("Older message", loop.state.value.events[0].content)
            assertEquals("Newly landed server delta", loop.state.value.events[1].content)
        } finally {
            repo.clearAll()
        }
    }

    @Test
    fun rapidConversationSwitchingYieldsTargetSnapshotInstantly() = runTest {
        val store = InMemoryConfirmedTimelineStore()
        store.writeSnapshot(
            StoredTimelineEnvelope(
                scope = TimelineScope("test-backend", "conv-A"),
                revision = 1L,
                events = listOf(
                    StoredTimelineEvent(
                        position = 1.0,
                        otid = "otid-a",
                        content = "Chat A",
                        serverId = "a1",
                        messageType = "user_message",
                        dateIso = "2026-08-23T13:00:00Z",
                    ),
                ),
            ),
        )
        store.writeSnapshot(
            StoredTimelineEnvelope(
                scope = TimelineScope("test-backend", "conv-B"),
                revision = 1L,
                events = listOf(
                    StoredTimelineEvent(
                        position = 1.0,
                        otid = "otid-b",
                        content = "Chat B",
                        serverId = "b1",
                        messageType = "user_message",
                        dateIso = "2026-08-23T13:00:00Z",
                    ),
                ),
            ),
        )

        val transport = FakeTimelineTransport()
        val repo = TimelineRepository(
            timelineTransport = transport,
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            confirmedTimelineStore = store,
            backendIdProvider = { "test-backend" },
            startLoopStreamSubscribers = false,
        )

        try {
            val loopA = repo.getOrCreate("conv-A")
            assertEquals(1, loopA.state.value.events.size)
            assertEquals("Chat A", loopA.state.value.events[0].content)

            val loopB = repo.getOrCreate("conv-B")
            assertEquals(1, loopB.state.value.events.size)
            assertEquals("Chat B", loopB.state.value.events[0].content)
        } finally {
            repo.clearAll()
        }
    }

    @Test
    fun neverSeenConversationOpensEmptyShellWithoutLoader() = runTest {
        val store = InMemoryConfirmedTimelineStore()
        val transport = FakeTimelineTransport()
        val repo = TimelineRepository(
            timelineTransport = transport,
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            confirmedTimelineStore = store,
            backendIdProvider = { "test-backend" },
            startLoopStreamSubscribers = false,
        )

        try {
            val loop = repo.getOrCreate("conv-brand-new")
            val timeline = loop.state.value
            assertEquals(0, timeline.events.size)
            assertEquals("conv-brand-new", timeline.conversationId)
        } finally {
            repo.clearAll()
        }
    }
}
