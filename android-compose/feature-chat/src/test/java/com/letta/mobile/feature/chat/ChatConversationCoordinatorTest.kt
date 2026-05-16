package com.letta.mobile.feature.chat

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.testutil.TestData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatConversationCoordinatorTest {

    @Before
    fun resetInitialMessageGuard() {
        InitialRouteMessageDeliveryGuard.resetForTests()
    }

    @After
    fun clearInitialMessageGuard() {
        InitialRouteMessageDeliveryGuard.resetForTests()
    }

    @Test
    fun `fresh client mode route does not hydrate most recent conversation`() = runTest {
        val harness = Harness(scope = this, isFreshRoute = true)
        coEvery { harness.chatSessionResolver.resolveMostRecentConversation(any(), any()) } returns "conv-old"

        harness.coordinator.resolveConversationAndLoad(useClientModeForResolve = true)
        advanceUntilIdle()

        coVerify(exactly = 0) { harness.chatSessionResolver.resolveMostRecentConversation(any(), any()) }
        assertEquals(ConversationState.NoConversation, harness.uiState.value.conversationState)
        assertTrue(harness.startedObservers.isEmpty())
        assertEquals(1, harness.stoppedObserverCount)
    }

    @Test
    fun `non fresh client mode route falls back to most recent conversation`() = runTest {
        val harness = Harness(scope = this, isFreshRoute = false)
        coEvery { harness.chatSessionResolver.resolveMostRecentConversation("agent-1", any()) } returns "conv-recent"

        harness.coordinator.resolveConversationAndLoad(useClientModeForResolve = true)
        advanceUntilIdle()

        assertEquals(ConversationState.Ready("conv-recent"), harness.uiState.value.conversationState)
        assertEquals("conv-recent", harness.clientModeConversationId)
        assertEquals(listOf("conv-recent"), harness.startedObservers)
        assertEquals("conv-recent", harness.currentConversationTracker.current)
    }

    @Test
    fun `explicit timeline route hydrates requested conversation and starts observer`() = runTest {
        val harness = Harness(scope = this, explicitConversationId = "conv-explicit")

        harness.coordinator.resolveConversationAndLoad(useClientModeForResolve = false)
        advanceUntilIdle()

        assertEquals(ConversationState.Ready("conv-explicit"), harness.uiState.value.conversationState)
        assertEquals("conv-explicit", harness.coordinator.activeConversationId)
        assertEquals(listOf("conv-explicit"), harness.startedObservers)
        assertEquals("Ada", harness.uiState.value.agentName)
    }

    @Test
    fun `duplicate fresh client initial message is mirrored without sending again`() = runTest {
        val first = Harness(scope = this, isFreshRoute = true, initialMessage = "shared prompt")
        every { first.agentRepository.getCachedAgent("agent-1") } returns TestData.agent(id = "agent-1", name = "Ada")
        first.coordinator.resolveConversationAndLoad(useClientModeForResolve = true)
        advanceUntilIdle()
        assertEquals(listOf("shared prompt"), first.sentClientModeMessages)

        val duplicate = Harness(scope = this, isFreshRoute = true, initialMessage = "shared prompt")
        every { duplicate.agentRepository.getCachedAgent("agent-1") } returns TestData.agent(id = "agent-1", name = "Ada")
        duplicate.coordinator.resolveConversationAndLoad(useClientModeForResolve = true)
        advanceUntilIdle()

        assertTrue(duplicate.sentClientModeMessages.isEmpty())
        assertEquals(listOf("shared prompt"), duplicate.uiState.value.messages.map { it.content })
        assertTrue(duplicate.followingDuplicateInitialMessageInFlight)
    }

    private class Harness(
        scope: CoroutineScope,
        explicitConversationId: String? = null,
        isFreshRoute: Boolean = false,
        initialMessage: String? = null,
    ) {
        val chatSessionResolver: ChatSessionResolver = mockk(relaxed = true)
        val agentRepository: AgentRepository = mockk(relaxed = true)
        val currentConversationTracker = CurrentConversationTracker()
        val uiState = MutableStateFlow(ChatUiState())
        val startedObservers = mutableListOf<String>()
        val sentClientModeMessages = mutableListOf<String>()
        val sentTimelineMessages = mutableListOf<String>()
        var stoppedObserverCount = 0
        var clientModeConversationId: String? = null
        var pendingBootstrapMessages: ImmutableList<UiMessage> = persistentListOf()
        var followingDuplicateInitialMessageInFlight = false

        val coordinator = ChatConversationCoordinator(
            scope = scope,
            agentId = "agent-1",
            initialMessage = initialMessage,
            explicitConversationId = explicitConversationId,
            isFreshRoute = isFreshRoute,
            chatSessionResolver = chatSessionResolver,
            agentRepository = agentRepository,
            currentConversationTracker = currentConversationTracker,
            uiState = uiState,
            pendingClientModeBootstrapMessages = { pendingBootstrapMessages },
            setPendingClientModeBootstrapUserMessage = { pendingBootstrapMessages = listOf(it).toImmutableList() },
            clearPendingClientModeBootstrapUserMessage = { pendingBootstrapMessages = persistentListOf() },
            currentClientModeConversationId = { clientModeConversationId },
            setClientModeConversationId = { clientModeConversationId = it },
            startTimelineObserver = { startedObservers += it },
            stopTimelineObserver = { stoppedObserverCount++ },
            sendMessageViaClientMode = { sentClientModeMessages += it },
            sendMessageViaTimeline = { sentTimelineMessages += it },
            markFollowingDuplicateInitialMessageInFlight = { followingDuplicateInitialMessageInFlight = true },
        )

        init {
            every { agentRepository.getCachedAgent("agent-1") } returns null
            every { agentRepository.getAgent("agent-1") } returns flowOf(TestData.agent(id = "agent-1", name = "Ada"))
            coEvery { chatSessionResolver.resolveMostRecentConversation(any(), any()) } returns null
        }
    }
}
