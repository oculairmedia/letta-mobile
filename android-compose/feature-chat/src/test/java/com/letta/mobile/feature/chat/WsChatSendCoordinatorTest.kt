package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsConnectionState
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.testutil.FakeTimelineExternalTransportWriter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
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
        val timelineRepository = FakeTimelineExternalTransportWriter()
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

        val local = timelineRepository.externalLocals.single()
        assertTrue(local.otid.startsWith("cm-android-"))
        assertTrue(cleared)
        assertEquals("conv-default-agent-1", activeConversation)
        assertEquals("conv-default-agent-1", observedConversation)
        assertTrue(uiState.value.isStreaming)
        verify {
            wsChatBridge.send(
                agentId = "agent-1",
                conversationId = "conv-default-agent-1",
                text = "hello",
                otid = local.otid,
                attachments = emptyList(),
            )
        }
    }

    @Test
    fun `fresh route on connected ws asks shim to create conversation and adopts turn started id`() = runTest {
        val wsChatBridge = mockBridge(sendAccepted = true)
        val timelineRepository = FakeTimelineExternalTransportWriter()
        val conversationRepository = stubConversationRepository(conversationId = "conv-rest-fallback")
        var activeConversation: String? = null
        var observedConversation: String? = null
        var cleared = false
        val uiState = MutableStateFlow(ChatUiState(agentName = "Agent"))
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = wsChatBridge,
            timelineRepository = timelineRepository,
            conversationRepository = conversationRepository,
            uiState = uiState,
            clearComposerAfterSend = { cleared = true },
            activeConversationId = { activeConversation },
            isFreshRoute = true,
            setActiveConversationId = { activeConversation = it },
            startTimelineObserver = { observedConversation = it },
            clientVersionProvider = clientVersionProvider,
        )

        coordinator.send("hello").join()

        coVerify(exactly = 0) { conversationRepository.createConversation(any(), any()) }
        assertTrue(cleared)
        assertTrue(timelineRepository.externalLocals.isEmpty())
        verify(exactly = 1) {
            wsChatBridge.send(
                agentId = "agent-1",
                conversationId = "",
                text = "hello",
                otid = any(),
                attachments = emptyList(),
                startNewConversation = true,
            )
        }

        coordinator.handleEvent(
            WsTimelineEvent.TurnStarted(
                turnId = "turn-1",
                agentId = "agent-1",
                conversationId = "conv-shim-created",
                runId = "run-1",
            )
        )
        advanceUntilIdle()

        assertEquals("conv-shim-created", activeConversation)
        assertEquals("conv-shim-created", observedConversation)
        assertEquals("conv-shim-created", timelineRepository.externalLocals.single().conversationId)
        assertEquals(ConversationState.Ready("conv-shim-created"), uiState.value.conversationState)
    }

    @Test
    fun `send with image attachments passes them through to the bridge (lcp-dlj)`() = runTest {
        val wsChatBridge = mockBridge(sendAccepted = true)
        val timelineRepository = FakeTimelineExternalTransportWriter()
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

        val local = timelineRepository.externalLocals.single()
        verify(exactly = 1) {
            wsChatBridge.send(
                agentId = "agent-1",
                conversationId = "conv-default-agent-1",
                text = "look",
                otid = local.otid,
                attachments = listOf(image),
            )
        }
        assertEquals("conv-default-agent-1", local.conversationId)
        assertEquals("look", local.content)
        assertEquals(listOf(image), local.attachments)
    }

    @Test
    fun `busy send is queued with optimistic local and drains on turn done`() = runTest {
        val wsChatBridge = mockBridge(sendResults = listOf(false, true))
        val timelineRepository = FakeTimelineExternalTransportWriter()
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
        assertEquals(1, timelineRepository.externalLocals.size)
        assertEquals("conv-1", timelineRepository.externalLocals.single().conversationId)
        assertEquals("hello", timelineRepository.externalLocals.single().content)

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
        val timelineRepository = FakeTimelineExternalTransportWriter()
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
        assertEquals(10, timelineRepository.externalLocals.size)
        assertTrue(timelineRepository.externalLocals.all { it.conversationId == "conv-1" })
    }

    @Test
    fun `disconnect clears queued sends and marks optimistic locals failed`() = runTest {
        val wsChatBridge = mockBridge(sendAccepted = false)
        val timelineRepository = FakeTimelineExternalTransportWriter()
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
        assertEquals(2, timelineRepository.failedLocals.size)
        assertTrue(timelineRepository.failedLocals.all { it.conversationId == "conv-1" })
    }

    @Test
    fun `cancel clears only queued sends for active conversation`() = runTest {
        val wsChatBridge = mockBridge(sendResults = listOf(false, false, true), cancelResult = true)
        val timelineRepository = FakeTimelineExternalTransportWriter()
        var activeConversation = "conv-a"
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = wsChatBridge,
            timelineRepository = timelineRepository,
            conversationRepository = stubConversationRepository(),
            uiState = MutableStateFlow(ChatUiState(agentName = "Agent")),
            clearComposerAfterSend = {},
            activeConversationId = { activeConversation },
            setActiveConversationId = { activeConversation = it },
            startTimelineObserver = {},
            clientVersionProvider = clientVersionProvider,
        )

        coordinator.send("one").join()
        activeConversation = "conv-b"
        coordinator.send("two").join()

        activeConversation = "conv-a"
        assertTrue(coordinator.cancel())
        advanceUntilIdle()

        verify(exactly = 1) { wsChatBridge.cancel("conv-a") }

        coordinator.handleEvent(WsTimelineEvent.TurnDone(turnId = "turn-a", runId = "run-a", status = "cancelled"))
        advanceUntilIdle()

        verify(exactly = 1) {
            wsChatBridge.send(
                agentId = "agent-1",
                conversationId = "conv-a",
                text = "one",
                otid = any(),
                attachments = emptyList(),
            )
        }
        verify(exactly = 2) {
            wsChatBridge.send(
                agentId = "agent-1",
                conversationId = "conv-b",
                text = "two",
                otid = any(),
                attachments = emptyList(),
            )
        }
    }

    @Test
    fun `cancel does not clear queued sends when bridge rejects cancel`() = runTest {
        val wsChatBridge = mockBridge(sendResults = listOf(false, true), cancelResult = false)
        val timelineRepository = FakeTimelineExternalTransportWriter()
        var activeConversation = "conv-a"
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = wsChatBridge,
            timelineRepository = timelineRepository,
            conversationRepository = stubConversationRepository(),
            uiState = MutableStateFlow(ChatUiState(agentName = "Agent")),
            clearComposerAfterSend = {},
            activeConversationId = { activeConversation },
            setActiveConversationId = { activeConversation = it },
            startTimelineObserver = {},
            clientVersionProvider = clientVersionProvider,
        )

        coordinator.send("one").join()

        assertEquals(false, coordinator.cancel())
        advanceUntilIdle()
        assertTrue(timelineRepository.failedLocals.isEmpty())

        activeConversation = "conv-a"
        coordinator.handleEvent(WsTimelineEvent.TurnDone(turnId = "turn-a", runId = "run-a", status = "completed"))
        advanceUntilIdle()

        verify(exactly = 2) {
            wsChatBridge.send(
                agentId = "agent-1",
                conversationId = "conv-a",
                text = "one",
                otid = any(),
                attachments = emptyList(),
            )
        }
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
            timelineRepository = FakeTimelineExternalTransportWriter(),
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
            timelineRepository = FakeTimelineExternalTransportWriter(),
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
    fun `message deltas before conversation id are buffered and replayed through timeline`() = runTest {
        val timelineRepository = FakeTimelineExternalTransportWriter()
        val uiState = MutableStateFlow(ChatUiState(agentName = "Agent"))
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = mockBridge(sendAccepted = true),
            timelineRepository = timelineRepository,
            conversationRepository = stubConversationRepository(),
            uiState = uiState,
            clearComposerAfterSend = {},
            activeConversationId = { null },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersionProvider = clientVersionProvider,
        )
        val reasoning = ReasoningMessage(id = "reason-1", reasoning = "thinking")
        val assistant = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("answer"))

        coordinator.handleEvent(WsTimelineEvent.MessageDelta(reasoning))
        coordinator.handleEvent(WsTimelineEvent.MessageDelta(assistant))
        advanceUntilIdle()

        assertTrue(
            "Pre-conversation assistant/reasoning deltas must not be rendered directly in ChatUiState.messages",
            uiState.value.messages.isEmpty(),
        )
        assertTrue(
            "Pre-conversation deltas must wait for the gateway conversation id before timeline ingestion",
            timelineRepository.ingestedMessages.isEmpty(),
        )

        coordinator.handleEvent(
            WsTimelineEvent.TurnStarted(
                turnId = "turn-1",
                agentId = "agent-1",
                conversationId = "conv-created",
                runId = "run-1",
            )
        )
        advanceUntilIdle()

        assertEquals(
            listOf(
                FakeTimelineExternalTransportWriter.IngestedMessage("conv-created", reasoning),
                FakeTimelineExternalTransportWriter.IngestedMessage("conv-created", assistant),
            ),
            timelineRepository.ingestedMessages,
        )
        assertTrue(uiState.value.messages.isEmpty())
        assertEquals(ConversationState.Ready("conv-created"), uiState.value.conversationState)
    }

    @Test
    fun `websocket lifecycle and tool events are recorded into shared runtime outbox`() = runTest {
        val recorded = mutableListOf<RuntimeEventDraft>()
        val coordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = settingsRepository(),
            wsChatBridge = mockBridge(sendAccepted = true),
            timelineRepository = FakeTimelineExternalTransportWriter(),
            conversationRepository = stubConversationRepository(),
            uiState = MutableStateFlow(ChatUiState(agentName = "Agent")),
            clearComposerAfterSend = {},
            activeConversationId = { null },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersionProvider = clientVersionProvider,
            backendDescriptor = { backendDescriptor() },
            runtimeEventSink = { drafts -> recorded += drafts },
        )

        coordinator.handleEvent(
            WsTimelineEvent.TurnStarted(
                turnId = "turn-1",
                agentId = "agent-1",
                conversationId = "conv-1",
                runId = "run-1",
            )
        )
        coordinator.handleEvent(
            WsTimelineEvent.MessageDelta(
                ToolCallMessage(
                    id = "msg-tool",
                    toolCall = ToolCall(
                        toolCallId = "call-1",
                        name = "bash",
                        arguments = """{"command":"pwd"}""",
                    ),
                    runId = "run-1",
                )
            )
        )
        advanceUntilIdle()

        assertEquals(2, recorded.size)
        assertTrue(recorded.all { it.backendId == BackendId("remote-letta:shim") })
        assertTrue(recorded.all { it.runtimeId == RuntimeId("remote-letta:shim") })
        assertTrue(recorded.all { it.agentId == AgentId("agent-1") })
        assertTrue(recorded.all { it.conversationId == ConversationId("conv-1") })

        val lifecycle = recorded[0].payload as RuntimeEventPayload.RunLifecycleChanged
        assertEquals(RuntimeRunStatus.Started, lifecycle.status)

        val toolCall = recorded[1].payload as RuntimeEventPayload.ToolCallObserved
        assertEquals("call-1", toolCall.toolCallId.value)
        assertEquals("bash", toolCall.toolName.value)
        assertEquals("""{"command":"pwd"}""", toolCall.argumentsJson)
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
            timelineRepository = FakeTimelineExternalTransportWriter(),
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
            timelineRepository = FakeTimelineExternalTransportWriter(),
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
        val timelineRepository = FakeTimelineExternalTransportWriter()
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

        assertTrue(timelineRepository.reconciledSends.isEmpty())
    }

    @Test
    fun `lossy turn done forces a reconcile against external default conversation id`() = runTest {
        val wsChatBridge = mockBridge(sendAccepted = true)
        val timelineRepository = FakeTimelineExternalTransportWriter()
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

        val local = timelineRepository.externalLocals.single()
        assertEquals(
            FakeTimelineExternalTransportWriter.ReconciledSend(
                conversationId = "conv-default-agent-1",
                agentId = "agent-1",
                externalConversationId = "conv-default-agent-1",
                otid = local.otid,
            ),
            timelineRepository.reconciledSends.single(),
        )
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
            timelineRepository = FakeTimelineExternalTransportWriter(),
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

    private fun backendDescriptor(): BackendDescriptor = BackendDescriptor(
        backendId = BackendId("remote-letta:shim"),
        runtimeId = RuntimeId("remote-letta:shim"),
        kind = BackendKind.RemoteLetta,
        label = "localhost",
        capabilities = BackendCapabilities(
            supportsStreaming = true,
            supportsMemFs = true,
            supportsTools = true,
            supportsApprovals = true,
            supportsAgentFileImport = true,
            supportsAgentFileExport = true,
        ),
    )

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
        cancelResult: Boolean = true,
    ): WsChatBridge = mockBridge(
        sendResults = listOf(sendAccepted),
        eventFlow = eventFlow,
        cancelResult = cancelResult,
    )

    private fun mockBridge(
        sendResults: List<Boolean>,
        eventFlow: kotlinx.coroutines.flow.Flow<WsTimelineEvent> = emptyFlow(),
        cancelResult: Boolean = true,
    ): WsChatBridge = mockk(relaxed = true) {
        every { state } returns MutableStateFlow(
            ChannelTransport.State.Connected(
                serverId = "server-1",
                sessionId = "sess-1",
                deviceId = "android-letta-mobile",
            )
        )
        every { events } returns eventFlow
        every { isConnected() } returns true
        coEvery { awaitConnected() } returns WsConnectionState.Connected(
            a2uiEnabled = false,
            catalog = null,
        )
        every { send(any(), any(), any(), any(), any(), any()) } returnsMany sendResults
        every { cancel(any()) } returns cancelResult
    }
}
