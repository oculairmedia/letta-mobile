package com.letta.mobile.data.chat.send

import com.letta.mobile.data.chat.sentruns.SentMessageId
import com.letta.mobile.data.chat.sentruns.SentRunId
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore.Delivery
import com.letta.mobile.data.timeline.api.TimelinePendingSendAttempt
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ChatSendCoordinator.identifiedSend] over the real coordinator. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChatSendCoordinatorIdentifiedSendTest : ChatSendCoordinatorConversationFixture() {
    private val timeline = RecordingTimelineWriter()
    private val ui = RecordingUiSink()

    // The bridge deduplicates message frames process-wide, so every test's echoes need their own ids.
    private val echoPrefix = "echo-${Random.nextLong()}"

    /** The echo of the message sent as [otid], on [tail]'s turn and run. */
    private fun echo(otid: SentMessageId, tail: TurnTail) = WsTimelineEvent.MessageDelta(
        message = UserMessage(
            id = "$echoPrefix-${otid.value}", contentRaw = JsonPrimitive("hi"), date = DATE, otid = otid.value, runId = tail.runId,
        ),
        conversationId = CONV,
        turnId = tail.turnId,
    )

    @Test
    fun theIdIsTheOtidTheEnqueuedMessageAndItsEchoCarry() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator(timeline, ui, FakeChannelTransport(mutableListOf(true), activeChatTurn = true)) { CONV }

        val id = assertNotNull(coordinator.identifiedSend.sendIdentified("hello"))
        assertEquals(id.value, timeline.externalLocals.single().otid)

        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT, CONV, "run-1"))
        coordinator.handleEvent(echo(id, TurnTail("turn-1", "run-1")))
        assertEquals(mapOf(id to setOf(SentRunId("run-1"))), coordinator.identifiedSend.sentRuns.value.started)
        assertEquals(setOf(SentRunId("run-1")), coordinator.identifiedSend.sentRuns.value.active)

        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", BridgeTurnStatus.Failed))
        advanceUntilIdle()
        assertTrue(coordinator.identifiedSend.sentRuns.value.active.isEmpty())
        assertEquals(setOf(SentRunId("run-1")), coordinator.identifiedSend.sentRuns.value.failed)
    }

    @Test
    fun anEchoArrivingAfterTheTerminalStillPairsTheSettledRun() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator(timeline, ui, FakeChannelTransport(mutableListOf(true), activeChatTurn = true)) { CONV }

        val id = assertNotNull(coordinator.identifiedSend.sendIdentified("hello"))
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT, CONV, "run-1"))
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", BridgeTurnStatus.Completed))
        advanceUntilIdle()
        assertTrue(coordinator.identifiedSend.sentRuns.value.started.isEmpty())

        coordinator.handleEvent(echo(id, TurnTail("turn-1", "run-1")))
        assertEquals(mapOf(id to setOf(SentRunId("run-1"))), coordinator.identifiedSend.sentRuns.value.started)
        assertTrue(coordinator.identifiedSend.sentRuns.value.active.isEmpty())
    }

    @Test
    fun twoSendsInFlightTogetherGetDistinctIdsEachPairedWithItsOwnRun() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator(timeline, ui, FakeChannelTransport(mutableListOf(true, true), activeChatTurn = true)) { CONV }
        val send = coordinator.identifiedSend

        val firstId = assertNotNull(send.sendIdentified("first"))
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT, CONV, "run-1"))
        coordinator.handleEvent(echo(firstId, TurnTail("turn-1", "run-1")))
        // The second goes out while the first's run is still streaming (the server queues it).
        val secondId = assertNotNull(async { send.sendIdentified("second") }.await())
        assertNotEquals(firstId, secondId)
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", BridgeTurnStatus.Completed))
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-2", AGENT, CONV, "run-2"))
        coordinator.handleEvent(echo(secondId, TurnTail("turn-2", "run-2")))
        advanceUntilIdle()

        assertEquals(
            mapOf(firstId to setOf(SentRunId("run-1")), secondId to setOf(SentRunId("run-2"))),
            send.sentRuns.value.started,
        )
        assertEquals(listOf(firstId.value, secondId.value), timeline.externalLocals.map { it.otid })
    }

    @Test
    fun aCallerCancelledWhileItsMessageIsEnqueuedStillReceivesItsId() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator(timeline, ui, FakeChannelTransport(mutableListOf(true), activeChatTurn = true)) { CONV }
        val release = CompletableDeferred<Unit>().also { timeline.appendRelease = it }
        var reported: SentMessageId? = null

        val caller = launch { reported = coordinator.identifiedSend.sendIdentified("hello") }
        caller.cancel()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(timeline.externalLocals.single().otid, reported?.value)
    }

    @Test
    fun aSendWhoseTimelineWriteFailsAfterTheChannelAcceptedItStillHasItsId() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeChannelTransport(mutableListOf(true), activeChatTurn = true)
        val coordinator = coordinator(timeline, ui, transport) { CONV }
        timeline.appendRelease = CompletableDeferred<Unit>().apply { completeExceptionally(IllegalStateException("write failed")) }

        assertNotNull(coordinator.identifiedSend.sendIdentified("hello"))
        assertEquals(listOf("hello"), transport.sentTexts)
    }

    @Test
    fun aDisconnectWhoseTimelineWriteFailsStillEndsTheSentRunAsFailed() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeChannelTransport(mutableListOf(true), activeChatTurn = true)
        val coordinator = standalone(transport = transport, writer = FailingFailureMarks(timeline)) { CONV }
        val id = assertNotNull(coordinator.identifiedSend.sendIdentified("hello"))
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT, CONV, "run-1"))
        coordinator.handleEvent(echo(id, TurnTail("turn-1", "run-1")))
        assertEquals(setOf(SentRunId("run-1")), coordinator.identifiedSend.sentRuns.value.active)

        coordinator.handleEvent(WsTimelineEvent.Disconnected(code = 1006, reason = "gone"))
        advanceUntilIdle()

        assertTrue(coordinator.identifiedSend.sentRuns.value.active.isEmpty())
        assertEquals(setOf(SentRunId("run-1")), coordinator.identifiedSend.sentRuns.value.failed)
    }

    @Test
    fun aSendThatCannotPrepareItsConversationHasNoId() = runTest(UnconfinedTestDispatcher()) {
        prepareConversation = { error("not ready") }
        val coordinator = coordinator(timeline, ui, FakeChannelTransport(mutableListOf(true))) { CONV }

        assertNull(coordinator.identifiedSend.sendIdentified("hello"))
        assertTrue(timeline.externalLocals.isEmpty())
    }

    @Test
    fun aSendWithNoBackendHasNoId() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = standalone(config = null, transport = FakeChannelTransport(mutableListOf(true))) { CONV }
        assertNull(coordinator.identifiedSend.sendIdentified("hello"))
        assertEquals("No active backend is configured", ui.currentError())
    }

    @Test
    fun aSendWhoseConversationCannotBeCreatedHasNoId() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = standalone(transport = FakeChannelTransport(mutableListOf(true))) { null }
        assertNull(coordinator.identifiedSend.sendIdentified("hello"))
        assertTrue(timeline.externalLocals.isEmpty())
    }

    @Test
    fun aSendOverADisconnectedChannelHasNoId() = runTest(UnconfinedTestDispatcher()) {
        val offline = OfflineTransport(FakeChannelTransport(mutableListOf(true)))
        val coordinator = standalone(transport = offline) { CONV }
        assertNull(coordinator.identifiedSend.sendIdentified("hello"))
        assertEquals("Chat channel is not connected", ui.currentError())
    }

    /**
     * A coordinator with [config], writing through [writer], and a repository that cannot create
     * conversations; it runs on the test's scheduler, so its connect wait passes in virtual time.
     */
    private fun TestScope.standalone(
        config: LettaConfig? = CONFIG,
        transport: IChannelTransport,
        writer: TimelineExternalTransportWriter = timeline,
        activeConversationId: () -> String?,
    ) = ChatSendCoordinator(
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        agentId = AGENT,
        activeConfig = { config },
        wsChatBridge = WsChatBridge(transport),
        timelineRepository = writer,
        conversationRepository = NoCreateConversations(),
        ui = ui,
        clearComposerAfterSend = {},
        activeConversationId = activeConversationId,
        setActiveConversationId = {},
        startTimelineObserver = {},
        clientVersion = { "test" },
        otidGenerator = { "otid-standalone" },
    )

    /** A channel that is not connected and never becomes connected. */
    private class OfflineTransport(base: IChannelTransport) : IChannelTransport by base {
        override val state: StateFlow<ChannelTransportState> = MutableStateFlow(ChannelTransportState.Idle)
    }

    /** A timeline that records like [base] but fails to mark a pending send as failed. */
    private class FailingFailureMarks(private val base: RecordingTimelineWriter) : TimelineExternalTransportWriter by base {
        override suspend fun markPendingSend(attempt: TimelinePendingSendAttempt, delivery: Delivery) {
            check(delivery != Delivery.Failed) { "timeline write failed" }
            base.markPendingSend(attempt, delivery)
        }
    }

    private class NoCreateConversations : IConversationRepository {
        override fun getConversations(agentId: AgentId): Flow<List<Conversation>> = emptyFlow()
        override fun getCachedConversations(agentId: AgentId): List<Conversation> = emptyList()
        override fun hasFreshConversations(agentId: AgentId, maxAgeMs: Long): Boolean = true
        override suspend fun refreshConversations(agentId: AgentId) = Unit
        override suspend fun refreshConversationsIfStale(agentId: AgentId, maxAgeMs: Long): Boolean = false
        override suspend fun getConversation(id: ConversationId): Conversation = error("unused")
        override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation = error("create failed")
        override suspend fun deleteConversation(id: ConversationId, agentId: AgentId) = Unit
        override suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String) = Unit
        override suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean) = Unit
        override suspend fun cancelConversation(id: ConversationId, agentId: AgentId?) = Unit
        override suspend fun recompileConversation(id: ConversationId, dryRun: Boolean, agentId: AgentId?): String = "run"
        override suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation = error("unused")
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONV = "conv-1"
        const val DATE = "2026-11-12T10:00:00Z"
        val CONFIG = LettaConfig("shim", LettaConfig.Mode.SELF_HOSTED, "http://localhost:8291", "token")
    }
}
