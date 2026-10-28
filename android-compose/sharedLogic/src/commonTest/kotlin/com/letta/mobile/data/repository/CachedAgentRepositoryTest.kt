package com.letta.mobile.data.repository

import com.letta.mobile.data.agenteditor.AgentEditorController
import com.letta.mobile.data.agenteditor.AgentEditorMode
import com.letta.mobile.data.agenteditor.AgentEditorRepositories
import com.letta.mobile.data.agenteditor.RepositoryAgentEditorBackend
import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import kotlinx.collections.immutable.persistentListOf
import com.letta.mobile.data.repository.api.AgentRemoteSource
import com.letta.mobile.data.repository.api.AgentLocalCache
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import kotlinx.coroutines.CancellationException
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
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CachedAgentRepositoryTest {
    @Test
    fun editorShowsStaleCacheThenFreshDetailWithExactlyOneRequest() = runTest {
        val remote = FakeAgentSource()
        val repository = repository(remote)
        repository.refreshAgents()
        repository.setLastRefreshForTest(0)
        val gate = CompletableDeferred<Unit>()
        val fresh = agent(ID, "Fresh detail").copy(system = "Full system prompt")
        remote.read = { gate.await(); fresh }
        val editor = editor(repository)

        assertEquals("Agent", editor.state.value.form.name)
        runCurrent()
        assertEquals(1, remote.getCalls.size)
        assertTrue(editor.state.value.loading)
        assertFalse(editor.state.value.canSave)
        gate.complete(Unit)
        runCurrent()

        assertEquals("Fresh detail", editor.state.value.form.name)
        assertEquals("Full system prompt", editor.state.value.form.systemPrompt)
        assertFalse(editor.state.value.loading)
        assertEquals(1, remote.getCalls.size)
    }

    @Test
    fun editorFailurePreservesCachedContentAndCannotSaveAMissingSystemPrompt() = runTest {
        val remote = FakeAgentSource().apply { roster = listOf(agent(ID).copy(model = "openai/gpt-5")) }
        val repository = repository(remote)
        repository.refreshAgents() // A fresh list row still cannot supply the system prompt.
        remote.read = { error("Detail fetch failed") }
        val editor = editor(repository)
        runCurrent()

        assertEquals("Agent", editor.state.value.form.name)
        assertEquals("Detail fetch failed", editor.state.value.loadError)
        assertFalse(editor.state.value.loading)
        editor.update { copy(name = "Edited name", compactProfile = true) }
        editor.dismissError()
        var saved = false
        editor.save { saved = true }
        runCurrent()
        assertFalse(saved)
        assertFalse(editor.state.value.canSave)
        assertEquals(0, remote.updates.size)
        assertEquals(1, remote.getCalls.size)

        remote.read = { agent(ID).copy(model = "openai/gpt-5", system = "Protected system prompt") }
        editor.retry()
        runCurrent()
        assertEquals("Protected system prompt", editor.state.value.form.systemPrompt)
        assertEquals("Edited name", editor.state.value.form.name)
        editor.save { saved = true }
        runCurrent()
        assertTrue(saved)
        assertEquals(null, remote.updates.single().system)
        assertEquals(2, remote.getCalls.size)
    }

    @Test
    fun editsDuringDetailLoadKeepFreshSystemAndPreviouslyUnlistedTools() = runTest {
        val remote = FakeAgentSource()
        val repository = repository(remote)
        repository.refreshAgents()
        val gate = CompletableDeferred<Unit>()
        remote.read = {
            gate.await()
            agent(ID).copy(system = "Full prompt", tools = persistentListOf(Tool(id = ToolId("existing"), name = "Existing")))
        }
        val editor = editor(repository)
        runCurrent()
        editor.update { copy(name = "Renamed", toolIds = setOf("added")) }
        gate.complete(Unit)
        runCurrent()

        assertEquals("Renamed", editor.state.value.form.name)
        assertEquals("Full prompt", editor.state.value.form.systemPrompt)
        assertEquals(setOf("existing", "added"), editor.state.value.form.toolIds)
        assertEquals(1, remote.getCalls.size)
    }

    @Test
    fun editorTimeoutPreservesCachedContentAndEndsTheSpinner() = runTest {
        val remote = FakeAgentSource()
        val repository = repository(remote)
        repository.refreshAgents()
        remote.read = { kotlinx.coroutines.awaitCancellation() }
        val editor = editor(repository)
        runCurrent()
        advanceTimeBy(AgentEditorController.LOAD_TIMEOUT_MS + 1)
        runCurrent()

        assertEquals("Agent", editor.state.value.form.name)
        assertEquals(AgentEditorController.LOAD_TIMED_OUT, editor.state.value.loadError)
        assertFalse(editor.state.value.loading)
        assertFalse(editor.state.value.canSave)
        assertEquals(1, remote.getCalls.size)
    }

    @Test
    fun invalidatingAnEditorRequestEndsLoadingWithoutAllowingSave() = runTest {
        val remote = FakeAgentSource()
        val repository = repository(remote)
        repository.refreshAgents()
        remote.read = { kotlinx.coroutines.awaitCancellation() }
        val editor = editor(repository)
        runCurrent()
        repository.clearForBackendSwitch()
        runCurrent()

        assertEquals("Agent", editor.state.value.form.name)
        assertFalse(editor.state.value.loading)
        assertTrue(editor.state.value.loadError != null)
        assertFalse(editor.state.value.canSave)
    }

    @Test
    fun detailLoadsShareRequestsRespectTtlAndForceBypassesFreshness() = runTest {
        val remote = FakeAgentSource()
        val repository = repository(remote)
        repository.refreshAgents()
        val gate = CompletableDeferred<Unit>()
        remote.read = { gate.await(); agent(ID).copy(system = "Detail") }
        val first = async { repository.getFreshAgent(ID) }
        val second = async { repository.getFreshAgent(ID, force = true) }
        runCurrent()
        assertEquals(1, remote.getCalls.size)
        gate.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals("Detail", repository.getFreshAgent(ID).system)
        assertEquals(1, remote.getCalls.size)
        repository.getFreshAgent(ID, force = true)
        assertEquals(2, remote.getCalls.size)
        repository.setLastRefreshForTest(0)
        repository.getFreshAgent(ID)
        assertEquals(3, remote.getCalls.size)
    }

    @Test
    fun aDetailWaiterCannotBeSatisfiedByAnOverlappingListWithoutSystem() = runTest {
        val remote = FakeAgentSource()
        val repository = repository(remote)
        val gate = CompletableDeferred<Unit>()
        remote.read = { gate.await(); agent(ID, "Older detail").copy(system = "Detail system") }
        val detail = async { repository.getFreshAgent(ID, force = true) }
        runCurrent()
        repository.refreshAgents()
        gate.complete(Unit)

        assertEquals("Detail system", detail.await().system)
        assertEquals("Agent", repository.getCachedAgent(ID)?.name)
        assertEquals(1, remote.getCalls.size)
    }

    @Test
    fun configCheckpointReadsDetailEvenWhenTheListIsFreshAndOmitsSystem() = runTest {
        val remote = FakeAgentSource()
        val repository = repository(remote)
        repository.refreshAgents()
        remote.read = { agent(ID).copy(system = "Checkpoint system") }

        repository.checkpointAndRestoreConfig(ID) { }

        assertEquals(2, remote.getCalls.size) // Before and after the operation, both detail reads.
        assertTrue(remote.updates.isEmpty())
    }

    @Test
    fun cancellingOneDetailWaiterDoesNotCancelTheOther() = runTest {
        val remote = FakeAgentSource()
        val gate = CompletableDeferred<Unit>()
        remote.read = { gate.await(); agent(ID) }
        val repository = repository(remote)
        val first = async { repository.getFreshAgent(ID, force = true) }
        val second = async { repository.getFreshAgent(ID, force = true) }
        runCurrent()
        first.cancelAndJoin()
        gate.complete(Unit)

        assertEquals(agent(ID), second.await())
        assertEquals(1, remote.getCalls.size)
    }

    private fun TestScope.editor(repository: CachedAgentRepository) = AgentEditorController(
        RepositoryAgentEditorBackend(AgentEditorRepositories(repository, tools = null, models = null)),
        backgroundScope,
        AgentEditorMode.Edit(ID.value),
    )

    @Test
    fun completedReadMakesImmediateRecollectionFreshAfterBulkExpiry() = runTest {
        val remote = FakeAgentSource()
        val repository = CachedAgentRepository(remote.bound(), repositoryScope = backgroundScope)
        repository.refreshAgents()
        repository.setLastRefreshForTest(0)

        repository.getAgent(ID).toList()
        repository.getAgent(ID).toList()

        assertEquals(1, remote.getCalls.size)
    }

    @Test
    fun concurrentCollectorsOfSameIdShareOneRequest() = runTest {
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
        val readGate = CompletableDeferred<Unit>()
        val listGate = CompletableDeferred<Unit>()
        remote.read = { id -> readGate.await(); agent(id, "Targeted") }
        remote.listGate = listGate
        val repository = repository(remote)
        val targeted = async { repository.getAgent(ID).toList() }
        runCurrent()
        val bulk = async { repository.refreshAgents() }
        runCurrent()
        assertEquals(1, remote.getCalls.size)
        assertEquals(1, remote.listCalls)

        readGate.complete(Unit)
        assertEquals(listOf(agent(ID, "Targeted")), targeted.await())
        listGate.complete(Unit)
        bulk.await()
        assertEquals(listOf(agent(ID, "Targeted")), repository.agents.value)
    }

    @Test
    fun bulkRefreshCompletingBeforeOverlappingTargetedReadKeepsItsNewerSnapshot() = runTest {
        val remote = FakeAgentSource()
        val readGate = CompletableDeferred<Unit>()
        val listGate = CompletableDeferred<Unit>()
        remote.read = { id -> readGate.await(); agent(id, "Old targeted") }
        remote.listGate = listGate
        val repository = repository(remote)
        val targeted = async { repository.getAgent(ID).toList() }
        runCurrent()
        remote.roster = listOf(agent(ID, "Fresh bulk"))
        val bulk = async { repository.refreshAgents() }
        runCurrent()
        assertEquals(1, remote.getCalls.size)
        assertEquals(1, remote.listCalls)

        listGate.complete(Unit)
        bulk.await()
        readGate.complete(Unit)
        assertEquals(listOf(agent(ID, "Fresh bulk")), targeted.await())
        assertEquals(listOf(agent(ID, "Fresh bulk")), repository.agents.value)
    }

    @Test
    fun targetedReadStartedAfterBulkRequestKeepsItsNewerResult() = runTest {
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
        val repository = repository(remote)
        assertEquals(agent(ID), repository.getAgent(ID).first())
        assertEquals(listOf(agent(ID)), repository.agents.value)
        repository.getAgent(ID).toList()
        assertEquals(1, remote.getCalls.size)
    }

    @Test
    fun cacheOnlyCollectorDoesNotStartAnUnobservedRequest() = runTest {
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
        val repository = repository(remote)
        repository.getAgent(ID).toList()
        repository.clearForBackendSwitch()
        remote.roster = listOf(agent(ID, "New session"))
        assertEquals(listOf(agent(ID, "New session")), repository.getAgent(ID).toList())
        assertEquals(2, remote.getCalls.size)
    }

    @Test
    fun clearDuringBulkFetchCannotPublishOrPersistItsOldRoster() = runTest {
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
        val gate = CompletableDeferred<Unit>()
        val local = FakeAgentCache().apply { rows = listOf(agent(ID, "Old disk")); readGate = gate }
        val repository = repository(remote, local = local)
        runCurrent()
        repository.clearForBackendSwitch()
        gate.complete(Unit)
        runCurrent()
        assertTrue(repository.agents.value.isEmpty())
        assertTrue(local.rows.isEmpty())
    }

    @Test
    fun emptyRemoteRefreshDuringInitialLocalReadDoesNotRestoreOldRows() = runTest {
        val remote = FakeAgentSource().apply { roster = emptyList() }
        val gate = CompletableDeferred<Unit>()
        val local = FakeAgentCache().apply { rows = listOf(agent(ID, "Old disk")); readGate = gate }
        val repository = repository(remote, local = local)
        runCurrent() // getAllOnce has captured the old disk row.

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
        val remote = FakeAgentSource()
        val gate = CompletableDeferred<Unit>()
        val local = FakeAgentCache().apply { rows = listOf(agent(ID, "Old disk")); readGate = gate }
        val repository = repository(remote, local = local)
        runCurrent() // getAllOnce has captured the row before deletion.

        repository.deleteAgent(ID)
        gate.complete(Unit)
        runCurrent()
        assertTrue(local.rows.isEmpty())
        assertTrue(repository.agents.value.isEmpty())
    }

    @Test
    fun pushRefreshBypassesFreshnessAndStampsTheNewCopy() = runTest {
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
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
        val remote = FakeAgentSource()
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

    private fun TestScope.repository(
        remote: FakeAgentSource,
        transport: PushingTransport? = null,
        local: FakeAgentCache? = null,
    ): CachedAgentRepository = CachedAgentRepository(
        remote.bound(), localCache = local?.let { { it } }, repositoryScope = backgroundScope, transport = transport,
    ).also { repository ->
        repository.clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(1_000_000 + testScheduler.currentTime)
        }
    }

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

    private class FakeAgentSource : AgentRemoteSource {
        val getCalls = mutableListOf<AgentId>()
        val updates = mutableListOf<AgentUpdateParams>()
        var roster = listOf(agent(ID))
        var listCalls = 0
        var listGate: CompletableDeferred<Unit>? = null
        var ignoreListCancellation = false
        var read: suspend (AgentId) -> Agent = { id -> roster.first { it.id == id } }
        override suspend fun getAgent(agentId: AgentId): Agent {
            getCalls += agentId
            return read(agentId)
        }
        override suspend fun listAgents(limit: Int?, offset: Int?, tags: List<String>?): List<Agent> {
            listCalls += 1
            val snapshot = roster
            val gate = listGate
            if (ignoreListCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
            return snapshot
        }
        override suspend fun listAgentsSlim(limit: Int?, offset: Int?, tags: List<String>?) =
            roster.map { AgentSummary(it.id, it.name, it.description) }
        override suspend fun countAgents() = roster.size
        override suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?) = ContextWindowOverview()
        override suspend fun updateAgent(agentId: AgentId, params: AgentUpdateParams): Agent =
            agent(agentId, params.name ?: "Updated").also { updated ->
                updates += params
                roster = roster.map { if (it.id == agentId) updated else it }
            }
        override suspend fun deleteAgent(agentId: AgentId) { roster = roster.filterNot { it.id == agentId } }
        override suspend fun createAgent(params: AgentCreateParams): Agent = error("unused")
        override suspend fun exportAgent(agentId: AgentId): String = error("unused")
        override suspend fun importAgent(params: AgentImportParams): ImportedAgentsResponse = error("unused")
        override suspend fun attachArchive(agentId: AgentId, archiveId: String) = Unit
        override suspend fun detachArchive(agentId: AgentId, archiveId: String) = Unit
    }

    private companion object {
        val ID = AgentId("agent-1")
        val OTHER_ID = AgentId("agent-2")
        fun agent(id: AgentId, name: String = "Agent") = Agent(id = id, name = name)
    }
}
