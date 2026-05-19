package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WsChatSendCoordinatorTest {
    private val clientVersionProvider = object : ChatClientVersionProvider {
        override val clientVersion: String = "letta-mobile/test (android)"
    }

    @Test
    fun `send dispatches through ws bridge and appends optimistic local with android otid`() = runTest {
        val settingsRepository = settingsRepository()
        val wsChatBridge = mockBridge(sendAccepted = true)
        val timelineRepository = mockk<TimelineRepository>(relaxed = true)
        val otid = slot<String>()
        coEvery {
            timelineRepository.appendExternalTransportLocal("conv-default-agent-1", "hello", capture(otid), emptyList())
        } answers { otid.captured }
        var cleared = false
        var activeConversation: String? = null
        var observedConversation: String? = null
        val uiState = MutableStateFlow(ChatUiState(agentName = "Agent"))

        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository,
            wsChatBridge = wsChatBridge,
            timelineRepository = timelineRepository,
            conversationRepository = stubConversationRepository(),
            uiState = uiState,
            clearComposerAfterSend = { cleared = true },
            activeConversationId = { activeConversation },
            setActiveConversationId = { activeConversation = it },
            startTimelineObserver = { observedConversation = it },
            clientVersionProvider = clientVersionProvider,
        )

        coordinator.send("hello").join()

        assertTrue(otid.captured.startsWith("cm-android-"))
        assertTrue(cleared)
        assertEquals("conv-default-agent-1", activeConversation)
        assertEquals("conv-default-agent-1", observedConversation)
        assertTrue(uiState.value.isStreaming)
        verify {
            wsChatBridge.send(
                agentId = "agent-1",
                conversationId = "conv-default-agent-1",
                text = "hello",
                otid = otid.captured,
                attachments = emptyList(),
            )
        }
    }

    @Test
    fun `send with image attachments passes them through to the bridge (lcp-dlj)`() = runTest {
        val wsChatBridge = mockBridge(sendAccepted = true)
        val timelineRepository = mockk<TimelineRepository>(relaxed = true)
        val otid = slot<String>()
        coEvery {
            timelineRepository.appendExternalTransportLocal("conv-default-agent-1", "look", capture(otid), any())
        } answers { otid.captured }
        val image = com.letta.mobile.data.model.MessageContentPart.Image(
            base64 = "AAA=",
            mediaType = "image/jpeg",
        )
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = wsChatBridge,
            timelineRepository = timelineRepository,
            conversationRepository = stubConversationRepository(),
            uiState = MutableStateFlow(ChatUiState(agentName = "Agent")),
            clearComposerAfterSend = {},
            activeConversationId = { null },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersionProvider = clientVersionProvider,
        )

        coordinator.send("look", listOf(image)).join()

        verify(exactly = 1) {
            wsChatBridge.send(
                agentId = "agent-1",
                conversationId = "conv-default-agent-1",
                text = "look",
                otid = otid.captured,
                attachments = listOf(image),
            )
        }
        coVerify(exactly = 1) {
            timelineRepository.appendExternalTransportLocal(
                conversationId = "conv-default-agent-1",
                content = "look",
                otid = otid.captured,
                attachments = listOf(image),
            )
        }
    }

    @Test
    fun `busy send is queued with optimistic local and drains on turn done`() = runTest {
        val wsChatBridge = mockBridge(sendResults = listOf(false, true))
        val timelineRepository = mockk<TimelineRepository>(relaxed = true)
        var cleared = false
        val uiState = MutableStateFlow(ChatUiState(agentName = "Agent"))
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = wsChatBridge,
            timelineRepository = timelineRepository,
            conversationRepository = stubConversationRepository(),
            uiState = uiState,
            clearComposerAfterSend = { cleared = true },
            activeConversationId = { "conv-1" },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersionProvider = clientVersionProvider,
        )

        coordinator.send("hello").join()

        assertNull(uiState.value.error)
        assertTrue(cleared)
        assertTrue(uiState.value.isStreaming)
        coVerify(exactly = 1) {
            timelineRepository.appendExternalTransportLocal("conv-1", "hello", any(), emptyList())
        }

        coordinator.handleEvent(WsTimelineEvent.TurnDone(turnId = "turn-1", runId = "run-1", status = "completed"))
        advanceUntilIdle()

        verify(exactly = 2) {
            wsChatBridge.send(
                agentId = "agent-1",
                conversationId = "conv-1",
                text = "hello",
                otid = any(),
                attachments = emptyList(),
            )
        }
    }

    @Test
    fun `busy send queue drops overflow without appending optimistic local`() = runTest {
        val wsChatBridge = mockBridge(sendAccepted = false)
        val timelineRepository = mockk<TimelineRepository>(relaxed = true)
        val uiState = MutableStateFlow(ChatUiState(agentName = "Agent"))
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = wsChatBridge,
            timelineRepository = timelineRepository,
            conversationRepository = stubConversationRepository(),
            uiState = uiState,
            clearComposerAfterSend = {},
            activeConversationId = { "conv-1" },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersionProvider = clientVersionProvider,
        )

        repeat(11) { index -> coordinator.send("message-$index").join() }

        assertEquals("WebSocket send queue is full; wait for the current turn to finish", uiState.value.error)
        coVerify(exactly = 10) { timelineRepository.appendExternalTransportLocal("conv-1", any(), any(), emptyList()) }
    }

    @Test
    fun `disconnect clears queued sends and marks optimistic locals failed`() = runTest {
        val wsChatBridge = mockBridge(sendAccepted = false)
        val timelineRepository = mockk<TimelineRepository>(relaxed = true)
        val uiState = MutableStateFlow(ChatUiState(agentName = "Agent", isStreaming = true, isAgentTyping = true))
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = wsChatBridge,
            timelineRepository = timelineRepository,
            conversationRepository = stubConversationRepository(),
            uiState = uiState,
            clearComposerAfterSend = {},
            activeConversationId = { "conv-1" },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersionProvider = clientVersionProvider,
        )

        coordinator.send("one").join()
        coordinator.send("two").join()

        coordinator.handleEvent(WsTimelineEvent.Disconnected(code = 1006, reason = "network lost"))
        advanceUntilIdle()

        assertEquals("network lost", uiState.value.error)
        assertEquals(false, uiState.value.isStreaming)
        assertEquals(false, uiState.value.isAgentTyping)
        coVerify(exactly = 2) { timelineRepository.markExternalTransportLocalFailed("conv-1", any()) }
    }

    @Test
    fun `usage statistics writes tokens to ui state once per turn`() = runTest {
        val wsChatBridge = mockBridge(sendAccepted = true)
        val uiState = MutableStateFlow(ChatUiState(agentName = "Agent"))
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = wsChatBridge,
            timelineRepository = mockk<TimelineRepository>(relaxed = true),
            conversationRepository = stubConversationRepository(),
            uiState = uiState,
            clearComposerAfterSend = {},
            activeConversationId = { null },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersionProvider = clientVersionProvider,
        )

        coordinator.handleEvent(
            WsTimelineEvent.UsageStatistics(
                turnId = "turn-1",
                runId = "run-1",
                promptTokens = 42L,
                completionTokens = 17L,
                totalTokens = 59L,
                cachedInputTokens = 5L,
                reasoningTokens = 3L,
            )
        )
        advanceUntilIdle()

        assertEquals(42, uiState.value.promptTokens)
        assertEquals(17, uiState.value.completionTokens)
        assertEquals(59, uiState.value.totalTokens)

        // Defensive first-wins: a second usage frame for the same turn is dropped.
        coordinator.handleEvent(
            WsTimelineEvent.UsageStatistics(
                turnId = "turn-1",
                runId = "run-1",
                promptTokens = 999L,
                completionTokens = 999L,
                totalTokens = 999L,
                cachedInputTokens = 0L,
                reasoningTokens = 0L,
            )
        )
        advanceUntilIdle()

        assertEquals(42, uiState.value.promptTokens)
        assertEquals(17, uiState.value.completionTokens)
        assertEquals(59, uiState.value.totalTokens)
    }

    @Test
    fun `turn started resets per turn first wins state`() = runTest {
        val wsChatBridge = mockBridge(sendAccepted = true)
        val uiState = MutableStateFlow(ChatUiState(agentName = "Agent"))
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = wsChatBridge,
            timelineRepository = mockk<TimelineRepository>(relaxed = true),
            conversationRepository = stubConversationRepository(),
            uiState = uiState,
            clearComposerAfterSend = {},
            activeConversationId = { null },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersionProvider = clientVersionProvider,
        )

        coordinator.handleEvent(
            WsTimelineEvent.UsageStatistics(
                turnId = "turn-1", runId = "run-1",
                promptTokens = 10L, completionTokens = 5L, totalTokens = 15L,
                cachedInputTokens = 0L, reasoningTokens = 0L,
            )
        )
        coordinator.handleEvent(WsTimelineEvent.TurnDone(turnId = "turn-1", runId = "run-1", status = "completed"))
        coordinator.handleEvent(WsTimelineEvent.TurnStarted(turnId = "turn-2", agentId = "agent-1", conversationId = "conv-default-agent-1", runId = "run-2"))
        coordinator.handleEvent(
            WsTimelineEvent.UsageStatistics(
                turnId = "turn-2", runId = "run-2",
                promptTokens = 100L, completionTokens = 50L, totalTokens = 150L,
                cachedInputTokens = 0L, reasoningTokens = 0L,
            )
        )
        advanceUntilIdle()

        assertEquals(100, uiState.value.promptTokens)
        assertEquals(50, uiState.value.completionTokens)
        assertEquals(150, uiState.value.totalTokens)
    }

    @Test
    fun `turn done failed surfaces an error message`() = runTest {
        val wsChatBridge = mockBridge(sendAccepted = true)
        val uiState = MutableStateFlow(ChatUiState(agentName = "Agent", isStreaming = true, isAgentTyping = true))
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = wsChatBridge,
            timelineRepository = mockk<TimelineRepository>(relaxed = true),
            conversationRepository = stubConversationRepository(),
            uiState = uiState,
            clearComposerAfterSend = {},
            activeConversationId = { null },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersionProvider = clientVersionProvider,
        )

        coordinator.handleEvent(WsTimelineEvent.TurnDone(turnId = "turn-1", runId = "run-1", status = "failed"))
        advanceUntilIdle()

        assertEquals("Turn failed", uiState.value.error)
        assertEquals(false, uiState.value.isStreaming)
        assertEquals(false, uiState.value.isAgentTyping)
    }

    @Test
    fun `turn done cancelled does not set error banner`() = runTest {
        val wsChatBridge = mockBridge(sendAccepted = true)
        val uiState = MutableStateFlow(ChatUiState(agentName = "Agent", isStreaming = true, isAgentTyping = true))
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = wsChatBridge,
            timelineRepository = mockk<TimelineRepository>(relaxed = true),
            conversationRepository = stubConversationRepository(),
            uiState = uiState,
            clearComposerAfterSend = {},
            activeConversationId = { null },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersionProvider = clientVersionProvider,
        )

        coordinator.handleEvent(WsTimelineEvent.TurnDone(turnId = "turn-1", runId = "run-1", status = "cancelled"))
        advanceUntilIdle()

        assertEquals(null, uiState.value.error)
        assertEquals(false, uiState.value.isStreaming)
        assertEquals(false, uiState.value.isAgentTyping)
    }

    @Test
    fun `clean turn done skips reconcile when shim reports lossy false`() = runTest {
        val wsChatBridge = mockBridge(sendAccepted = true)
        val timelineRepository = mockk<TimelineRepository>(relaxed = true)
        val otid = slot<String>()
        coEvery {
            timelineRepository.appendExternalTransportLocal("conv-default-agent-1", "hello", capture(otid), emptyList())
        } answers { otid.captured }
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = wsChatBridge,
            timelineRepository = timelineRepository,
            conversationRepository = stubConversationRepository(),
            uiState = MutableStateFlow(ChatUiState(agentName = "Agent")),
            clearComposerAfterSend = {},
            activeConversationId = { null },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersionProvider = clientVersionProvider,
        )

        coordinator.send("hello").join()
        coordinator.handleEvent(WsTimelineEvent.TurnDone(turnId = "turn-1", runId = "run-1", status = "completed", lossy = false))
        advanceUntilIdle()

        coVerify(exactly = 0) {
            timelineRepository.reconcileExternalTransportSend(any(), any(), any(), any())
        }
    }

    @Test
    fun `lossy turn done forces a reconcile against external default conversation id`() = runTest {
        val wsChatBridge = mockBridge(sendAccepted = true)
        val timelineRepository = mockk<TimelineRepository>(relaxed = true)
        val otid = slot<String>()
        coEvery {
            timelineRepository.appendExternalTransportLocal("conv-default-agent-1", "hello", capture(otid), emptyList())
        } answers { otid.captured }
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = wsChatBridge,
            timelineRepository = timelineRepository,
            conversationRepository = stubConversationRepository(),
            uiState = MutableStateFlow(ChatUiState(agentName = "Agent")),
            clearComposerAfterSend = {},
            activeConversationId = { null },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersionProvider = clientVersionProvider,
        )

        coordinator.send("hello").join()
        coordinator.handleEvent(
            WsTimelineEvent.TurnDone(
                turnId = "turn-1", runId = "run-1", status = "completed",
                lossy = true, dropCount = 3L,
            )
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            timelineRepository.reconcileExternalTransportSend(
                conversationId = "conv-default-agent-1",
                agentId = "agent-1",
                externalConversationId = "conv-default-agent-1",
                otid = otid.captured,
            )
        }
    }

    @Test
    fun `failed turn surfaces buffered error message from preceding error frame`() = runTest {
        val wsChatBridge = mockBridge(sendAccepted = true)
        val uiState = MutableStateFlow(ChatUiState(agentName = "Agent", isStreaming = true, isAgentTyping = true))
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = wsChatBridge,
            timelineRepository = mockk<TimelineRepository>(relaxed = true),
            conversationRepository = stubConversationRepository(),
            uiState = uiState,
            clearComposerAfterSend = {},
            activeConversationId = { null },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersionProvider = clientVersionProvider,
        )

        coordinator.handleEvent(
            WsTimelineEvent.Error(
                code = "worker_exit",
                message = "Worker died",
                turnId = "turn-1",
                runId = "run-1",
            )
        )
        // After the error frame alone, UI should NOT have flipped yet — the
        // contract pairs error + turn_done(failed) in lock-step.
        advanceUntilIdle()
        assertEquals(true, uiState.value.isStreaming)
        assertEquals(null, uiState.value.error)

        coordinator.handleEvent(WsTimelineEvent.TurnDone(turnId = "turn-1", runId = "run-1", status = "failed"))
        advanceUntilIdle()

        assertEquals("Worker died", uiState.value.error)
        assertEquals(false, uiState.value.isStreaming)
        assertEquals(false, uiState.value.isAgentTyping)
    }

    private fun settingsRepository(): () -> LettaConfig? = {
        LettaConfig(
            id = "shim",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8291",
            accessToken = "token",
        )
    }

    /**
     * letta-mobile-vcky: WsChatSendCoordinator now mints a fresh conversation
     * via the repository when activeConversation is null. Tests that were
     * written for the old `conv-default-<agentId>` hardcode keep their
     * assertions by stubbing the repo to return a Conversation with the same
     * id; tests that exercise picker-selected ids pre-set activeConversation
     * and never hit the create path.
     */
    private fun stubConversationRepository(
        conversationId: String = "conv-default-agent-1",
        agentId: String = "agent-1",
    ): ConversationRepository = mockk(relaxed = true) {
        coEvery { createConversation(agentId, any()) } returns Conversation(
            id = conversationId,
            agentId = agentId,
            createdAt = "1970-01-01T00:00:00Z",
            updatedAt = "1970-01-01T00:00:00Z",
            lastMessageAt = "1970-01-01T00:00:00Z",
        )
    }

    private fun mockBridge(
        sendAccepted: Boolean,
        eventFlow: kotlinx.coroutines.flow.Flow<WsTimelineEvent> = emptyFlow(),
    ): WsChatBridge = mockBridge(
        sendResults = listOf(sendAccepted),
        eventFlow = eventFlow,
    )

    private fun mockBridge(
        sendResults: List<Boolean>,
        eventFlow: kotlinx.coroutines.flow.Flow<WsTimelineEvent> = emptyFlow(),
    ): WsChatBridge = mockk(relaxed = true) {
        every { state } returns MutableStateFlow(
            ChannelTransport.State.Connected(
                serverId = "server-1",
                sessionId = "sess-1",
                deviceId = "android-letta-mobile",
            )
        )
        every { events } returns eventFlow
        every { send(any(), any(), any(), any(), any()) } returnsMany sendResults
    }
}
