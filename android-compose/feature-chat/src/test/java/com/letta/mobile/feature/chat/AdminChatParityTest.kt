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
import com.letta.mobile.testutil.TestData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
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

        harness.coordinator.resolveConversationAndLoad(useClientModeForResolve = false)
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

    private class Harness(scope: CoroutineScope) {
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

        val coordinator = ChatConversationCoordinator(
            scope = scope,
            agentId = "agent-1",
            initialMessage = null,
            explicitConversationId = { routeConversationId },
            pinnedExplicitConversationId = null,
            setRouteConversationId = { routeConversationId = it },
            isFreshRoute = false,
            chatSessionResolver = chatSessionResolver,
            agentRepository = agentRepository,
            currentConversationTracker = currentConversationTracker,
            uiState = uiState,
            updateSessionState = ::updateSessionState,
            pendingClientModeBootstrapMessages = { pendingBootstrapMessages },
            setPendingClientModeBootstrapUserMessage = { pendingBootstrapMessages = persistentListOf(it) },
            clearPendingClientModeBootstrapUserMessage = { pendingBootstrapMessages = persistentListOf() },
            currentClientModeConversationId = { null },
            setClientModeConversationId = {},
            startTimelineObserver = {},
            stopTimelineObserver = {},
            reconcileRecentMessages = { _, _ -> },
            sendMessageViaClientMode = {},
            sendMessageViaTimeline = {},
            markFollowingDuplicateInitialMessageInFlight = {},
        )

        init {
            every { agentRepository.getCachedAgent(AgentId("agent-1")) } returns null
            every { agentRepository.getAgent(AgentId("agent-1")) } returns flowOf(TestData.agent(id = "agent-1", name = "Ada"))
        }
    }
}
