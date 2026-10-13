package com.letta.mobile.data.projects

import com.letta.mobile.data.model.BeadsRemoteProvisionResponse
import com.letta.mobile.data.model.BeadsRemoteStatus
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.model.ProjectSyncTriggerResponse
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
    private val alpha = project("Alpha", activity = "2026-10-01T00:00:00Z")
    private val beta = project("Beta", activity = "2026-10-05T00:00:00Z")
    private val gamma = project("Gamma")

    private class Harness(val controller: ProjectsPageController, val backend: FakeProjectsBackend, val pins: FakePins, val reloads: MutableSharedFlow<Unit>)

    private fun TestScope.harness(backend: FakeProjectsBackend = FakeProjectsBackend(listOf(alpha, beta, gamma))): Harness {
        val pins = FakePins()
        val reloads = MutableSharedFlow<Unit>()
        val controller = ProjectsPageController(ProjectsPageSources(backend, pins, reloads), backgroundScope)
        controller.start()
        runCurrent()
        return Harness(controller, backend, pins, reloads)
    }

    private val ProjectsPageController.order: List<ProjectKey> get() = state.value.visibleProjects.map { it.key }

    @Test
    fun pinnedFirstThenTheMostRecent() = runTest {
        val h = harness()
        assertEquals(listOf(beta.key, alpha.key, gamma.key), h.controller.order)
        h.controller.togglePinned(gamma.key)
        runCurrent()
        assertEquals(listOf(gamma.key, beta.key, alpha.key), h.controller.order)
        assertEquals(listOf(gamma.key to ProjectPin.Pinned), h.pins.writes)
    }

    @Test
    fun theSearchMatchesNameAndPath() = runTest {
        val h = harness()
        h.controller.setQuery("src/al")
        assertEquals(listOf(alpha.key), h.controller.order)
    }

    @Test
    fun aBackendSwitchReloads() = runTest {
        val h = harness()
        val before = h.backend.forcedLoads
        h.reloads.emit(Unit)
        runCurrent()
        assertEquals(before + 1, h.backend.forcedLoads)
    }

    @Test
    fun aCatalogThatCannotLoadIsAnError() = runTest {
        val h = harness(FakeProjectsBackend(emptyList(), FakeFailures(load = IllegalStateException("no relay"))))
        assertEquals("no relay", h.controller.state.value.error)
    }

    @Test
    fun selectingReadsTheBeadsRemote() = runTest {
        val h = harness()
        h.controller.select(alpha.key)
        runCurrent()
        assertEquals(alpha, h.controller.state.value.selected)
        assertEquals("missing", h.controller.state.value.beadsStatus[alpha.key]?.status)
    }

    @Test
    fun aNewProjectNeedsANameAndAnAbsolutePath() = runTest {
        val h = harness()
        val editor = h.controller.editor
        editor.startCreate()
        editor.updateDraft(ProjectDraft(name = "Delta", filesystemPath = "relative/path"))
        editor.submit()
        runCurrent()
        assertTrue(h.backend.created.isEmpty())
        editor.updateDraft(ProjectDraft(name = " Delta ", filesystemPath = " /src/delta ", gitUrl = ""))
        editor.submit()
        runCurrent()
        assertEquals(listOf(ProjectDraft(name = "Delta", filesystemPath = "/src/delta")), h.backend.created)
        assertNull(h.controller.state.value.editor)
        assertEquals("Created Delta.", h.controller.state.value.notice)
        assertTrue(h.controller.state.value.projects.any { it.key == ProjectKey("delta") })
    }

    @Test
    fun aRefusedEditKeepsTheFormOpen() = runTest {
        val h = harness(FakeProjectsBackend(listOf(alpha), FakeFailures(update = IllegalStateException("path not reachable"))))
        h.controller.editor.startEdit(alpha.key)
        h.controller.editor.updateDraft(ProjectDraft(name = "Alpha", filesystemPath = "/elsewhere"))
        h.controller.editor.submit()
        runCurrent()
        val editor = assertNotNull(h.controller.state.value.editor)
        assertEquals(alpha.key, editor.target)
        assertEquals(false, editor.submitting)
        assertEquals("path not reachable", h.controller.state.value.notice)
    }

    @Test
    fun deletingClosesTheDetailAndAFailureReopensIt() = runTest {
        val h = harness()
        h.controller.select(beta.key)
        h.controller.delete(beta.key)
        runCurrent()
        assertNull(h.controller.state.value.selectedKey)
        assertEquals("Deleted Beta.", h.controller.state.value.notice)
        assertEquals(listOf(alpha.key, gamma.key), h.controller.order)

        val failing = harness(FakeProjectsBackend(listOf(alpha), FakeFailures(archive = IllegalStateException("locked"))))
        failing.controller.select(alpha.key)
        failing.controller.archive(alpha.key)
        runCurrent()
        assertEquals(alpha.key, failing.controller.state.value.selectedKey)
        assertEquals("locked", failing.controller.state.value.notice)
    }

    @Test
    fun syncAndProvisionReportTheirOutcome() = runTest {
        val h = harness()
        h.controller.sync(alpha.key)
        runCurrent()
        assertNull(h.controller.state.value.syncing)
        assertEquals("sync queued", h.controller.state.value.notice)
        h.controller.provisionBeadsRemote(alpha.key)
        runCurrent()
        assertEquals("https://beads.example/alpha", h.controller.state.value.notice)
        assertEquals("provisioned", h.controller.state.value.beadsStatus[alpha.key]?.status)
    }

    @Test
    fun aHostErrorMapperWordsTheFailure() {
        val words = ProjectFailureWords.of { _, fallback -> "mapped: $fallback" }
        assertEquals("mapped: Failed to delete project", words.describe(IllegalStateException(), ProjectFailure.Delete))
    }

    private class FakePins : ProjectPinStore {
        private val keys = MutableStateFlow<Set<ProjectKey>>(emptySet())
        val writes = mutableListOf<Pair<ProjectKey, ProjectPin>>()
        override val pinned: Flow<Set<ProjectKey>> = keys

        override suspend fun set(key: ProjectKey, pin: ProjectPin) {
            writes += key to pin
            keys.value = if (pin == ProjectPin.Pinned) keys.value + key else keys.value - key
        }
    }

    /** What the fake host refuses. */
    private class FakeFailures(
        val load: Throwable? = null,
        val update: Throwable? = null,
        val archive: Throwable? = null,
    )

    private class FakeProjectsBackend(initial: List<ProjectSummary>, private val failures: FakeFailures = FakeFailures()) : ProjectsBackend {
        private val stored = initial.toMutableList()
        private val flow = MutableStateFlow<List<ProjectSummary>>(emptyList())
        private val provisioned = mutableSetOf<ProjectKey>()
        val created = mutableListOf<ProjectDraft>()
        var forcedLoads = 0

        override val projects: StateFlow<List<ProjectSummary>> = flow

        override suspend fun reload(mode: ProjectReload) {
            if (mode == ProjectReload.Force) forcedLoads++
            failures.load?.let { throw it }
            flow.value = stored.toList()
        }

        override suspend fun beadsStatus(key: ProjectKey) = BeadsRemoteStatus(status = if (key in provisioned) "provisioned" else "missing")

        override suspend fun provisionBeads(key: ProjectKey): BeadsRemoteProvisionResponse {
            provisioned += key
            return BeadsRemoteProvisionResponse(status = "provisioned", remoteUrl = "https://beads.example/${key.value}")
        }

        override suspend fun sync(key: ProjectKey) = ProjectSyncTriggerResponse(message = "sync queued")

        override suspend fun create(draft: ProjectDraft): ProjectSummary {
            created += draft
            return ProjectSummary(identifier = draft.name.lowercase(), name = draft.name, filesystemPath = draft.filesystemPath).also { stored += it }
        }

        override suspend fun update(key: ProjectKey, draft: ProjectDraft): ProjectSummary {
            failures.update?.let { throw it }
            return stored.first { it.key == key }.copy(filesystemPath = draft.filesystemPath)
        }

        override suspend fun archive(key: ProjectKey): ProjectSummary {
            failures.archive?.let { throw it }
            return stored.first { it.key == key }.also { stored.remove(it) }
        }

        override suspend fun delete(key: ProjectKey) {
            stored.removeAll { it.key == key }
        }
    }
}

/** A project whose identifier is its lower-cased [name]. */
private fun project(name: String, activity: String? = null) =
    ProjectSummary(identifier = name.lowercase(), name = name, filesystemPath = "/src/${name.lowercase()}", lastActivityAt = activity)
