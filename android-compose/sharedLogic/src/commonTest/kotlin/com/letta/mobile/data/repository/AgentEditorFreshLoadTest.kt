package com.letta.mobile.data.repository

import com.letta.mobile.data.agenteditor.AgentEditorController
import com.letta.mobile.data.agenteditor.AgentEditorMode
import com.letta.mobile.data.agenteditor.AgentEditorRepositories
import com.letta.mobile.data.agenteditor.RepositoryAgentEditorBackend
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentEditorFreshLoadTest {
    @Test
    fun editorShowsStaleCacheThenFreshDetailWithExactlyOneRequest() = runTest {
        val remote = AgentRepositoryTestSource()
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
        val remote = AgentRepositoryTestSource().apply { roster = listOf(agent(ID).copy(model = "openai/gpt-5")) }
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
        val remote = AgentRepositoryTestSource()
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
        val remote = AgentRepositoryTestSource()
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
        val remote = AgentRepositoryTestSource()
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
        val remote = AgentRepositoryTestSource()
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
        val remote = AgentRepositoryTestSource()
        val repository = repository(remote)
        val gate = CompletableDeferred<Unit>()
        remote.read = { gate.await(); agent(ID, "Older detail").copy(system = "Detail system") }
        val ordinary = async { repository.getAgent(ID).toList() }
        runCurrent()
        val detail = async { repository.getFreshAgent(ID, force = true) }
        runCurrent()
        repository.refreshAgents()
        gate.complete(Unit)

        assertEquals("Detail system", detail.await().system)
        assertEquals(listOf(agent(ID)), ordinary.await())
        assertEquals("Agent", repository.getCachedAgent(ID)?.name)
        assertEquals(1, remote.getCalls.size)
    }

    @Test
    fun configCheckpointReadsDetailEvenWhenTheListIsFreshAndOmitsSystem() = runTest {
        val remote = AgentRepositoryTestSource()
        val repository = repository(remote)
        repository.refreshAgents()
        remote.read = { agent(ID).copy(system = "Checkpoint system") }

        repository.checkpointAndRestoreConfig(ID) { }

        assertEquals(2, remote.getCalls.size) // Before and after the operation, both detail reads.
        assertTrue(remote.updates.isEmpty())
    }

    @Test
    fun cancellingOneDetailWaiterDoesNotCancelTheOther() = runTest {
        val remote = AgentRepositoryTestSource()
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

    @Test
    fun detailSurvivesARosterRefreshThatOmitsTheAgent() = runTest {
        val remote = AgentRepositoryTestSource()
        val repository = repository(remote)
        val detailGate = CompletableDeferred<Unit>()
        val fresh = agent(ID, "Fresh detail")
        remote.read = { detailGate.await(); fresh }
        val detail = async { repository.getFreshAgent(ID, force = true) }
        runCurrent()
        remote.roster = emptyList()
        repository.refreshAgents()
        detailGate.complete(Unit)

        assertEquals(fresh, detail.await())
        assertEquals(emptyList(), repository.agents.value.map { it.id })
    }

    private fun TestScope.editor(repository: CachedAgentRepository) = AgentEditorController(
        RepositoryAgentEditorBackend(AgentEditorRepositories(repository, tools = null, models = null)),
        backgroundScope,
        AgentEditorMode.Edit(ID.value),
    )


    private fun TestScope.repository(remote: AgentRepositoryTestSource) = cachedAgentRepository(remote)
}

private val ID = AgentId("agent-1")
private fun agent(id: AgentId, name: String = "Agent") = Agent(id = id, name = name)
