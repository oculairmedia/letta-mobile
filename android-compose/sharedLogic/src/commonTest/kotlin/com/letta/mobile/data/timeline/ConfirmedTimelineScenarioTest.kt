package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.InMemoryConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfirmedTimelineScenarioTest {

    private class FakeTimelineTransport(
        private val messagesByConversation: Map<String, List<LettaMessage>> = emptyMap(),
        private val delayCompletion: CompletableDeferred<Unit>? = null,
        private val throwOnList: Throwable? = null,
    ) : TimelineTransport by EmptyTimelineTransport {
        var listMessagesCallCount = 0
        val listMessagesStarted = CompletableDeferred<Unit>()

        override suspend fun listConversationMessages(
            conversationId: String,
            limit: Int?,
            after: String?,
            order: String?,
        ): List<LettaMessage> {
            listMessagesCallCount++
            listMessagesStarted.complete(Unit)
            delayCompletion?.await()
            throwOnList?.let { throw it }
            val messages = messagesByConversation[conversationId] ?: emptyList()
            return if (order == "desc") messages.reversed() else messages
        }
    }

    private fun TimelineScope.testEnvelope(events: List<StoredTimelineEvent>) = StoredTimelineEnvelope(
        schemaVersion = StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION,
        scope = this,
        revision = 1L,
        events = events,
        writtenAtMillis = 5000L,
    )

    private val storedEventFixture = StoredTimelineEvent(
        position = 1.0,
        otid = "server-message-user",
        content = "fixture",
        serverId = "message",
        messageType = "USER",
        dateIso = "2026-08-23T10:00:00Z",
    )

    private fun createTestRepo(
        transport: TimelineTransport,
        store: ConfirmedTimelineStore = InMemoryConfirmedTimelineStore(),
        repositoryScope: CoroutineScope,
    ) = TimelineRepository(
        timelineTransport = transport,
        pendingLocalStore = NoOpPendingLocalStore,
        conversationCursorStore = NoOpConversationCursorStore,
        confirmedTimelineStore = store,
        backendIdProvider = { "test-backend" },
        repositoryScope = repositoryScope,
        startLoopStreamSubscribers = false,
    )

    private fun kotlinx.coroutines.CoroutineScope.createTestLoop(
        scope: TimelineScope,
        transport: TimelineTransport,
        store: ConfirmedTimelineStore = InMemoryConfirmedTimelineStore(),
        snapshot: StoredTimelineEnvelope? = null,
    ) = TimelineSyncLoop(
        messageApi = transport,
        conversationId = scope.conversationId,
        scope = this,
        startStreamSubscriber = false,
        confirmedTimelineStore = store,
        timelineScope = scope,
        initialTimeline = snapshot?.let(TimelineSnapshotCodec::storedEnvelopeToTimeline),
        initialRevision = snapshot?.revision ?: 0L,
    )

    @Test
    fun coldStartWithDurableSnapshotRendersImmediatelyBeforeRemoteCall() = runTest {
        val store = InMemoryConfirmedTimelineStore()
        val scope = TimelineScope(backendId = "test-backend", conversationId = "conv-persisted")
        val envelope = scope.testEnvelope(
            listOf(
                storedEventFixture.copy(
                    otid = "server-msg-1-user",
                    content = "Hello from yesterday",
                    serverId = "msg-1",
                ),
                storedEventFixture.copy(
                    position = 2.0,
                    otid = "server-msg-2-assistant",
                    content = "I remember you!",
                    serverId = "msg-2",
                    messageType = "ASSISTANT",
                    dateIso = "2026-08-23T10:00:05Z",
                ),
            ),
        )
        store.writeSnapshot(envelope)

        val gate = CompletableDeferred<Unit>()
        val transport = FakeTimelineTransport(
            delayCompletion = gate,
            messagesByConversation = mapOf(
                "conv-persisted" to listOf(
                    UserMessage(
                        id = "fresh-msg",
                        contentRaw = kotlinx.serialization.json.JsonPrimitive("Fresh from the server"),
                    ),
                ),
            ),
        )
        val snapshot = store.readSnapshot(scope)
        assertNotNull(snapshot)
        val loop = createTestLoop(scope, transport, store, snapshot)

        try {
            val hydration = async { loop.hydrate() }
            transport.listMessagesStarted.await()
            assertEquals(1, transport.listMessagesCallCount)
            assertEquals("Hello from yesterday", loop.state.value.events[0].content)
            assertEquals("I remember you!", loop.state.value.events[1].content)

            gate.complete(Unit)
            hydration.await()
            assertTrue(loop.state.value.events.any { it.content == "Fresh from the server" })
        } finally {
            loop.close()
        }
    }

    @Test
    fun offlineModePreservesLastKnownGoodContentWithoutLoaderOrBlanking() = runTest {
        val store = InMemoryConfirmedTimelineStore()
        val scope = TimelineScope(backendId = "test-backend", conversationId = "conv-offline")
        val envelope = scope.testEnvelope(
            listOf(
                storedEventFixture.copy(
                    otid = "server-msg-offline-1-user",
                    content = "Saved offline content",
                    serverId = "msg-offline-1",
                    dateIso = "2026-08-23T11:00:00Z",
                ),
            ),
        ).copy(revision = 2L, writtenAtMillis = 6000L)
        store.writeSnapshot(envelope)

        val transport = FakeTimelineTransport(
            throwOnList = IllegalStateException("Network unreachable (offline)"),
        )
        val repo = createTestRepo(transport, store, backgroundScope)

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
        val envelope = scope.testEnvelope(
            listOf(
                storedEventFixture.copy(
                    otid = "server-msg-1-user",
                    content = "Older message",
                    serverId = "msg-1",
                    dateIso = "2026-08-23T12:00:00Z",
                ),
            ),
        ).copy(writtenAtMillis = 1000L)
        store.writeSnapshot(envelope)

        val gate = CompletableDeferred<Unit>()
        val transport = FakeTimelineTransport(
            delayCompletion = gate,
            messagesByConversation = mapOf(
                "conv-slow" to listOf(
                    UserMessage(id = "msg-1", contentRaw = kotlinx.serialization.json.JsonPrimitive("Older message")),
                    AssistantMessage(id = "msg-new", contentRaw = kotlinx.serialization.json.JsonPrimitive("Newly landed server delta")),
                ),
            ),
        )
        val snapshot = store.readSnapshot(scope)
        assertNotNull(snapshot)
        val loop = createTestLoop(scope, transport, store, snapshot)

        try {
            val hydration = async { loop.hydrate() }
            transport.listMessagesStarted.await()
            assertEquals(listOf("Older message"), loop.state.value.events.map { it.content })

            gate.complete(Unit)
            hydration.await()
            assertEquals(2, loop.state.value.events.size)
            assertEquals("Older message", loop.state.value.events[0].content)
            assertEquals("Newly landed server delta", loop.state.value.events[1].content)
        } finally {
            loop.close()
        }
    }

    @Test
    fun rapidConversationSwitchingYieldsTargetSnapshotInstantly() = runTest {
        val store = InMemoryConfirmedTimelineStore()
        store.writeSnapshot(
            TimelineScope(backendId = "test-backend", conversationId = "conv-A").testEnvelope(
                listOf(storedEventFixture.copy(otid = "server-a1-user", content = "Chat A", serverId = "a1")),
            ),
        )
        store.writeSnapshot(
            TimelineScope(backendId = "test-backend", conversationId = "conv-B").testEnvelope(
                listOf(storedEventFixture.copy(otid = "server-b1-user", content = "Chat B", serverId = "b1")),
            ),
        )

        val transport = FakeTimelineTransport()
        val repo = createTestRepo(transport, store, backgroundScope)

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
        val repo = createTestRepo(transport, store, backgroundScope)

        try {
            val loop = repo.getOrCreate("conv-brand-new")
            val timeline = loop.state.value
            assertEquals(0, timeline.events.size)
            assertEquals("conv-brand-new", timeline.conversationId)
        } finally {
            repo.clearAll()
        }
    }

    @Test
    fun hydrationPreservesNewerStreamedEventsWithoutPrepending() {
        val oldEvent = TimelineEvent.Confirmed(
            position = 1.0,
            otid = "server-srv-1-user",
            content = "Oldest message",
            serverId = "srv-1",
            messageType = TimelineMessageType.USER,
            date = parseTimelineInstant("2026-08-23T08:00:00Z"),
            runId = null,
            stepId = null,
        )
        val newerStreamedEvent = TimelineEvent.Confirmed(
            position = 3.0,
            otid = "server-srv-3-assistant",
            content = "Newer streamed message",
            serverId = "srv-3",
            messageType = TimelineMessageType.ASSISTANT,
            date = parseTimelineInstant("2026-08-23T12:00:00Z"),
            runId = null,
            stepId = null,
        )
        val timelineBefore = Timeline(
            conversationId = "conv-order",
            events = kotlinx.collections.immutable.persistentListOf(oldEvent, newerStreamedEvent),
        )
        val serverMiddleMessage = UserMessage(
            id = "srv-2",
            date = "2026-08-23T10:00:00Z",
            contentRaw = kotlinx.serialization.json.JsonPrimitive("Middle message"),
        )

        val result = TimelineHydrationReducer.reduce(
            conversationId = "conv-order",
            serverMessagesChronological = listOf(serverMiddleMessage),
            timelineBeforeFetch = timelineBefore,
            currentTimeline = timelineBefore,
            diskRecords = emptyList(),
        )

        val contents = result.timeline.events.map { it.content }
        assertEquals(listOf("Oldest message", "Middle message", "Newer streamed message"), contents)
    }
}
