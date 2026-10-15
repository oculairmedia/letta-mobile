package com.letta.mobile.data.session

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-by4vm.88: characterization of what the Hilt-bound session-scoped repositories do
 * when the user switches backends. Written against the hand-written SessionScoped* proxies before
 * they were replaced by [sessionScoped]; [Bound] is the only part that names the implementation
 * (it constructed the old proxies, and the proxies themselves were the caches).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionScopedBackendSwitchCharacterizationTest {

    /** How production binds each interface; the tests below never construct an implementation. */
    private class Bound(sessionManager: SessionManager) {
        val agents: IAgentRepository = sessionManager.scopedAgentRepository()
        val conversations: IConversationRepository =
            sessionManager.sessionScoped(IConversationRepository::class) { it.conversationRepository }
        val allConversations: IAllConversationsRepository = sessionManager.scopedAllConversationsRepository()
        val caches: Set<BackendScopedCache> = sessionManager.sessionBackendScopedCaches()
    }

    private class Harness(val settings: FakeSettingsRepository, val bound: Bound) {
        fun switchTo(id: String) {
            settings.activeConfigState.value = sessionTestConfig(id)
        }
    }

    private fun TestScope.harness(setup: TestDefaultSessionRepositoryGraphFactoryBuilder.() -> Unit): Harness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settings = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settings,
            sessionGraphFactory = createTestDefaultSessionRepositoryGraphFactory(setup),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        return Harness(settings, Bound(sessionManager))
    }

    @Test
    fun stateFlowIsEmptyRightAfterSwitchAndCollectorsFollowTheNewBackend() = runTest {
        val api = FakeAgentApi().apply { agents = mutableListOf(TestData.agent(id = "agent-a")) }
        val h = harness { agentApi = api }
        val seen = mutableListOf<List<String>>()
        // A foreground collector: advanceUntilIdle does not wait for backgroundScope tasks.
        val collector = h.bound.agents.agents.onEach { list -> seen += list.map { it.id.value } }.launchIn(this)

        h.bound.agents.refreshAgents()
        advanceUntilIdle()
        api.agents = mutableListOf(TestData.agent(id = "agent-b"))
        h.switchTo("backend-b")
        advanceUntilIdle()

        assertTrue(h.bound.agents.agents.value.isEmpty())
        h.bound.agents.refreshAgents()
        advanceUntilIdle()

        assertEquals(listOf("agent-b"), h.bound.agents.agents.value.map { it.id.value })
        assertEquals(listOf("agent-a"), seen.first { it.isNotEmpty() })
        assertEquals(listOf("agent-b"), seen.last())
        collector.cancel()
    }

    @Test
    fun flowCallMadeBeforeSwitchFollowsTheRebuiltGraph() = runTest {
        val api = FakeConversationApi().apply {
            conversations = mutableListOf(TestData.conversation(id = "conv-a", agentId = "agent-1"))
        }
        val h = harness { conversationApi = api }
        val seen = mutableListOf<List<String>>()
        val collector = h.bound.conversations.getConversations(AgentId("agent-1"))
            .onEach { list -> seen += list.map { it.id.value } }
            .launchIn(this)

        h.bound.conversations.refreshConversations(AgentId("agent-1"))
        advanceUntilIdle()
        api.conversations = mutableListOf(TestData.conversation(id = "conv-b", agentId = "agent-1"))
        h.switchTo("backend-b")
        advanceUntilIdle()
        h.bound.conversations.refreshConversations(AgentId("agent-1"))
        advanceUntilIdle()

        assertTrue(seen.contains(listOf("conv-a")))
        assertEquals(listOf("conv-b"), seen.last())
        collector.cancel()
    }

    @Test
    fun suspendCallSpanningASwitchIsCancelledAsSessionSwitched() = runTest {
        val api = FakeConversationApi().apply {
            conversations = mutableListOf(TestData.conversation(id = "conv-a", agentId = "agent-1"))
            listDelayMillis = 50L
        }
        val h = harness { conversationApi = api }
        advanceUntilIdle()

        var error: Throwable? = null
        val call = launch {
            error = runCatching { h.bound.conversations.refreshConversations(AgentId("agent-1")) }.exceptionOrNull()
        }
        advanceTimeBy(1)
        h.switchTo("backend-b")
        advanceUntilIdle()
        call.join()

        assertTrue("expected cancellation, got $error", error is CancellationException)
        assertEquals("Session switched during operation", error?.message)
    }

    @Test
    fun invalidatorClearsTheCurrentGraphThroughEveryBoundCache() = runTest {
        val agentApi = FakeAgentApi().apply { agents = mutableListOf(TestData.agent(id = "agent-a")) }
        val conversationApi = FakeConversationApi().apply {
            conversations = mutableListOf(TestData.conversation(id = "conv-a", agentId = "agent-1"))
        }
        val h = harness {
            this.agentApi = agentApi
            this.conversationApi = conversationApi
        }
        h.bound.agents.refreshAgents()
        h.bound.conversations.refreshConversations(AgentId("agent-1"))
        h.bound.allConversations.refresh()
        advanceUntilIdle()
        assertEquals(1, h.bound.agents.agents.value.size)

        val result = BackendSwitchInvalidator(h.bound.caches).clearAll()
        advanceUntilIdle()

        assertTrue(result.failures.toString(), result.allSucceeded)
        assertEquals(4, result.successes)
        assertTrue(h.bound.agents.agents.value.isEmpty())
        assertTrue(h.bound.conversations.getCachedConversations(AgentId("agent-1")).isEmpty())
        assertTrue(h.bound.allConversations.conversations.value.isEmpty())
    }
}
