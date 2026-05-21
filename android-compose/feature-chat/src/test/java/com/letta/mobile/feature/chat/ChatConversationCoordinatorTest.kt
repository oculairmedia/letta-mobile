package com.letta.mobile.feature.chat

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.testutil.FakeTimelineExternalTransportWriter
import com.letta.mobile.testutil.TestData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
        assertEquals("conv-recent", harness.routeConversationId)
        assertEquals("conv-recent", harness.coordinator.activeConversationId)
        assertEquals(listOf("conv-recent"), harness.startedObservers)
        assertEquals("conv-recent", harness.currentConversationTracker.current)
    }

    @Test
    fun `client mode open without route arg exposes resolved most recent conversation as active`() = runTest {
        val harness = Harness(scope = this, isFreshRoute = false)
        coEvery { harness.chatSessionResolver.resolveMostRecentConversation("agent-1", any()) } returns "conv-existing"

        harness.coordinator.resolveConversationAndLoad(useClientModeForResolve = true)
        advanceUntilIdle()

        assertEquals(ConversationState.Ready("conv-existing"), harness.uiState.value.conversationState)
        assertEquals("conv-existing", harness.routeConversationId)
        assertEquals("conv-existing", harness.coordinator.activeConversationId)
    }

    /**
     * letta-mobile-go8el follow-up. PR #177 wired setRouteConversationId on the
     * resolveMostRecent fallback but missed the cached-CM branch: when
     * `currentClientModeConversationId()` already returns a value from a prior
     * session, resolve was only updating `clientModeBootstrapState` and never
     * mirroring the id into the unified `conversationId` SavedStateHandle key.
     * That left `coordinator.activeConversationId` (derived from
     * `explicitConversationId()`) null, so `WsChatSendCoordinator.send`'s
     * `activeConversationId() ?: createConversation(...)` minted a fresh conv on
     * first send after every reopen. Regression test prevents that branch from
     * silently regressing again.
     */
    @Test
    fun `client mode open with cached clientModeConversationId mirrors it into the unified route key`() = runTest {
        val harness = Harness(scope = this, isFreshRoute = false)
        harness.clientModeConversationId = "conv-cached"
        // No chatSessionResolver expectation — resolve must take the cached
        // branch before falling through to resolveMostRecent.

        harness.coordinator.resolveConversationAndLoad(useClientModeForResolve = true)
        advanceUntilIdle()

        assertEquals(ConversationState.Ready("conv-cached"), harness.uiState.value.conversationState)
        assertEquals("conv-cached", harness.routeConversationId)
        assertEquals("conv-cached", harness.coordinator.activeConversationId)
        coVerify(exactly = 0) { harness.chatSessionResolver.resolveMostRecentConversation(any(), any()) }
    }

    @Test
    fun `first ws send after client mode open uses resolved conversation without creating one`() = runTest {
        val harness = Harness(scope = this, isFreshRoute = false)
        coEvery { harness.chatSessionResolver.resolveMostRecentConversation("agent-1", any()) } returns "conv-existing"
        harness.coordinator.resolveConversationAndLoad(useClientModeForResolve = true)
        advanceUntilIdle()
        val bridge = mockBridge(sendAccepted = true)
        val conversationRepository = stubConversationRepository()
        val timelineRepository = FakeTimelineExternalTransportWriter()
        val wsSendCoordinator = WsChatSendCoordinator(
            scope = backgroundScope,
            agentId = "agent-1",
            activeConfig = {
                LettaConfig(
                    id = "shim",
                    mode = LettaConfig.Mode.SELF_HOSTED,
                    serverUrl = "http://localhost:8291",
                    accessToken = "token",
                )
            },
            wsChatBridge = bridge,
            timelineRepository = timelineRepository,
            conversationRepository = conversationRepository,
            uiState = harness.uiState,
            clearComposerAfterSend = {},
            activeConversationId = { harness.coordinator.activeConversationId },
            setActiveConversationId = harness.coordinator::setActiveConversationId,
            startTimelineObserver = { harness.startedObservers += it },
            clientVersionProvider = object : ChatClientVersionProvider {
                override val clientVersion: String = "letta-mobile/test (android)"
            },
        )

        wsSendCoordinator.send("ping").join()

        coVerify(exactly = 0) { conversationRepository.createConversation(any(), any()) }
        verify(exactly = 1) {
            bridge.send(
                agentId = "agent-1",
                conversationId = "conv-existing",
                text = "ping",
                otid = any(),
                attachments = emptyList(),
            )
        }
        assertEquals("conv-existing", timelineRepository.externalLocals.single().conversationId)
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
    fun `loadMessages reconciles recent messages on conversation open (letta-mobile-ork1)`() = runTest {
        val harness = Harness(scope = this, explicitConversationId = "conv-explicit")

        harness.coordinator.resolveConversationAndLoad(useClientModeForResolve = false)
        advanceUntilIdle()

        assertEquals(listOf("conv-explicit" to "open"), harness.reconcileCalls)
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
        val reconcileCalls = mutableListOf<Pair<String, String>>()
        val sentClientModeMessages = mutableListOf<String>()
        val sentTimelineMessages = mutableListOf<String>()
        var stoppedObserverCount = 0
        var clientModeConversationId: String? = null
        var routeConversationId: String? = explicitConversationId
        var pendingBootstrapMessages: ImmutableList<UiMessage> = persistentListOf()
        var followingDuplicateInitialMessageInFlight = false

        val coordinator = ChatConversationCoordinator(
            scope = scope,
            agentId = "agent-1",
            initialMessage = initialMessage,
            explicitConversationId = { routeConversationId },
            setRouteConversationId = { routeConversationId = it?.takeIf { value -> value.isNotBlank() } },
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
            reconcileRecentMessages = { convId, reason -> reconcileCalls += convId to reason },
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

    private fun stubConversationRepository(): ConversationRepository = mockk(relaxed = true) {
        coEvery { createConversation(any(), any()) } returns Conversation(
            id = "conv-created",
            agentId = "agent-1",
            createdAt = "1970-01-01T00:00:00Z",
            updatedAt = "1970-01-01T00:00:00Z",
            lastMessageAt = "1970-01-01T00:00:00Z",
        )
    }

    private fun mockBridge(sendAccepted: Boolean): WsChatBridge = mockk(relaxed = true) {
        every { state } returns MutableStateFlow(
            ChannelTransport.State.Connected(
                serverId = "server-1",
                sessionId = "sess-1",
                deviceId = "android-letta-mobile",
            )
        )
        every { events } returns emptyFlow()
        every { send(any(), any(), any(), any(), any()) } returns sendAccepted
    }
}
