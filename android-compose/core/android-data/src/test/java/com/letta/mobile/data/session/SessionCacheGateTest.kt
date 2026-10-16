package com.letta.mobile.data.session

import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.GatedDao
import com.letta.mobile.data.local.SessionCacheGate
import com.letta.mobile.data.local.SessionSupersededException
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-by4vm.94: the session cache clear runs in the background behind a per-generation
 * gate. Every test holds the clear open on an explicit [CompletableDeferred] and drives virtual
 * time, so "before the clear lands" is a fixed point rather than a race.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCacheGateTest {

    /** Room as the previous session left it; the clear stalls in the agent delete until released. */
    private class HeldCache {
        val release = CompletableDeferred<Unit>()
        val agents = FakeAgentDao()
        val conversations = FakeConversationDao().apply {
            conversations.value = listOf(row("previous-session", agent = "a"))
        }
        /** Completed clears, counted at the clear's last step. */
        var clearsCompleted = 0
            private set

        val heldAgentDao: AgentDao = object : AgentDao by agents {
            override suspend fun deleteAll() {
                release.await()
                agents.deleteAll()
            }
        }

        val countingConversationDao: ConversationDao = object : ConversationDao by conversations {
            override suspend fun deleteAllRefreshStates() {
                conversations.deleteAllRefreshStates()
                clearsCompleted++
            }
        }

        private val assembler = SessionGraphAssembler(
            agentDao = dagger.Lazy { heldAgentDao },
            conversationDao = dagger.Lazy { countingConversationDao },
        )

        suspend fun clear() = assembler.clearCachesForNewSession()

        fun rowIds(): List<String> = conversations.conversations.value.map { it.id }
    }

    private fun TestScope.chain(cache: HeldCache) =
        SessionCacheClearChain(StandardTestDispatcher(testScheduler)) { cache.clear() }

    private fun gatedConversations(cache: HeldCache, gate: SessionCacheGate): GatedDao<ConversationDao> =
        GatedDao(dagger.Lazy { cache.conversations }, gate)

    @Test
    fun `first graph built on the main path returns before the cache clear lands`() = runTest {
        val cache = HeldCache()
        val factory = createTestDefaultSessionRepositoryGraphFactory {
            agentDao = dagger.Lazy { cache.heldAgentDao }
            conversationDao = dagger.Lazy { cache.countingConversationDao }
            graphParentScope = { backgroundScope }
            cacheClearContext = StandardTestDispatcher(testScheduler)
        }

        // SessionManager builds its first graph in its constructor, as Hilt injection does on main.
        val manager = SessionManager(
            settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a")),
            sessionGraphFactory = factory,
            managerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
        runCurrent()

        assertEquals(0, cache.clearsCompleted)
        assertEquals(listOf("previous-session"), cache.rowIds())

        // The graph's own repository is gated: its first read waits, then sees the cleared cache.
        val read = async { manager.current.conversationRepository.getConversations(AgentId("a")).first() }
        runCurrent()
        assertFalse(read.isCompleted)

        cache.release.complete(Unit)
        // The clear runs in backgroundScope, which advanceUntilIdle does not drain; awaiting the
        // gated read does, and the read can only finish once the clear opened the gate.
        val rows = read.await()

        assertEquals(emptyList<String>(), rows.map { it.id })
        assertEquals(1, cache.clearsCompleted)
    }

    @Test
    fun `a read issued before the clear completes waits and sees the cleared state`() = runTest {
        val cache = HeldCache()
        val dao = gatedConversations(cache, chain(cache).startGeneration(backgroundScope))

        val once = async { dao.get().getAllOnce() }
        val observed = async { dao.get().observeForAgent("a").first() }
        runCurrent()
        assertFalse(once.isCompleted)
        assertFalse(observed.isCompleted)

        cache.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList<ConversationEntity>(), once.await())
        assertEquals(emptyList<ConversationEntity>(), observed.await())
    }

    @Test
    fun `a write issued before the clear completes lands after it and is kept`() = runTest {
        val cache = HeldCache()
        val dao = gatedConversations(cache, chain(cache).startGeneration(backgroundScope))

        val write = async { dao.get().upsert(row("new-session", agent = "a")) }
        runCurrent()
        assertFalse(write.isCompleted)
        assertEquals(listOf("previous-session"), cache.rowIds())

        cache.release.complete(Unit)
        advanceUntilIdle()

        write.await()
        assertEquals(listOf("new-session"), cache.rowIds())
    }

    @Test
    fun `a backend switch during a pending clear supersedes the old generation and keeps the new writes`() = runTest {
        val cache = HeldCache()
        val clears = chain(cache)
        val first = gatedConversations(cache, clears.startGeneration(backgroundScope))
        val staleWrite = async { runCatching { first.get().upsert(row("stale-generation", agent = "a")) } }
        runCurrent()

        val second = gatedConversations(cache, clears.startGeneration(backgroundScope))
        val freshWrite = async { second.get().upsert(row("new-session", agent = "b")) }
        runCurrent()

        assertTrue(staleWrite.await().exceptionOrNull() is SessionSupersededException)
        assertFalse(freshWrite.isCompleted)

        cache.release.complete(Unit)
        advanceUntilIdle()

        freshWrite.await()
        // The first clear was already running, so it finished; the second ran strictly after it.
        assertEquals(2, cache.clearsCompleted)
        assertEquals(listOf("new-session"), cache.rowIds())
    }

    @Test
    fun `a backend switch through the session manager never blocks and publishes the new graph`() = runTest {
        val cache = HeldCache()
        val settings = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = SessionManager(
            settingsRepository = settings,
            sessionGraphFactory = createTestDefaultSessionRepositoryGraphFactory {
                agentDao = dagger.Lazy { cache.heldAgentDao }
                conversationDao = dagger.Lazy { cache.countingConversationDao }
                graphParentScope = { backgroundScope }
                cacheClearContext = dispatcher
            },
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val firstGraph = manager.current
        runCurrent()

        settings.activeConfigState.value = sessionTestConfig("backend-b")
        runCurrent()

        assertNotSame(firstGraph, manager.current)
        assertEquals(0, cache.clearsCompleted)

        val read = async { manager.current.conversationRepository.getConversations(AgentId("a")).first() }
        cache.release.complete(Unit)
        // The clear runs in backgroundScope, which advanceUntilIdle does not drain; awaiting the
        // gated read does, and the read can only finish once the clear opened the gate.
        val rows = read.await()

        assertEquals(emptyList<String>(), rows.map { it.id })
        assertEquals(2, cache.clearsCompleted)
    }

    @Test
    fun `a failed clear fails the generation closed instead of serving old rows`() = runTest {
        val cache = HeldCache()
        val gate = SessionCacheClearChain(StandardTestDispatcher(testScheduler)) {
            error("disk full")
        }.startGeneration(backgroundScope)
        val dao = gatedConversations(cache, gate)

        val read = async { runCatching { dao.get().getAllOnce() } }
        advanceUntilIdle()

        assertEquals("disk full", read.await().exceptionOrNull()?.message)
    }

    private companion object {
        fun row(id: String, agent: String) = ConversationEntity(id = id, agentId = agent, cachedAtEpochMs = 0L)
    }
}
