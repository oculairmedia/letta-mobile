package com.letta.mobile.data.session

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionScopedAllConversationsRepositoryTest {

    @Test
    fun `all conversations repository proxy switches cache to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeConversationApi = FakeConversationApi().apply {
            conversations = mutableListOf(TestData.conversation(id = "conv-a", summary = "Backend A"))
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = sessionManager(settingsRepository, fakeConversationApi, dispatcher)
        val conversationsProxy = sessionManager.scopedAllConversationsRepository()

        conversationsProxy.refresh()
        advanceUntilIdle()
        assertEquals(listOf("conv-a"), conversationsProxy.conversations.value.map { it.id.value })

        fakeConversationApi.conversations = mutableListOf(TestData.conversation(id = "conv-b", summary = "Backend B"))
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        advanceUntilIdle()

        conversationsProxy.refresh()
        advanceUntilIdle()

        assertEquals(listOf("conv-b"), conversationsProxy.conversations.value.map { it.id.value })
        assertTrue(conversationsProxy.hasFreshConversations(maxAgeMs = 60_000))
    }

    @Test
    fun `conversation refresh retries when session graph rebuilds mid-operation`() = runTest {
        // letta-mobile-xzoy3: activeConfigChanges emits while the collector
        // refresh is mid-flight; the rebuild lands before the op finishes and
        // withCurrentSession's post-check throws CancellationException("Session
        // switched during operation"). The retry must re-run on the rebuilt graph.
        val refreshed = refreshAcrossARebuild(staleTransportErrors = 0)

        assertNull(refreshed.error)
        assertEquals(listOf("conv-b"), refreshed.listed)
        assertEquals(2, refreshed.listCalls)
    }

    @Test
    fun `conversation refresh survives stale transport error after session rebuild`() = runTest {
        // letta-mobile-xzoy3: the rebuild lands while the refresh runs on the
        // old graph; the stale transport throws the default-transport ISE. The
        // retry waits for the new graph and re-runs the refresh there.
        val refreshed = refreshAcrossARebuild(staleTransportErrors = 1)

        assertNull(refreshed.error)
        assertEquals(listOf("conv-b"), refreshed.listed)
        assertEquals(2, refreshed.listCalls)
    }

    @Test
    fun `conversation refresh rethrows stale transport error when session graph never settles`() = runTest {
        // letta-mobile-xzoy3: stale-transport error with no graph change within
        // the 300ms settle window -> original error surfaces (no silent no-op).
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeConversationApi = FakeConversationApi().apply {
            conversations = mutableListOf(TestData.conversation(id = "conv-a", summary = "Backend A"))
            staleTransportIaeCountdown = Int.MAX_VALUE
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = sessionManager(settingsRepository, fakeConversationApi, dispatcher)
        val conversationsProxy = sessionManager.scopedAllConversationsRepository()
        advanceUntilIdle()

        var error: Throwable? = null
        val refreshJob = launch { error = runCatching { conversationsProxy.refresh() }.exceptionOrNull() }
        advanceUntilIdle()
        refreshJob.join()

        assertTrue(error is IllegalStateException)
        assertEquals("admin_rpc is not supported by this transport", error?.message)
        assertEquals(1, fakeConversationApi.calls.count { it == "listConversations" })
    }

    @Test
    fun `conversation refresh abandons after retry budget when session graph keeps switching`() = runTest {
        // letta-mobile-xzoy3: bounded-retry guard — every attempt's post-check
        // fires because a fresh rebuild lands mid-operation; after
        // MAX_SESSION_SWITCH_RETRIES the refresh must stop and throw explicitly.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeConversationApi = BarrierConversationApi().apply {
            conversations = mutableListOf(TestData.conversation(id = "conv-a", summary = "Backend A"))
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = sessionManager(settingsRepository, fakeConversationApi, dispatcher)
        fakeConversationApi.currentGraph = { sessionManager.current }
        val conversationsProxy = sessionManager.scopedAllConversationsRepository()
        advanceUntilIdle()

        var error: Throwable? = null
        val refreshJob = launch { error = runCatching { conversationsProxy.refresh() }.exceptionOrNull() }
        var expectedGraph = sessionManager.current
        for (backend in listOf("backend-b", "backend-c", "backend-d")) {
            val attempt = fakeConversationApi.started.receive()
            assertSame(expectedGraph, attempt.graph)
            settingsRepository.activeConfigState.value = sessionTestConfig(backend)
            expectedGraph = sessionManager.currentGraph.first { it !== attempt.graph }
            assertNotSame(attempt.graph, expectedGraph)
            attempt.release.complete(Unit)
            assertNull(attempt.completed.await())
        }
        refreshJob.join()

        assertTrue(error is IllegalStateException)
        assertEquals(
            "Session graph kept switching; refresh abandoned after 3 attempts",
            error?.message,
        )
        assertEquals(3, fakeConversationApi.calls.count { it == "listConversations" })
    }

    /** What a refresh still in flight when the backend switches (failing [staleTransportErrors] times) ends with. */
    private data class RebuildRefresh(val error: Throwable?, val listed: List<String>, val listCalls: Int)

    private suspend fun TestScope.refreshAcrossARebuild(staleTransportErrors: Int): RebuildRefresh {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeConversationApi = BarrierConversationApi().apply {
            conversations = mutableListOf(TestData.conversation(id = "conv-b", summary = "Backend B"))
            staleTransportIaeCountdown = staleTransportErrors
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = sessionManager(settingsRepository, fakeConversationApi, dispatcher)
        fakeConversationApi.currentGraph = { sessionManager.current }
        val conversationsProxy = sessionManager.scopedAllConversationsRepository()
        advanceUntilIdle()

        val oldGraph = sessionManager.current
        var error: Throwable? = null
        val refreshJob = launch { error = runCatching { conversationsProxy.refresh() }.exceptionOrNull() }
        val firstAttempt = fakeConversationApi.started.receive()
        assertSame(oldGraph, firstAttempt.graph)
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        val rebuiltGraph = sessionManager.currentGraph.first { it !== oldGraph }
        assertNotSame(oldGraph, rebuiltGraph)
        firstAttempt.release.complete(Unit)
        val oldError = firstAttempt.completed.await()
        if (staleTransportErrors == 0) {
            assertNull(oldError)
        } else {
            assertTrue(oldError is IllegalStateException)
            assertEquals("admin_rpc is not supported by this transport", oldError?.message)
        }
        val retry = fakeConversationApi.started.receive()
        assertSame(rebuiltGraph, retry.graph)
        retry.release.complete(Unit)
        assertNull(retry.completed.await())
        refreshJob.join()
        return RebuildRefresh(
            error = error,
            listed = conversationsProxy.conversations.value.map { it.id.value },
            listCalls = fakeConversationApi.calls.count { it == "listConversations" },
        )
    }
}

/**
 * A session manager over [conversationApi]: the list reads its roster's agents' conversations, and
 * every test conversation is TestData's agent's, so the roster has that agent.
 */
private fun TestScope.sessionManager(
    settingsRepository: FakeSettingsRepository,
    conversationApi: FakeConversationApi,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
) = SessionManager(
    settingsRepository = settingsRepository,
    sessionGraphFactory = createTestDefaultSessionRepositoryGraphFactory {
        agentApi = FakeAgentApi().apply { agents = mutableListOf(TestData.agent()) }
        this.conversationApi = conversationApi
        graphParentScope = { backgroundScope }
    },
    managerScope = CoroutineScope(backgroundScope.coroutineContext + dispatcher),
)

/** Holds each real remote invocation until the test has observed the graph publication. */
private class BarrierConversationApi : FakeConversationApi() {
    class Attempt(val graph: SessionGraph) {
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Throwable?>()
    }

    lateinit var currentGraph: () -> SessionGraph
    val started = Channel<Attempt>(Channel.UNLIMITED)

    override suspend fun listConversations(
        agentId: AgentId?,
        limit: Int?,
        after: String?,
        archiveStatus: String?,
        summarySearch: String?,
        order: String?,
        orderBy: String?,
    ): List<Conversation> {
        val attempt = Attempt(currentGraph())
        started.send(attempt)
        try {
            attempt.release.await()
            val result = super.listConversations(agentId, limit, after, archiveStatus, summarySearch, order, orderBy)
            attempt.completed.complete(null)
            return result
        } catch (error: CancellationException) {
            attempt.completed.complete(error)
            throw error
        } catch (error: Throwable) {
            attempt.completed.complete(error)
            throw error
        }
    }
}
