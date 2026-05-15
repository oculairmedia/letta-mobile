package com.letta.mobile.ui.screens.chat

import com.letta.mobile.bot.chat.ClientModeChatSender
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.channel.NotificationDeliveryCoordinator
import com.letta.mobile.data.channel.NotificationCandidatePhase
import com.letta.mobile.data.channel.NotificationDeliveryCandidate
import com.letta.mobile.data.channel.NotificationDeliveryDecision
import com.letta.mobile.data.channel.NotificationDeferralReason
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.ClientModeStreamChunk
import com.letta.mobile.data.timeline.TimelineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClientModeSendCoordinatorTest {

    @Test
    fun `fresh send shows optimistic user echo before gateway creates conversation`() = runTest {
        val harness = Harness(this)
        val chunks = Channel<BotStreamChunk>(capacity = Channel.UNLIMITED)
        every { harness.sender.streamMessage("agent-1", "hello", null) } returns chunks.consumeAsFlow()

        harness.coordinator.send(text = "hello", explicitConversationId = null)
        runCurrent()

        assertEquals(ConversationState.NoConversation, harness.uiState.value.conversationState)
        assertEquals(listOf("hello"), harness.uiState.value.messages.map { it.content })
        assertEquals("user", harness.uiState.value.messages.single().role)
        assertTrue(harness.uiState.value.isStreaming)
        assertTrue(harness.uiState.value.isAgentTyping)
        assertEquals(1, harness.stopObserverCount)
        assertTrue(harness.startedObservers.isEmpty())

        chunks.close()
        harness.coordinator.cancelActiveStream("test cleanup")
        advanceUntilIdle()
    }

    @Test
    fun `fresh send buffers assistant chunks and replays them after conversation id arrives`() = runTest {
        val harness = Harness(this)
        every { harness.sender.streamMessage("agent-1", "hello", null) } returns flow {
            emit(BotStreamChunk(text = "Hello "))
            emit(BotStreamChunk(text = "world", conversationId = "conv-new"))
            emit(BotStreamChunk(conversationId = "conv-new", done = true))
        }

        harness.coordinator.send(text = "hello", explicitConversationId = null)
        advanceUntilIdle()

        assertEquals("conv-new", harness.clientConversationId)
        assertEquals("conv-new", harness.activeConversationId)
        assertEquals("conv-new", harness.bootstrapReadyConversationId)
        assertEquals(listOf("conv-new"), harness.startedObservers)
        assertTrue(harness.pendingBootstrapMessages.isEmpty())
        assertEquals(listOf("conv-new" to "hello"), harness.appendedClientModeLocals)
        assertEquals(listOf("Hello ", "world", null), harness.upsertedChunks.map { it.text })
        assertFalse(harness.uiState.value.isStreaming)
        assertFalse(harness.uiState.value.isAgentTyping)
    }

    @Test
    fun `stream exception after conversation id reconciles recoverable conversation`() = runTest {
        val harness = Harness(this)
        every { harness.sender.streamMessage("agent-1", "hello", null) } returns flow {
            emit(BotStreamChunk(text = "Partial", conversationId = "conv-recover"))
            throw IOException("socket closed")
        }

        harness.coordinator.send(text = "hello", explicitConversationId = null)
        advanceUntilIdle()

        assertEquals("conv-recover", harness.clientConversationId)
        assertEquals("conv-recover", harness.activeConversationId)
        assertEquals("conv-recover", harness.startedObservers.last())
        assertTrue(harness.startedObservers.isNotEmpty())
        assertEquals(listOf("conv-recover" to "client_mode_stream_exception"), harness.reconciliations)
        assertFalse(harness.uiState.value.isStreaming)
        assertFalse(harness.uiState.value.isAgentTyping)
        assertNull(harness.uiState.value.error)
    }

    @Test
    fun `stream exception before conversation id preserves bootstrap message`() = runTest {
        val harness = Harness(this)
        every { harness.sender.streamMessage("agent-1", "hello", null) } returns flow {
            throw IOException("offline")
        }

        harness.coordinator.send(text = "hello", explicitConversationId = null)
        advanceUntilIdle()

        assertNull(harness.clientConversationId)
        assertTrue(harness.startedObservers.isEmpty())
        assertEquals(listOf("hello"), harness.uiState.value.messages.map { it.content })
        assertTrue(harness.uiState.value.error.orEmpty().contains("failed before a conversation was created"))
        assertFalse(harness.uiState.value.isStreaming)
        assertFalse(harness.uiState.value.isAgentTyping)
    }

    @Test
    fun `conversation substitution emits banner and updates route conversation`() = runTest {
        val harness = Harness(this, initialClientConversationId = "conv-old")
        every { harness.sender.streamMessage("agent-1", "hello", "conv-old") } returns flow {
            emit(BotStreamChunk(text = "New answer", conversationId = "conv-new"))
            emit(BotStreamChunk(conversationId = "conv-new", done = true))
        }

        harness.coordinator.send(text = "hello", explicitConversationId = null)
        advanceUntilIdle()

        assertEquals(ClientModeConversationSwap("conv-old", "conv-new"), harness.uiState.value.clientModeConversationSwap)
        assertEquals("conv-new", harness.routeConversationId)
        assertEquals("conv-new", harness.clientConversationId)
        assertEquals("conv-new", harness.activeConversationId)
        assertEquals(listOf("conv-old", "conv-new"), harness.startedObservers)
        assertEquals(listOf("conv-old" to "hello", "conv-new" to "hello"), harness.appendedClientModeLocals)
    }

    @Test
    fun `terminal frame without conversation id still publishes final notification`() = runTest {
        val harness = Harness(this)
        every { harness.sender.streamMessage("agent-1", "hello", null) } returns flow {
            emit(BotStreamChunk(text = "Final answer", conversationId = "conv-1"))
            emit(BotStreamChunk(done = true))
        }

        harness.coordinator.send(text = "hello", explicitConversationId = null)
        advanceUntilIdle()

        val final = harness.notificationCandidates.single { it.phase == NotificationCandidatePhase.Final }
        assertEquals("conv-1", final.conversationId)
        assertEquals("agent-1", final.agentId)
        assertEquals("Ada", final.agentName)
        assertEquals("Final answer", final.previewText)
        assertTrue(final.isFinal)
    }

    private class Harness(
        scope: CoroutineScope,
        initialClientConversationId: String? = null,
    ) {
        val sender: ClientModeChatSender = mockk()
        val timelineRepository: TimelineRepository = mockk()
        val notificationDeliveryCoordinator: NotificationDeliveryCoordinator = mockk()
        val currentConversationTracker = CurrentConversationTracker()
        val uiState = MutableStateFlow(ChatUiState(agentName = "Ada"))
        val appendedClientModeLocals = mutableListOf<Pair<String, String>>()
        val upsertedChunks = mutableListOf<ClientModeStreamChunk>()
        val reconciliations = mutableListOf<Pair<String, String>>()
        val startedObservers = mutableListOf<String>()
        val notificationCandidates = mutableListOf<NotificationDeliveryCandidate>()
        var stopObserverCount = 0
        var clientConversationId: String? = initialClientConversationId
        var routeConversationId: String? = null
        var activeConversationId: String? = null
        var bootstrapReadyConversationId: String? = null
        var pendingBootstrapMessages: ImmutableList<UiMessage> = persistentListOf()
        var refreshContextWindowCount = 0
        var composerClearedCount = 0

        val coordinator = ClientModeSendCoordinator(
            scope = scope,
            agentId = "agent-1",
            clientModeChatSender = sender,
            timelineRepository = timelineRepository,
            notificationDeliveryCoordinator = notificationDeliveryCoordinator,
            currentConversationTracker = currentConversationTracker,
            uiState = uiState,
            clearComposerAfterSend = { composerClearedCount++ },
            currentClientModeConversationId = { clientConversationId },
            setClientModeConversationId = { clientConversationId = it },
            setRouteConversationId = { routeConversationId = it },
            setActiveConversationId = { activeConversationId = it },
            markClientModeBootstrapReady = { bootstrapReadyConversationId = it },
            pendingBootstrapMessages = { pendingBootstrapMessages },
            setBootstrapUserMessage = { pendingBootstrapMessages = listOf(it).toImmutableList() },
            clearBootstrapUserMessage = { pendingBootstrapMessages = persistentListOf() },
            showConversationSwap = { swap -> uiState.update { it.copy(clientModeConversationSwap = swap) } },
            startTimelineObserver = { startedObservers += it },
            stopTimelineObserver = { stopObserverCount++ },
            refreshContextWindow = { refreshContextWindowCount++ },
            collapseCompletedRunsIfStreamingFinished = { _, next -> next },
        )

        init {
            coEvery { timelineRepository.appendClientModeLocal(any(), any(), any()) } answers {
                appendedClientModeLocals += firstArg<String>() to secondArg<String>()
                "local-${appendedClientModeLocals.size}"
            }
            coEvery { timelineRepository.upsertClientModeStreamChunk(any(), any(), any()) } answers {
                upsertedChunks += secondArg<ClientModeStreamChunk>()
                "chunk-${upsertedChunks.size}"
            }
            coEvery { timelineRepository.reconcileRecentMessages(any(), any()) } answers {
                reconciliations += firstArg<String>() to secondArg<String>()
                Unit
            }
            coEvery { timelineRepository.postHandlerCollapse(any()) } returns Unit
            every { notificationDeliveryCoordinator.submit(any()) } answers {
                notificationCandidates += firstArg<NotificationDeliveryCandidate>()
                NotificationDeliveryDecision.Deferred(NotificationDeferralReason.AwaitingFinalPreview)
            }
        }
    }
}
