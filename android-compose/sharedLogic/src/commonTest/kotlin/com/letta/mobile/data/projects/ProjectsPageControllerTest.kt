package com.letta.mobile.data.projects

import com.letta.mobile.data.model.BeadsRemoteProvisionResponse
import com.letta.mobile.data.model.BeadsRemoteStatus
import com.letta.mobile.data.model.ProjectCatalog
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.model.ProjectSyncTriggerResponse
import com.letta.mobile.data.repository.api.IProjectRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-ckmub.2.5 (S17): the shared projects catalog over an Iroh host's `project.*` relay. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsPageControllerTest {
    private val alpha = ProjectSummary(identifier = "alpha", name = "Alpha", filesystemPath = "/src/alpha", lastActivityAt = "2026-10-01T00:00:00Z")
    private val beta = ProjectSummary(identifier = "beta", name = "Beta", filesystemPath = "/src/beta", lastActivityAt = "2026-10-05T00:00:00Z")
    private val gamma = ProjectSummary(identifier = "gamma", name = "Gamma", filesystemPath = "/src/gamma")

    private class Harness(val controller: ProjectsPageController, val repository: FakeProjectRepository, val pins: FakePins, val reloads: MutableSharedFlow<Unit>)

    private fun TestScope.harness(repository: FakeProjectRepository = FakeProjectRepository(listOf(alpha, beta, gamma))): Harness {
        val pins = FakePins()
        val reloads = MutableSharedFlow<Unit>()
        val controller = ProjectsPageController(ProjectsPageSources(repository, pins, reloads), backgroundScope)
        controller.start()
        runCurrent()
        return Harness(controller, repository, pins, reloads)
    }

    @Test
    fun pinnedFirstThenTheMostRecent() = runTest {
        val h = harness()
        assertEquals(listOf("beta", "alpha", "gamma"), h.controller.state.value.visibleProjects.map { it.identifier })
        h.controller.togglePinned("gamma")
        runCurrent()
        assertEquals(listOf("gamma", "beta", "alpha"), h.controller.state.value.visibleProjects.map { it.identifier })
        assertEquals(listOf("gamma" to true), h.pins.writes)
    }

    @Test
    fun theSearchMatchesNameAndPath() = runTest {
        val h = harness()
        h.controller.setQuery("src/al")
        assertEquals(listOf("alpha"), h.controller.state.value.visibleProjects.map { it.identifier })
    }

    @Test
    fun aBackendSwitchReloads() = runTest {
        val h = harness()
        val before = h.repository.forcedLoads
        h.reloads.emit(Unit)
        runCurrent()
        assertEquals(before + 1, h.repository.forcedLoads)
    }

    @Test
    fun aCatalogThatCannotLoadIsAnError() = runTest {
        val h = harness(FakeProjectRepository(emptyList(), loadFailure = IllegalStateException("no relay")))
        assertEquals("no relay", h.controller.state.value.error)
    }

    @Test
    fun selectingReadsTheBeadsRemote() = runTest {
        val h = harness()
        h.controller.select("alpha")
        runCurrent()
        assertEquals("alpha", h.controller.state.value.selected?.identifier)
        assertEquals("missing", h.controller.state.value.beadsStatus["alpha"]?.status)
    }

    @Test
    fun aNewProjectNeedsANameAndAnAbsolutePath() = runTest {
        val h = harness()
        val editor = h.controller.editor
        editor.startCreate()
        editor.updateDraft(ProjectDraft(name = "Delta", filesystemPath = "relative/path"))
        editor.submit()
        runCurrent()
        assertTrue(h.repository.created.isEmpty())
        editor.updateDraft(ProjectDraft(name = " Delta ", filesystemPath = " /src/delta ", gitUrl = ""))
        editor.submit()
        runCurrent()
        assertEquals(listOf(Triple<String?, String, String?>("Delta", "/src/delta", null)), h.repository.created)
        assertNull(h.controller.state.value.editor)
        assertEquals("Created Delta.", h.controller.state.value.notice)
        assertTrue(h.controller.state.value.projects.any { it.identifier == "delta" })
    }

    @Test
    fun aRefusedEditKeepsTheFormOpen() = runTest {
        val h = harness(FakeProjectRepository(listOf(alpha), updateFailure = IllegalStateException("path not reachable")))
        h.controller.editor.startEdit("alpha")
        h.controller.editor.updateDraft(ProjectDraft(name = "Alpha", filesystemPath = "/elsewhere"))
        h.controller.editor.submit()
        runCurrent()
        val editor = assertNotNull(h.controller.state.value.editor)
        assertEquals("alpha", editor.identifier)
        assertEquals(false, editor.submitting)
        assertEquals("path not reachable", h.controller.state.value.notice)
    }

    @Test
    fun deletingClosesTheDetailAndAFailureReopensIt() = runTest {
        val h = harness()
        h.controller.select("beta")
        h.controller.delete("beta")
        runCurrent()
        assertNull(h.controller.state.value.selectedId)
        assertEquals("Deleted Beta.", h.controller.state.value.notice)
        assertEquals(listOf("alpha", "gamma"), h.controller.state.value.visibleProjects.map { it.identifier })

        val failing = harness(FakeProjectRepository(listOf(alpha), archiveFailure = IllegalStateException("locked")))
        failing.controller.select("alpha")
        failing.controller.archive("alpha")
        runCurrent()
        assertEquals("alpha", failing.controller.state.value.selectedId)
        assertEquals("locked", failing.controller.state.value.notice)
    }

    @Test
    fun syncAndProvisionReportTheirOutcome() = runTest {
        val h = harness()
        h.controller.sync("alpha")
        runCurrent()
        assertNull(h.controller.state.value.syncingId)
        assertEquals("sync queued", h.controller.state.value.notice)
        h.controller.provisionBeadsRemote("alpha")
        runCurrent()
        assertEquals("https://beads.example/alpha", h.controller.state.value.notice)
        assertEquals("provisioned", h.controller.state.value.beadsStatus["alpha"]?.status)
    }

    private class FakePins : ProjectPinStore {
        private val ids = MutableStateFlow<Set<String>>(emptySet())
        val writes = mutableListOf<Pair<String, Boolean>>()
        override val pinnedIds: Flow<Set<String>> = ids

        override suspend fun setPinned(projectId: String, pinned: Boolean) {
            writes += projectId to pinned
            ids.value = if (pinned) ids.value + projectId else ids.value - projectId
        }
    }

    private class FakeProjectRepository(
        initial: List<ProjectSummary>,
        private val loadFailure: Throwable? = null,
        private val updateFailure: Throwable? = null,
        private val archiveFailure: Throwable? = null,
    ) : IProjectRepository {
        private val stored = initial.toMutableList()
        private val flow = MutableStateFlow<List<ProjectSummary>>(emptyList())
        private val provisioned = mutableSetOf<String>()
        val created = mutableListOf<Triple<String?, String, String?>>()
        var forcedLoads = 0

        override val projects: StateFlow<List<ProjectSummary>> = flow

        override suspend fun refreshProjects(): ProjectCatalog {
            forcedLoads++
            return publish()
        }

        override suspend fun refreshProjectsIfStale(maxAgeMs: Long): Boolean {
            publish()
            return true
        }

        private fun publish(): ProjectCatalog {
            loadFailure?.let { throw it }
            flow.value = stored.toList()
            return ProjectCatalog(projects = stored.toList())
        }

        override suspend fun getProject(identifier: String): ProjectSummary = stored.first { it.identifier == identifier }
        override suspend fun getBeadsRemoteStatus(identifier: String): BeadsRemoteStatus =
            BeadsRemoteStatus(status = if (identifier in provisioned) "provisioned" else "missing")
        override suspend fun provisionBeadsRemote(identifier: String, push: Boolean): BeadsRemoteProvisionResponse {
            provisioned += identifier
            return BeadsRemoteProvisionResponse(status = "provisioned", remoteUrl = "https://beads.example/$identifier")
        }
        override suspend fun triggerSync(identifier: String): ProjectSyncTriggerResponse = ProjectSyncTriggerResponse(message = "sync queued")
        override suspend fun createProject(name: String?, filesystemPath: String, gitUrl: String?): ProjectSummary {
            created += Triple(name, filesystemPath, gitUrl)
            val project = ProjectSummary(identifier = name.orEmpty().lowercase(), name = name.orEmpty(), filesystemPath = filesystemPath)
            stored += project
            return project
        }
        override suspend fun updateProject(identifier: String, filesystemPath: String?, gitUrl: String?): ProjectSummary {
            updateFailure?.let { throw it }
            return stored.first { it.identifier == identifier }.copy(filesystemPath = filesystemPath, gitUrl = gitUrl)
        }
        override suspend fun archiveProject(identifier: String): ProjectSummary {
            archiveFailure?.let { throw it }
            return stored.first { it.identifier == identifier }.also { stored.remove(it) }
        }
        override suspend fun deleteProject(identifier: String) {
            stored.removeAll { it.identifier == identifier }
        }
        override fun hasFreshProjects(maxAgeMs: Long): Boolean = false
    }
}
