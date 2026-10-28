package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.repository.api.AgentLocalCache
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CachedAgentRepositoryTest {
    @Test
    fun completedReadMakesImmediateRecollectionFreshAfterBulkExpiry() = runTest {
        val remote = AgentRepositoryTestSource()
        val repository = CachedAgentRepository(remote.bound(), repositoryScope = backgroundScope)
        repository.refreshAgents()
        repository.setLastRefreshForTest(0)

        repository.getAgent(ID).toList()
        repository.getAgent(ID).toList()

        assertEquals(1, remote.getCalls.size)
    }

    @Test
    fun concurrentCollectorsOfSameIdShareOneRequest() = runTest {
        val remote = AgentRepositoryTestSource()
        val gate = CompletableDeferred<Unit>()
        remote.read = { id -> gate.await(); agent(id) }
        val repository = CachedAgentRepository(remote.bound(), repositoryScope = backgroundScope)
        val first = async { repository.getAgent(ID).toList() }
        val second = async { repository.getAgent(ID).toList() }
        runCurrent()
        val callsWhileBlocked = remote.getCalls.size
        gate.complete(Unit)

        assertEquals(first.await(), second.await())
        assertEquals(1, callsWhileBlocked)
        assertEquals(1, remote.getCalls.size)
    }

    @Test
    fun targetedReadCompletingBeforeOverlappingBulkRefreshWinsItsOlderSnapshot() = runTest {
        verifyReadRace(CompletesFirst.Detail)
    }

    @Test
    fun bulkRefreshCompletingBeforeOverlappingTargetedReadKeepsItsNewerSnapshot() = runTest {
        verifyReadRace(CompletesFirst.Roster)
    }

    @Test
    fun targetedReadStartedAfterBulkRequestKeepsItsNewerResult() = runTest {
        val remote = AgentRepositoryTestSource()
        val readGate = CompletableDeferred<Unit>()
        val listGate = CompletableDeferred<Unit>()
        remote.read = { id -> readGate.await(); agent(id, "New targeted") }
        remote.listGate = listGate
        remote.roster = listOf(agent(ID, "Old bulk"))
        val repository = repository(remote)
        val bulk = async { repository.refreshAgents() }
        runCurrent()
        val targeted = async { repository.getAgent(ID).toList() }
        runCurrent()
        listGate.complete(Unit)
        bulk.await()
        readGate.complete(Unit)
        assertEquals(listOf(agent(ID, "New targeted")), targeted.await())
        assertEquals(listOf(agent(ID, "New targeted")), repository.agents.value)
    }

    @Test
    fun differentIdsFetchInParallel() = runTest {
        val remote = AgentRepositoryTestSource()
        val gate = CompletableDeferred<Unit>()
        remote.read = { id -> gate.await(); agent(id) }
        val repository = repository(remote)
        val first = async { repository.getAgent(ID).toList() }
        val second = async { repository.getAgent(OTHER_ID).toList() }
        runCurrent()
        assertEquals(listOf(ID, OTHER_ID), remote.getCalls)
        gate.complete(Unit)
        assertEquals(ID, first.await().single().id)
        assertEquals(OTHER_ID, second.await().single().id)
    }

    @Test
    fun expiryIsPerAgentAndMeasuredFromCompletedRead() = runTest {
        val remote = AgentRepositoryTestSource()
        remote.roster += agent(OTHER_ID)
        val repository = repository(remote)
        repository.getAgent(ID).toList()
        advanceTimeBy(10_000)
        repository.getAgent(OTHER_ID).toList()
        advanceTimeBy(20_000)
        repository.getAgent(ID).toList() // Inclusive 30-second boundary.
        assertEquals(listOf(ID, OTHER_ID), remote.getCalls)
        advanceTimeBy(1)
        repository.getAgent(ID).toList()
        repository.getAgent(OTHER_ID).toList()
        assertEquals(listOf(ID, OTHER_ID, ID), remote.getCalls)
    }

    @Test
    fun slowSuccessfulReadGetsAFullFreshnessWindowOnCompletion() = runTest {
        val remote = AgentRepositoryTestSource()
        val gate = CompletableDeferred<Unit>()
        remote.read = { id -> gate.await(); agent(id) }
        val repository = repository(remote)
        val read = async { repository.getAgent(ID).toList() }
        runCurrent()
        advanceTimeBy(31_000)
        gate.complete(Unit)
        read.await()
        repository.getAgent(ID).toList()
        assertEquals(1, remote.getCalls.size)
    }

    @Test
    fun firstCollectorLeavesRefreshedValueCachedBeforeReturning() = runTest {
        val remote = AgentRepositoryTestSource()
        val repository = repository(remote)
        assertEquals(agent(ID), repository.getAgent(ID).first())
        assertEquals(listOf(agent(ID)), repository.agents.value)
        repository.getAgent(ID).toList()
        assertEquals(1, remote.getCalls.size)
    }

    @Test
    fun cacheOnlyCollectorDoesNotStartAnUnobservedRequest() = runTest {
        val remote = AgentRepositoryTestSource()
        val repository = repository(remote)
        repository.refreshAgents()
        repository.setLastRefreshForTest(0)
        repository.getAgent(ID).first()
        runCurrent()
        assertTrue(remote.getCalls.isEmpty())
        repository.getAgent(ID).toList()
        assertEquals(1, remote.getCalls.size)
    }

    @Test
    fun freshBulkSuppressesReadsButForcedRefreshStillFetches() = runTest {
        val remote = AgentRepositoryTestSource()
        val repository = repository(remote)
        repository.refreshAgents()
        assertFalse(repository.refreshAgentsIfStale(60_000))
        repository.getAgent(ID).toList()
        remote.roster = listOf(agent(ID, "Changed"))
        repository.refreshAgents()
        assertEquals(listOf(agent(ID, "Changed")), repository.getAgent(ID).toList())
        assertEquals(2, remote.listCalls)
        assertTrue(remote.getCalls.isEmpty())
    }

    @Test
    fun concurrentStaleBulkCallersRecheckTheCompletedRefresh() = runTest {
        val remote = AgentRepositoryTestSource()
        val gate = CompletableDeferred<Unit>()
        remote.listGate = gate
        val repository = repository(remote)
        val reads = List(3) { async { repository.refreshAgentsIfStale(60_000) } }
        runCurrent()
        assertEquals(1, remote.listCalls)
        gate.complete(Unit)
        assertEquals(1, reads.count { it.await() })
        assertEquals(1, remote.listCalls)
    }

    @Test
    fun failureRetainsCachedCopyAndRetryDoesNotTrustFailedRead() = runTest {
        val remote = AgentRepositoryTestSource()
        val repository = repository(remote)
        repository.refreshAgents()
        repository.setLastRefreshForTest(0)
        remote.read = { error("offline") }
        val observed = mutableListOf<Agent>()
        assertFailsWith<IllegalStateException> { repository.getAgent(ID).collect { observed += it } }
        assertEquals(listOf(agent(ID)), observed)
        assertEquals(listOf(agent(ID)), repository.agents.value)
        remote.read = { id -> agent(id, "Recovered") }
        assertEquals(listOf("Agent", "Recovered"), repository.getAgent(ID).toList().map { it.name })
        repository.getAgent(ID).toList()
        assertEquals(2, remote.getCalls.size)
    }

    @Test
    fun concurrentFailureIsSharedAndNextCollectionRetries() = runTest {
        val remote = AgentRepositoryTestSource()
        val gate = CompletableDeferred<Unit>()
        remote.read = { gate.await(); error("offline") }
        val repository = repository(remote)
        val reads = List(2) { async { runCatching { repository.getAgent(ID).toList() } } }
        runCurrent()
        gate.complete(Unit)
        assertTrue(reads.all { it.await().exceptionOrNull() is IllegalStateException })
        assertTrue(repository.agents.value.isEmpty())
        remote.read = { id -> agent(id) }
        repository.getAgent(ID).toList()
        assertEquals(2, remote.getCalls.size)
    }

    @Test
    fun cancelingTheFirstWaiterDoesNotCancelSharedWork() = runTest {
        val remote = AgentRepositoryTestSource()
        val gate = CompletableDeferred<Unit>()
        remote.read = { id -> gate.await(); agent(id) }
        val repository = repository(remote)
        val first = async { repository.getAgent(ID).toList() }
        runCurrent()
        val second = async { repository.getAgent(ID).toList() }
        runCurrent()
        first.cancelAndJoin()
        assertFalse(second.isCompleted)
        gate.complete(Unit)
        assertEquals(listOf(agent(ID)), second.await())
        repository.getAgent(ID).toList()
        assertEquals(1, remote.getCalls.size)
    }

    @Test
    fun repositoryCancellationCancelsTheRequestAndReleasesItsWaiters() = runTest {
        val remote = AgentRepositoryTestSource()
        var canceled = false
        remote.read = {
            try { CompletableDeferred<Agent>().await() } finally { canceled = true }
        }
        val owner = Job(backgroundScope.coroutineContext[Job])
        val repository = CachedAgentRepository(
            remote.bound(), repositoryScope = CoroutineScope(backgroundScope.coroutineContext + owner),
        )
        val read = async { runCatching { repository.getAgent(ID).toList() } }
        runCurrent()
        owner.cancelAndJoin()
        assertTrue(canceled)
        assertTrue(read.await().exceptionOrNull() is CancellationException)
    }

    @Test
    fun clearDropsLateResponseAndOldCleanupCannotRemoveNewRequest() = runTest {
        val remote = AgentRepositoryTestSource()
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        remote.read = { id -> withContext(NonCancellable) { oldGate.await() }; agent(id, "Old session") }
        val repository = repository(remote)
        val old = async { runCatching { repository.getAgent(ID).toList() } }
        runCurrent()
        repository.clearForBackendSwitch()
        remote.read = { id -> newGate.await(); agent(id, "New session") }
        val current = async { repository.getAgent(ID).toList() }
        runCurrent()
        oldGate.complete(Unit)
        runCurrent()
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertTrue(repository.agents.value.isEmpty())
        val joined = async { repository.getAgent(ID).toList() }
        runCurrent()
        assertEquals(2, remote.getCalls.size)
        newGate.complete(Unit)
        assertEquals(listOf(agent(ID, "New session")), current.await())
        assertEquals(current.await(), joined.await())
    }

    @Test
    fun clearInvalidatesAnAlreadyCompletedFreshnessStamp() = runTest {
        val remote = AgentRepositoryTestSource()
        val repository = repository(remote)
        repository.getAgent(ID).toList()
        repository.clearForBackendSwitch()
        remote.roster = listOf(agent(ID, "New session"))
        assertEquals(listOf(agent(ID, "New session")), repository.getAgent(ID).toList())
        assertEquals(2, remote.getCalls.size)
    }

    @Test
    fun clearDuringBulkFetchCannotPublishOrPersistItsOldRoster() = runTest {
        val remote = AgentRepositoryTestSource()
        val gate = CompletableDeferred<Unit>()
        remote.listGate = gate
        remote.ignoreListCancellation = true
        val local = FakeAgentCache()
        val repository = repository(remote, local = local)
        val old = async { runCatching { repository.refreshAgents() } }
        runCurrent()
        repository.clearForBackendSwitch()
        remote.listGate = null
        remote.roster = listOf(agent(ID, "New session"))
        repository.refreshAgents()
        gate.complete(Unit)
        runCurrent()
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertEquals(listOf(agent(ID, "New session")), repository.agents.value)
        assertEquals(repository.agents.value, local.rows)
        assertFalse(repository.isRefreshing.value)
        assertEquals(null, repository.refreshError.value)
    }

    @Test
    fun clearDuringInitialLocalReadCannotRestoreOldRows() = runTest {
        verifyLocalReadInvalidation { it.clearForBackendSwitch() }
    }

    @Test
    fun emptyRemoteRefreshDuringInitialLocalReadDoesNotRestoreOldRows() = runTest {
        val remote = AgentRepositoryTestSource().apply { roster = emptyList() }
        val gate = CompletableDeferred<Unit>()
        val (repository, local) = hydratingRepository(remote, gate)

        repository.refreshAgents()
        assertTrue(repository.agents.value.isEmpty())
        assertTrue(local.rows.isEmpty())
        assertFalse(repository.refreshAgentsIfStale(60_000))
        gate.complete(Unit)
        runCurrent()
        assertTrue(repository.agents.value.isEmpty())
    }

    @Test
    fun deleteDuringInitialLocalReadDoesNotRestoreDeletedRow() = runTest {
        verifyLocalReadInvalidation { it.deleteAgent(ID) }
    }

    @Test
    fun pushRefreshBypassesFreshnessAndStampsTheNewCopy() = runTest {
        val remote = AgentRepositoryTestSource()
        val transport = PushingTransport()
        val local = FakeAgentCache()
        val repository = repository(remote, transport, local)
        repository.refreshAgents()
        remote.roster = listOf(agent(ID, "Pushed"))
        transport.push("updated")
        runCurrent()
        assertEquals(listOf(agent(ID, "Pushed")), repository.getAgent(ID).toList())
        assertEquals(1, remote.getCalls.size)
        assertEquals(repository.agents.value, local.rows)
    }

    @Test
    fun failedPushInvalidatesFreshStampAndAllowsRetry() = runTest {
        val remote = AgentRepositoryTestSource()
        val transport = PushingTransport()
        val repository = repository(remote, transport)
        repository.refreshAgents()
        remote.read = { error("offline") }
        transport.push("updated")
        runCurrent()
        assertEquals(listOf(agent(ID)), repository.agents.value)
        remote.read = { id -> agent(id, "Recovered") }
        repository.getAgent(ID).toList()
        assertEquals(2, remote.getCalls.size)
    }

    @Test
    fun pushSupersedesAnOlderReadWithoutItsCleanupRemovingTheNewRequest() = runTest {
        val remote = AgentRepositoryTestSource()
        val transport = PushingTransport()
        val repository = repository(remote, transport)
        val oldGate = CompletableDeferred<Unit>()
        val pushGate = CompletableDeferred<Unit>()
        remote.read = { id -> withContext(NonCancellable) { oldGate.await() }; agent(id, "Old") }
        val old = async { runCatching { repository.getAgent(ID).toList() } }
        runCurrent()
        remote.read = { id -> pushGate.await(); agent(id, "Pushed") }
        transport.push("updated")
        runCurrent()
        oldGate.complete(Unit)
        runCurrent()
        val joined = async { repository.getAgent(ID).toList() }
        runCurrent()
        assertEquals(2, remote.getCalls.size)
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        pushGate.complete(Unit)
        assertEquals(listOf(agent(ID, "Pushed")), joined.await())
    }

    @Test
    fun targetedPushAndDeleteWinOverAnOlderBulkSnapshot() = runTest {
        val remote = AgentRepositoryTestSource()
        remote.roster += agent(OTHER_ID)
        val transport = PushingTransport()
        val repository = repository(remote, transport)
        repository.refreshAgents()
        val gate = CompletableDeferred<Unit>()
        remote.listGate = gate
        val bulk = async { repository.refreshAgents() }
        runCurrent()
        remote.read = { id -> agent(id, "Pushed") }
        transport.push("updated")
        runCurrent()
        transport.push("deleted", OTHER_ID)
        runCurrent()
        assertEquals(listOf(agent(ID, "Pushed")), repository.agents.value)
        gate.complete(Unit)
        bulk.await()
        assertEquals(listOf(agent(ID, "Pushed")), repository.getAgent(ID).toList())
        assertEquals(listOf(agent(ID, "Pushed")), repository.agents.value)
    }

    @Test
    fun deleteInvalidatesPendingReadAndAFollowingReadMustReachRemote() = runTest {
        val remote = AgentRepositoryTestSource()
        val gate = CompletableDeferred<Unit>()
        remote.read = { id -> withContext(NonCancellable) { gate.await() }; agent(id, "Deleted") }
        val repository = repository(remote)
        val old = async { runCatching { repository.getAgent(ID).toList() } }
        runCurrent()
        repository.deleteAgent(ID)
        gate.complete(Unit)
        runCurrent()
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertTrue(repository.agents.value.isEmpty())
        remote.read = { error("not found") }
        assertFailsWith<IllegalStateException> { repository.getAgent(ID).toList() }
        assertEquals(2, remote.getCalls.size)
    }

    @Test
    fun updateInvalidatesPendingReadAndBulkRefreshesTheNewAgent() = runTest {
        val remote = AgentRepositoryTestSource()
        val gate = CompletableDeferred<Unit>()
        remote.read = { id -> withContext(NonCancellable) { gate.await() }; agent(id, "Old") }
        val repository = repository(remote)
        val old = async { runCatching { repository.getAgent(ID).toList() } }
        runCurrent()
        val updated = repository.updateAgent(ID, AgentUpdateParams(name = "Updated"))
        gate.complete(Unit)
        runCurrent()
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertEquals(listOf(updated), repository.getAgent(ID).toList())
        assertEquals(1, remote.getCalls.size)
        assertEquals(1, remote.listCalls)
    }

    private suspend fun TestScope.verifyReadRace(first: CompletesFirst) {
        val remote = AgentRepositoryTestSource()
        val readGate = CompletableDeferred<Unit>()
        val listGate = CompletableDeferred<Unit>()
        val targetedName = if (first == CompletesFirst.Detail) "Targeted" else "Old targeted"
        remote.read = { id -> readGate.await(); agent(id, targetedName) }
        remote.listGate = listGate
        val repository = repository(remote)
        val (targeted, bulk) = overlappingReads(repository, remote) {
            if (first == CompletesFirst.Roster) remote.roster = listOf(agent(ID, "Fresh bulk"))
        }
        val observed = when (first) {
            CompletesFirst.Detail -> {
                readGate.complete(Unit)
                val result = targeted.await() // Observe the detail before the older roster can finish.
                listGate.complete(Unit)
                bulk.await()
                result
            }
            CompletesFirst.Roster -> {
                listGate.complete(Unit)
                bulk.await()
                readGate.complete(Unit)
                targeted.await()
            }
        }
        val expected = agent(ID, if (first == CompletesFirst.Detail) "Targeted" else "Fresh bulk")
        assertEquals(listOf(expected), observed)
        assertEquals(listOf(expected), repository.agents.value)
    }

    private suspend fun TestScope.verifyLocalReadInvalidation(invalidate: suspend (CachedAgentRepository) -> Unit) {
        val remote = AgentRepositoryTestSource()
        val gate = CompletableDeferred<Unit>()
        val (repository, local) = hydratingRepository(remote, gate)
        invalidate(repository)
        gate.complete(Unit)
        runCurrent()
        assertTrue(repository.agents.value.isEmpty())
        assertTrue(local.rows.isEmpty())
    }

    private fun TestScope.overlappingReads(
        repository: CachedAgentRepository,
        remote: AgentRepositoryTestSource,
        beforeRoster: () -> Unit = {},
    ): Pair<Deferred<List<Agent>>, Deferred<Unit>> {
        val targeted = async { repository.getAgent(ID).toList() }
        runCurrent()
        beforeRoster()
        val bulk = async { repository.refreshAgents() }
        runCurrent()
        assertEquals(1, remote.getCalls.size)
        assertEquals(1, remote.listCalls)
        return targeted to bulk
    }

    private fun TestScope.hydratingRepository(
        remote: AgentRepositoryTestSource,
        gate: CompletableDeferred<Unit>,
    ): Pair<CachedAgentRepository, FakeAgentCache> {
        val local = FakeAgentCache().apply { rows = listOf(agent(ID, "Old disk")); readGate = gate }
        val repository = repository(remote, local = local)
        runCurrent() // getAllOnce has captured the old disk row and is waiting on gate.
        return repository to local
    }

    private fun TestScope.repository(
        remote: AgentRepositoryTestSource,
        transport: PushingTransport? = null,
        local: FakeAgentCache? = null,
    ): CachedAgentRepository = cachedAgentRepository(remote, transport, local)

    private class PushingTransport : NoOpChannelTransport() {
        override val events = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 8)
        suspend fun push(reason: String, id: AgentId = ID) {
            events.emit(ServerFrame.AgentUpdated(id = "push", ts = "2026-10-26T00:00:00Z", agentId = id.value, reason = reason))
        }
    }

    private class FakeAgentCache : AgentLocalCache {
        var rows = emptyList<Agent>()
        var readGate: CompletableDeferred<Unit>? = null
        override suspend fun getAllOnce(): List<Agent> {
            val snapshot = rows
            readGate?.await()
            return snapshot
        }
        override suspend fun insertAll(agents: List<Agent>) { rows = agents }
        override suspend fun upsert(agent: Agent) { rows = rows.filterNot { it.id == agent.id } + agent }
        override suspend fun deleteExcept(keepIds: List<String>) { rows = rows.filter { it.id.value in keepIds } }
        override suspend fun deleteById(id: String) { rows = rows.filterNot { it.id.value == id } }
        override suspend fun deleteAll() { rows = emptyList() }
    }


    private companion object {
        val ID = AgentId("agent-1")
        val OTHER_ID = AgentId("agent-2")
        fun agent(id: AgentId, name: String = "Agent") = Agent(id = id, name = name)
    }
}

private enum class CompletesFirst { Detail, Roster }
