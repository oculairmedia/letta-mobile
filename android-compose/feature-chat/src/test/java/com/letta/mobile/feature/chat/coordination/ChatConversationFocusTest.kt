package com.letta.mobile.feature.chat.coordination

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.feature.chat.route.focusedCreation
import kotlinx.coroutines.async
import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.IdFormats
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.shell.CurrentFocus
import com.letta.mobile.data.shell.CurrentFocusChatMode
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.feature.chat.route.ChatRouteArgs
import com.letta.mobile.testutil.TestData
import com.letta.mobile.ui.chat.render.ChatUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.letta.mobile.data.model.Conversation

/** Exercises the actual owner/resolver/coordinator boundary, including delayed backend reads. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatConversationFocusTest {
    @Test
    fun staleResumeAfterAnotherAgentSelectionCannotHydrateOrSend() = runTest {
        val owner = ShellWorkspaceState(ShellDestination.Chat("agent-a")).apply { bindSource("source", 1L) }
        val agent = TestData.agent("agent-a", "A")
        val agents = mockk<IAgentRepository> {
            every { getCachedAgent(agent.id) } returns agent
            every { this@mockk.agents } returns MutableStateFlow(listOf(agent))
        }
        val release = CompletableDeferred<Unit>()
        val conversations = mockk<IConversationRepository> {
            coEvery { refreshConversationsIfStale(agent.id, any()) } coAnswers { release.await(); true }
            every { getCachedConversations(agent.id) } returns listOf(
                Conversation(id = ConversationId("a-late"), agentId = agent.id),
            )
        }
        val args = ChatRouteArgs(SavedStateHandle(mapOf("agentId" to agent.id.value))).apply { bindWorkspace(owner) }
        val observers = mutableListOf<String>()
        val sends = mutableListOf<String>()
        val ui = MutableStateFlow(ChatUiState())
        val coordinator = ChatConversationCoordinator(ChatConversationCoordinatorConfig(
            scope = this,
            route = ChatConversationRoute(
                agent.id.value, "must not send", { args.explicitConversationId },
                setConversationId = { id -> id?.let { args.settle(ConversationId(it)) } }, isFresh = false, focus = args,
            ),
            chatSessionResolver = ChatSessionResolver(agents, conversations),
            agentRepository = agents,
            currentConversationTracker = CurrentConversationTracker(),
            uiState = ui,
            updateSessionState = {},
            observer = TimelineObserverConfig({ observers += it }, {}),
            reconcileLauncher = RecentMessagesReconcileLauncher(this) {},
            send = ConversationSendConfig({ sends += it }, {}),
            hydration = HydrationRouteConfig({ ChatHydrationTrace.Identity(agent.id.value, it) }),
        ))
        coordinator.resolveConversationAndLoad()
        runCurrent()
        owner.navigate(ShellDestination.Chat("agent-b", "b-exact"))
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(AgentId("agent-b"), owner.currentFocus.agentId)
        assertEquals(ConversationId("b-exact"), owner.currentFocus.conversationId)
        assertTrue(observers.isEmpty())
        assertTrue(sends.isEmpty())
        assertNull(coordinator.conversationId())
    }

    @Test
    fun explicitDefaultAndNewUseTheOwnerEvenWithAStaleRouteHandle() = runTest {
        val owner = ShellWorkspaceState().apply {
            bindSource("source", 1L)
            navigate(ShellDestination.Chat("agent-a", IdFormats.DEFAULT_CONVERSATION_ID))
        }
        val handle = SavedStateHandle(mapOf("agentId" to "agent-a", "conversationId" to "stale"))
        val args = ChatRouteArgs(handle).apply { bindWorkspace(owner) }
        assertEquals(IdFormats.defaultConversationIdFor("agent-a"), args.explicitConversationId)
        handle["conversationId"] = "another-stale-handle"
        assertEquals(IdFormats.defaultConversationIdFor("agent-a"), args.explicitConversationId)

        owner.selectFocus(CurrentFocus("chat", AgentId("agent-a"), chatMode = CurrentFocusChatMode.New))
        args.bindWorkspace(owner)
        assertNull(args.explicitConversationId)
        val repositories = mockk<IConversationRepository>()
        val resolver = ChatSessionResolver(mockk(), repositories)
        assertNull(resolver.resolveFocus(owner, owner.requestToken(), kotlin.time.Duration.ZERO))
        coVerify(exactly = 0) { repositories.refreshConversationsIfStale(any<AgentId>(), any<Long>()) }
        assertNull(owner.currentFocus.conversationId)
    }
    @Test
    fun retainedResumeCommitsOnlyToTheRecreatedOwnerWithoutStartingAnotherLookup() = runTest {
        val old = ShellWorkspaceState(ShellDestination.Chat("agent-a")).apply { bindSource("source", 1L) }
        val agent = TestData.agent("agent-a", "A")
        val agents = mockk<IAgentRepository> {
            every { getCachedAgent(agent.id) } returns agent
            every { this@mockk.agents } returns MutableStateFlow(listOf(agent))
        }
        val release = CompletableDeferred<Unit>()
        val conversations = mockk<IConversationRepository> {
            coEvery { refreshConversationsIfStale(agent.id, any()) } coAnswers { release.await(); true }
            every { getCachedConversations(agent.id) } returns listOf(Conversation(id = ConversationId("resolved"), agentId = agent.id))
        }
        val args = ChatRouteArgs(SavedStateHandle(mapOf("agentId" to agent.id.value))).apply { bindWorkspace(old) }
        val observers = mutableListOf<String>()
        val coordinator = ChatConversationCoordinator(ChatConversationCoordinatorConfig(
            scope = this,
            route = ChatConversationRoute(
                agent.id.value, null, { args.explicitConversationId },
                setConversationId = { id -> id?.let { args.settle(ConversationId(it)) } }, isFresh = false, focus = args,
            ),
            chatSessionResolver = ChatSessionResolver(agents, conversations), agentRepository = agents,
            currentConversationTracker = CurrentConversationTracker(), uiState = MutableStateFlow(ChatUiState()),
            updateSessionState = {}, observer = TimelineObserverConfig({ observers += it }, {}),
            reconcileLauncher = RecentMessagesReconcileLauncher(this) {}, send = ConversationSendConfig({}, {}),
            hydration = HydrationRouteConfig({ ChatHydrationTrace.Identity(agent.id.value, it) }),
        ))
        coordinator.resolveConversationAndLoad()
        runCurrent()
        val recreated = ShellWorkspaceState().apply { bindSource("source", 1L); assertTrue(restore(old.snapshot())) }
        args.bindWorkspace(recreated)
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(ConversationId("resolved"), recreated.currentFocus.conversationId)
        assertNull(old.currentFocus.conversationId)
        assertEquals(listOf("resolved"), observers)
        coVerify(exactly = 1) { conversations.refreshConversationsIfStale(agent.id, any()) }
    }

    @Test
    fun sessionGenerationFencesCompletionsEvenBeforeTheWorkspaceRecomposes() {
        val owner = ShellWorkspaceState(ShellDestination.Chat("agent-a", "exact")).apply { bindSource("source", 1L) }
        var generation = 1L
        val args = ChatRouteArgs(SavedStateHandle(mapOf("agentId" to "agent-a"))).apply {
            liveGeneration = { generation }
            bindWorkspace(owner)
        }
        val request = args.requestToken()
        generation = 2L
        org.junit.Assert.assertFalse(args.accepts(request))
        org.junit.Assert.assertFalse(args.complete(request, ConversationId("exact")))
        assertEquals(ConversationId("exact"), owner.currentFocus.conversationId)
    }

    @Test
    fun delayedCreationCannotAcquireTheRequestAfterAToBToA() = runTest {
        val owner = ShellWorkspaceState().apply {
            bindSource("source", 1L)
            selectFocus(CurrentFocus("chat", AgentId("agent-a"), chatMode = CurrentFocusChatMode.New))
        }
        val args = ChatRouteArgs(SavedStateHandle(mapOf("agentId" to "agent-a"))).apply { bindWorkspace(owner) }
        val release = CompletableDeferred<Unit>()
        val backend = mockk<IConversationRepository> {
            coEvery { createConversation(AgentId("agent-a"), null) } coAnswers {
                release.await()
                Conversation(ConversationId("late-created"), AgentId("agent-a"))
            }
        }
        val repository = args.focusedCreation(backend)
        val result = async { runCatching { repository.createConversation(AgentId("agent-a")) } }
        runCurrent()
        owner.navigate(ShellDestination.Chat("agent-b", "b"))
        owner.selectFocus(CurrentFocus("chat", AgentId("agent-a"), chatMode = CurrentFocusChatMode.New))
        args.bindWorkspace(owner)
        release.complete(Unit)
        advanceUntilIdle()

        assertTrue(result.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertNull(owner.currentFocus.conversationId)
        assertEquals(CurrentFocusChatMode.New, owner.currentFocus.chatMode)
        coVerify(exactly = 1) { backend.createConversation(AgentId("agent-a"), null) }
    }

    @Test
    fun acceptedCreationSettlesTheCapturedNewRequestBeforeSendPreparation() = runTest {
        val owner = ShellWorkspaceState().apply {
            bindSource("source", 1L)
            selectFocus(CurrentFocus("chat", AgentId("agent-a"), chatMode = CurrentFocusChatMode.New))
        }
        val args = ChatRouteArgs(SavedStateHandle(mapOf("agentId" to "agent-a"))).apply { bindWorkspace(owner) }
        val backend = mockk<IConversationRepository> {
            coEvery { createConversation(AgentId("agent-a"), null) } returns
                Conversation(ConversationId("created"), AgentId("agent-a"))
        }
        val created = args.focusedCreation(backend).createConversation(AgentId("agent-a"))
        assertEquals(ConversationId("created"), created.id)
        assertEquals(ConversationId("created"), owner.currentFocus.conversationId)
        assertEquals(CurrentFocusChatMode.Exact, owner.currentFocus.chatMode)
    }

    @Test
    fun creationRejectsRequestedAndReturnedAgentMismatches() = runTest {
        val owner = ShellWorkspaceState().apply {
            bindSource("source", 1L)
            selectFocus(CurrentFocus("chat", AgentId("agent-a"), chatMode = CurrentFocusChatMode.New))
        }
        val args = ChatRouteArgs(SavedStateHandle(mapOf("agentId" to "agent-a"))).apply { bindWorkspace(owner) }
        val backend = mockk<IConversationRepository> {
            coEvery { createConversation(AgentId("agent-a"), null) } returns
                Conversation(ConversationId("wrong-owner"), AgentId("agent-b"))
            coEvery { createConversation(AgentId("agent-b"), null) } returns
                Conversation(ConversationId("other-agent"), AgentId("agent-b"))
        }
        val repository = args.focusedCreation(backend)
        assertTrue(runCatching { repository.createConversation(AgentId("agent-b")) }.exceptionOrNull() is IllegalArgumentException)
        coVerify(exactly = 0) { backend.createConversation(AgentId("agent-b"), null) }
        assertTrue(runCatching { repository.createConversation(AgentId("agent-a")) }.exceptionOrNull() is IllegalArgumentException)
        assertNull(owner.currentFocus.conversationId)
        assertEquals(AgentId("agent-a"), owner.currentFocus.agentId)
    }

    @Test
    fun creationUsesTheCapturedSessionRepositoryInsteadOfTheFollowingProxy() = runTest {
        val owner = ShellWorkspaceState().apply {
            bindSource("source-a", 1L)
            selectFocus(CurrentFocus("chat", AgentId("agent-a"), chatMode = CurrentFocusChatMode.New))
        }
        val args = ChatRouteArgs(SavedStateHandle(mapOf("agentId" to "agent-a"))).apply { bindWorkspace(owner) }
        val captured = mockk<IConversationRepository> {
            coEvery { createConversation(AgentId("agent-a"), null) } returns
                Conversation(ConversationId("captured-created"), AgentId("agent-a"))
        }
        args.session = mockk<SessionRepositoryGraph> { every { conversationRepository } returns captured }
        val following = mockk<IConversationRepository> {
            coEvery { createConversation(AgentId("agent-a"), null) } returns
                Conversation(ConversationId("wrong-source-created"), AgentId("agent-a"))
        }

        val result = args.focusedCreation(following).createConversation(AgentId("agent-a"))

        assertEquals(ConversationId("captured-created"), result.id)
        assertEquals(ConversationId("captured-created"), owner.currentFocus.conversationId)
        coVerify(exactly = 1) { captured.createConversation(AgentId("agent-a"), null) }
        coVerify(exactly = 0) { following.createConversation(AgentId("agent-a"), null) }
    }

    @Test
    fun forkUsesTheCapturedSessionAndCannotImplicitlySelectItsResult() = runTest {
        val owner = ShellWorkspaceState(ShellDestination.Chat("agent-a", "source-conversation")).apply { bindSource("source-a", 1L) }
        val args = ChatRouteArgs(SavedStateHandle(mapOf("agentId" to "agent-a"))).apply { bindWorkspace(owner) }
        val captured = mockk<IConversationRepository> {
            coEvery { forkConversation(ConversationId("source-conversation"), AgentId("agent-a"), null) } returns
                Conversation(ConversationId("captured-fork"), AgentId("agent-a"))
        }
        args.session = mockk<SessionRepositoryGraph> { every { conversationRepository } returns captured }
        val following = mockk<IConversationRepository> {
            coEvery { forkConversation(ConversationId("source-conversation"), AgentId("agent-a"), null) } returns
                Conversation(ConversationId("wrong-source-fork"), AgentId("agent-a"))
        }

        val result = args.focusedCreation(following).forkConversation(ConversationId("source-conversation"), AgentId("agent-a"), null)

        assertEquals(ConversationId("captured-fork"), result.id)
        assertEquals(ConversationId("source-conversation"), owner.currentFocus.conversationId)
        coVerify(exactly = 1) { captured.forkConversation(ConversationId("source-conversation"), AgentId("agent-a"), null) }
        coVerify(exactly = 0) { following.forkConversation(ConversationId("source-conversation"), AgentId("agent-a"), null) }
    }

}
