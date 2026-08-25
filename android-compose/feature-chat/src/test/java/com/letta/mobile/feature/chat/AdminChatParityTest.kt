package com.letta.mobile.feature.chat
import com.letta.mobile.ui.chat.render.*

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.chat.runtime.ChatConnectionState
import com.letta.mobile.data.chat.runtime.ChatSessionReducer
import com.letta.mobile.data.chat.runtime.ChatSessionState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.feature.chat.coordination.ChatConversationCoordinator
import com.letta.mobile.feature.chat.coordination.ChatSessionResolver
import com.letta.mobile.feature.chat.coordination.ConversationAccessMode
import com.letta.mobile.feature.chat.coordination.RecentMessagesReconcileLauncher
import com.letta.mobile.testutil.TestData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdminChatParityTest {

    private fun ChatConnectionState.toConversationState(
        selectedConversationId: String?,
        errorMessage: String?,
    ): ConversationState {
        return when (this) {
            ChatConnectionState.Loading -> ConversationState.Loading
            ChatConnectionState.ConfigNeeded -> ConversationState.Error(errorMessage ?: "Backend configuration required")
            ChatConnectionState.Offline -> ConversationState.Error(errorMessage ?: "Backend offline")
            ChatConnectionState.NoConversations -> ConversationState.NoConversation
            ChatConnectionState.Demo,
            ChatConnectionState.Live,
            ChatConnectionState.Sending,
            ChatConnectionState.StreamDisconnected,
            ChatConnectionState.SendFailed -> {
                if (selectedConversationId != null) {
                    ConversationState.Ready(selectedConversationId)
                } else {
                    ConversationState.NoConversation
                }
            }
        }
    }

    @Test
    fun `resolve conversation failure triggers offline state and sets error message`() = runTest {
        val harness = Harness(scope = this)
        coEvery { harness.chatSessionResolver.resolveMostRecentConversation("agent-1", any()) } throws RuntimeException("Network error")

        harness.coordinator.resolveConversationAndLoad(ConversationAccessMode.Timeline)
        advanceUntilIdle()

        // Verify KMP state transitioned to Offline
        assertEquals(ChatConnectionState.Offline, harness.sessionState.value.connectionState)
        assertEquals("Network error", harness.sessionState.value.errorMessage)

        // Verify mapped ConversationState is Error and error message matches
        val expectedConvState = harness.sessionState.value.connectionState.toConversationState(
            harness.sessionState.value.selectedConversationId,
            harness.sessionState.value.errorMessage
        )
        assertEquals(expectedConvState, harness.uiState.value.conversationState)
        assertTrue(harness.uiState.value.conversationState is ConversationState.Error)
        assertEquals("Network error", (harness.uiState.value.conversationState as ConversationState.Error).message)
    }

    @Test
    fun `send failed transitions connection state to SendFailed and restores composer text`() = runTest {
        val current = ChatSessionState(
            conversations = listOf(
                com.letta.mobile.data.chat.runtime.ChatConversationSummary(
                    id = "conv-1",
                    title = "Ada",
                    agentName = "Ada",
                    updatedAtLabel = "",
                    lastMessagePreview = "",
                )
            ),
            selectedConversationId = "conv-1",
            connectionState = ChatConnectionState.Live
        )

        // Simulate failing to send a message "Hello World"
        val failedState = ChatSessionReducer.sendFailed(
            state = current,
            text = "Hello World",
            attachments = emptyList(),
            errorMessage = "Timeout"
        )

        assertEquals(ChatConnectionState.SendFailed, failedState.connectionState)
        assertEquals("Hello World", failedState.composer.text)
        assertEquals("Timeout", failedState.errorMessage)
    }

    @Test
    fun `stale selection of non existent conversation is no op`() = runTest {
        val current = ChatSessionState(
            conversations = listOf(
                com.letta.mobile.data.chat.runtime.ChatConversationSummary(
                    id = "conv-1",
                    title = "Ada",
                    agentName = "Ada",
                    updatedAtLabel = "",
                    lastMessagePreview = "",
                )
            ),
            selectedConversationId = "conv-1",
            connectionState = ChatConnectionState.Live
        )

        // Select an invalid conversationId
        val next = ChatSessionReducer.selectConversation(current, "invalid-conv", remoteBacked = true)

        // Verify state is completely unchanged
        assertEquals(current, next)
    }

    @Test
    fun `cache miss resolves agent name into chat header state`() = runTest {
        val harness = Harness(this)
        harness.routeConversationId = "conversation-1"

        harness.coordinator.resolveConversationAndLoad(ConversationAccessMode.Timeline)
        advanceUntilIdle()

        assertEquals("Ada", harness.uiState.value.agentName)
    }

    @Test
    fun `cache miss resolver is a no-op when the cache already has the agent`() = runTest {
        val harness = Harness(this)
        harness.routeConversationId = "conversation-1"
        every { harness.agentRepository.getCachedAgent(AgentId("agent-1")) } returns
            TestData.agent(id = "agent-1", name = "Cached Ada")

        harness.coordinator.resolveConversationAndLoad(ConversationAccessMode.Timeline)
        advanceUntilIdle()

        // letta-mobile-xl1o2 AC: when the cache already has the agent, the
        // resolver MUST NOT trigger a per-id fetch. The only getAgent call
        // the harness ever sees is the one from loadMessagesInternal, not
        // the resolver's getAgent.
        val resolverCalls = harness.coordinator.rosterNameResolverForTest.resolveCallsForTest()
        assertEquals(0, resolverCalls)
    }

    @Test
    fun `already hydrated conversation skips the Loading flash on re-entry`() = runTest {
        val harness = Harness(this)
        harness.routeConversationId = "conversation-1"
        harness.sessionState.value = ChatSessionState(
            conversations = listOf(
                com.letta.mobile.data.chat.runtime.ChatConversationSummary(
                    id = "conversation-1",
                    title = "Ada",
                    agentName = "Ada",
                    updatedAtLabel = "",
                    lastMessagePreview = "",
                )
            ),
            selectedConversationId = "conversation-1",
            connectionState = ChatConnectionState.Live,
        )
        harness.uiState.value = harness.uiState.value.copy(
            messages = persistentListOf(
                UiMessage(
                    id = "m1",
                    role = "assistant",
                    content = "hello",
                    timestamp = "2026-05-16T00:00:00Z",
                )
            )
        )

        val seen = mutableListOf<ChatConnectionState>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.sessionState.collect { seen += it.connectionState }
        }
        harness.coordinator.resolveConversationAndLoad(ConversationAccessMode.Timeline)
        advanceUntilIdle()
        collector.cancel()

        // letta-mobile-6bqi1: the eager Loading transition must never appear
        // when the conversation's messages are already on screen (re-entry /
        // rotation). beginConversationLoad and beginSelectedConversationHydrate
        // both set connectionState = Loading; the guard routes to
        // hydrateCompleted instead, which lands on Live.
        assertTrue("Loading flash observed: $seen", ChatConnectionState.Loading !in seen)
        assertEquals(ChatConnectionState.Live, harness.sessionState.value.connectionState)
    }

    @Test
    fun `messages from another conversation do not skip the Loading transition`() = runTest {
        val harness = Harness(this)
        harness.routeConversationId = "conversation-a"
        harness.uiState.value = harness.uiState.value.copy(
            messages = persistentListOf(
                UiMessage(
                    id = "m1",
                    role = "assistant",
                    content = "from conversation A",
                    timestamp = "2026-05-16T00:00:00Z",
                )
            ),
        )
        val coordinator = harness.coordinator
        harness.routeConversationId = "conversation-b"

        val seen = mutableListOf<ChatConnectionState>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.sessionState.collect { seen += it.connectionState }
        }
        coordinator.resolveConversationAndLoad(ConversationAccessMode.Timeline)
        advanceUntilIdle()
        collector.cancel()

        assertTrue("expected Loading for a different conversation, saw: $seen", ChatConnectionState.Loading in seen)
    }

    @Test
    fun `failed initial resolution remains initial on retry`() = runTest {
        val harness = Harness(this, pinnedConversationId = "pinned-conversation")
        harness.routeConversationId = "stale-before-first-attempt"
        coEvery { harness.agentRepository.getAgent(AgentId("agent-1")) } throws RuntimeException("Network error")

        harness.coordinator.resolveConversationAndLoad(ConversationAccessMode.Timeline)
        advanceUntilIdle()

        harness.routeConversationId = "stale-before-retry"
        coEvery { harness.agentRepository.getAgent(AgentId("agent-1")) } returns
            flowOf(TestData.agent(id = "agent-1", name = "Ada"))
        harness.coordinator.resolveConversationAndLoad(ConversationAccessMode.Timeline)
        advanceUntilIdle()

        assertEquals("pinned-conversation", harness.routeConversationId)
    }

    @Test
    fun `fresh conversation still shows the Loading flash`() = runTest {
        val harness = Harness(this)
        harness.routeConversationId = "conversation-1"

        val seen = mutableListOf<ChatConnectionState>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.sessionState.collect { seen += it.connectionState }
        }
        harness.coordinator.resolveConversationAndLoad(ConversationAccessMode.Timeline)
        advanceUntilIdle()
        collector.cancel()

        // letta-mobile-6bqi1: a genuine first load (empty VM render cache)
        // must still enter Loading so the skeleton shows.
        assertTrue("expected Loading transition, saw: $seen", ChatConnectionState.Loading in seen)
        assertEquals(ChatConnectionState.Live, harness.sessionState.value.connectionState)
    }

    private class Harness(
        scope: CoroutineScope,
        private val pinnedConversationId: String? = null,
    ) {
        val chatSessionResolver: ChatSessionResolver = mockk(relaxed = true)
        val agentRepository: IAgentRepository = mockk(relaxed = true)
        val currentConversationTracker = CurrentConversationTracker()
        val uiState = MutableStateFlow(ChatUiState())
        val sessionState = MutableStateFlow(ChatSessionState())

        fun updateSessionState(reducerUpdate: (ChatSessionState) -> ChatSessionState) {
            sessionState.value = reducerUpdate(sessionState.value)
            val next = sessionState.value
            uiState.value = uiState.value.copy(
                conversationState = next.connectionState.toConversationState(
                    next.selectedConversationId,
                    next.errorMessage,
                ),
                isLoadingMessages = next.isLoading,
                error = next.errorMessage
            )
        }

        var routeConversationId: String? = null
        var pendingBootstrapMessages = persistentListOf<UiMessage>()

        val coordinator by lazy {
            ChatConversationCoordinator(
                scope = scope,
                agentId = "agent-1",
                initialMessage = null,
                explicitConversationId = { routeConversationId },
                pinnedExplicitConversationId = pinnedConversationId,
                setRouteConversationId = { routeConversationId = it },
                isFreshRoute = false,
                chatSessionResolver = chatSessionResolver,
                agentRepository = agentRepository,
                currentConversationTracker = currentConversationTracker,
                uiState = uiState,
                updateSessionState = ::updateSessionState,
                pendingClientModeBootstrapMessages = { pendingBootstrapMessages },
                setPendingClientModeBootstrapUserMessage = { pendingBootstrapMessages = persistentListOf(it) },
                currentClientModeConversationId = { null },
                startTimelineObserver = {},
                stopTimelineObserver = {},
                recentMessagesReconcileLauncher = RecentMessagesReconcileLauncher(
                    scope = scope,
                    reconcile = { },
                ),
                sendMessageViaClientMode = {},
                sendMessageViaTimeline = {},
                markFollowingDuplicateInitialMessageInFlight = {},
            )
        }

        init {
            every { agentRepository.getCachedAgent(AgentId("agent-1")) } returns null
            coEvery { agentRepository.getAgent(AgentId("agent-1")) } returns flowOf(TestData.agent(id = "agent-1", name = "Ada"))
            every { agentRepository.agents } returns MutableStateFlow(emptyList())
        }
    }
}
