package com.letta.mobile.ui.screens.projects

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ProjectApi
import com.letta.mobile.data.model.ProjectCatalog
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.repository.ProjectRepository
import com.letta.mobile.ui.common.UiState
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectHomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeApi: FakeProjectApi
    private lateinit var repository: ProjectRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeApi = FakeProjectApi()
        repository = ProjectRepository(fakeApi)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadProjects sorts projects by newest activity then name`() = runTest {
        fakeApi.projects += listOf(
            project(
                identifier = "beta",
                name = "Beta",
                updatedAt = "2026-04-10T10:00:00Z",
            ),
            project(
                identifier = "alpha-zulu",
                name = "Zulu",
                updatedAt = "2026-04-11T10:00:00Z",
            ),
            project(
                identifier = "alpha-echo",
                name = "Echo",
                updatedAt = "2026-04-11T10:00:00Z",
            ),
        )

        val viewModel = ProjectHomeViewModel(repository)

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(listOf("Echo", "Zulu", "Beta"), state.data.projects.map { it.name })
        assertEquals(listOf("listProjects"), fakeApi.calls)
    }

    @Test
    fun `refresh forces a second project fetch`() = runTest {
        fakeApi.projects += project(identifier = "alpha", name = "Alpha")
        val viewModel = ProjectHomeViewModel(repository)

        viewModel.refresh()

        assertEquals(listOf("listProjects", "listProjects"), fakeApi.calls)
        val state = viewModel.uiState.value as UiState.Success
        assertEquals(false, state.data.isRefreshing)
    }

    @Test
    fun `selectProject tracks current project from loaded state`() = runTest {
        fakeApi.projects += listOf(
            project(identifier = "alpha", name = "Alpha"),
            project(identifier = "beta", name = "Beta"),
        )
        val viewModel = ProjectHomeViewModel(repository)

        viewModel.selectProject("beta")

        assertEquals("beta", (viewModel.uiState.value as UiState.Success).data.selectedProjectId)
        assertEquals("Beta", viewModel.currentProject()?.name)
    }

    @Test
    fun `startProjectSettingsEdit seeds draft from selected project`() = runTest {
        fakeApi.projects += project(
            identifier = "alpha",
            name = "Alpha",
            filesystemPath = "/opt/stacks/alpha",
            gitUrl = "https://github.com/example/alpha.git",
        )
        val viewModel = ProjectHomeViewModel(repository)

        viewModel.selectProject("alpha")
        viewModel.startProjectSettingsEdit()

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(true, state.data.showProjectSettingsDialog)
        assertEquals(null, state.data.selectedProjectId)
        assertEquals("alpha", state.data.projectSettingsDraft.identifier)
        assertEquals("Alpha", state.data.projectSettingsDraft.projectName)
        assertEquals("/opt/stacks/alpha", state.data.projectSettingsDraft.filesystemPath)
        assertEquals("https://github.com/example/alpha.git", state.data.projectSettingsDraft.gitUrl)
    }

    @Test
    fun `startManualProjectCreation opens manual dialog and hides create options`() = runTest {
        val viewModel = ProjectHomeViewModel(repository)

        viewModel.showCreateProjectOptions()
        viewModel.startManualProjectCreation()

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(false, state.data.showCreateOptions)
        assertEquals(true, state.data.showManualCreateDialog)
    }

    @Test
    fun `showCreateProjectOptions clears selected project`() = runTest {
        fakeApi.projects += listOf(
            project(identifier = "alpha", name = "Alpha"),
            project(identifier = "beta", name = "Beta"),
        )
        val viewModel = ProjectHomeViewModel(repository)

        viewModel.selectProject("beta")
        viewModel.showCreateProjectOptions()

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(null, state.data.selectedProjectId)
        assertEquals(true, state.data.showCreateOptions)
    }

    @Test
    fun `showCreateProjectOptions dismisses manual create dialog`() = runTest {
        val viewModel = ProjectHomeViewModel(repository)

        viewModel.startManualProjectCreation()
        viewModel.updateNewProjectDraft(
            NewProjectDraft(
                name = "Graphiti",
                filesystemPath = "/opt/stacks/graphiti",
            )
        )
        viewModel.showCreateProjectOptions()

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(true, state.data.showCreateOptions)
        assertEquals(false, state.data.showManualCreateDialog)
        assertEquals("", state.data.newProjectDraft.name)
    }

    @Test
    fun `selectProject dismisses create surfaces`() = runTest {
        fakeApi.projects += project(identifier = "alpha", name = "Alpha")
        val viewModel = ProjectHomeViewModel(repository)

        viewModel.showCreateProjectOptions()
        viewModel.startManualProjectCreation()
        viewModel.updateNewProjectDraft(
            NewProjectDraft(
                name = "Graphiti",
                filesystemPath = "/opt/stacks/graphiti",
            )
        )
        viewModel.selectProject("alpha")

        val state = viewModel.uiState.value as UiState.Success
        assertEquals("alpha", state.data.selectedProjectId)
        assertEquals(false, state.data.showCreateOptions)
        assertEquals(false, state.data.showManualCreateDialog)
        assertEquals("", state.data.newProjectDraft.name)
    }

    @Test
    fun `submitManualProjectCreation resets draft and exposes pending message`() = runTest {
        val viewModel = ProjectHomeViewModel(repository)
        val event = async { viewModel.events.first() }

        viewModel.startManualProjectCreation()
        viewModel.updateNewProjectDraft(
            NewProjectDraft(
                name = "Graphiti",
                filesystemPath = "/opt/stacks/graphiti",
            )
        )

        viewModel.submitManualProjectCreation()

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(false, state.data.showManualCreateDialog)
        assertEquals("", state.data.newProjectDraft.name)
        assertEquals(ProjectHomeUiEvent.ShowMessage("Created Graphiti."), event.await())
        assertTrue(fakeApi.calls.contains("createProject:Graphiti:/opt/stacks/graphiti:null"))
    }

    @Test
    fun `project settings draft requires absolute path`() {
        val draft = ProjectSettingsDraft(
            filesystemPath = "relative/path",
        )

        assertEquals(
            ProjectSettingsDraft.FilesystemPathValidation.MustBeAbsolute,
            draft.filesystemPathValidation(),
        )
        assertEquals(false, draft.isReadyToSubmit())
    }

    @Test
    fun `submitProjectSettingsEdit ignores invalid path draft`() = runTest {
        fakeApi.projects += project(identifier = "alpha", name = "Alpha")
        val viewModel = ProjectHomeViewModel(repository)

        viewModel.selectProject("alpha")
        viewModel.startProjectSettingsEdit()
        viewModel.updateProjectSettingsDraft(
            ProjectSettingsDraft(
                identifier = "alpha",
                projectName = "Alpha",
                filesystemPath = "relative/path",
            )
        )

        viewModel.submitProjectSettingsEdit()

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(true, state.data.showProjectSettingsDialog)
        assertEquals("relative/path", state.data.projectSettingsDraft.filesystemPath)
    }

    @Test
    fun `submitProjectSettingsEdit closes dialog and exposes pending notice`() = runTest {
        fakeApi.projects += project(identifier = "alpha", name = "Alpha")
        val viewModel = ProjectHomeViewModel(repository)
        val event = async { viewModel.events.first() }

        viewModel.selectProject("alpha")
        viewModel.startProjectSettingsEdit()
        viewModel.updateProjectSettingsDraft(
            ProjectSettingsDraft(
                identifier = "alpha",
                projectName = "Alpha",
                filesystemPath = "  /opt/stacks/alpha  ",
                gitUrl = "  https://github.com/example/alpha.git  ",
            )
        )

        viewModel.submitProjectSettingsEdit()

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(false, state.data.showProjectSettingsDialog)
        assertEquals("", state.data.projectSettingsDraft.filesystemPath)
        assertEquals(ProjectHomeUiEvent.ShowMessage("Saved project settings for Alpha."), event.await())
        assertTrue(fakeApi.calls.contains("updateProject:alpha:/opt/stacks/alpha:https://github.com/example/alpha.git"))
    }

    @Test
    fun `submitManualProjectCreation trims draft values before exposing pending notice`() = runTest {
        val viewModel = ProjectHomeViewModel(repository)
        val event = async { viewModel.events.first() }

        viewModel.startManualProjectCreation()
        viewModel.updateNewProjectDraft(
            NewProjectDraft(
                name = "  Graphiti  ",
                description = "  Project memory graph  ",
                filesystemPath = "  /opt/stacks/graphiti  ",
                gitUrl = "  https://github.com/example/graphiti.git  ",
            )
        )

        viewModel.submitManualProjectCreation()

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(ProjectHomeUiEvent.ShowMessage("Created Graphiti."), event.await())
    }

    @Test
    fun `submitManualProjectCreation exposes action error when create fails`() = runTest {
        fakeApi.createShouldFail = true
        val viewModel = ProjectHomeViewModel(repository)
        val event = async { viewModel.events.first() }

        viewModel.startManualProjectCreation()
        viewModel.updateNewProjectDraft(
            NewProjectDraft(
                name = "Graphiti",
                filesystemPath = "/opt/stacks/graphiti",
            )
        )

        viewModel.submitManualProjectCreation()

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(true, state.data.showManualCreateDialog)
        assertEquals(ProjectHomeUiEvent.ShowMessage("Create failed"), event.await())
    }

    @Test
    fun `submitProjectSettingsEdit exposes action error when update fails`() = runTest {
        fakeApi.projects += project(identifier = "alpha", name = "Alpha")
        fakeApi.updateShouldFail = true
        val viewModel = ProjectHomeViewModel(repository)
        val event = async { viewModel.events.first() }

        viewModel.selectProject("alpha")
        viewModel.startProjectSettingsEdit()
        viewModel.updateProjectSettingsDraft(
            ProjectSettingsDraft(
                identifier = "alpha",
                projectName = "Alpha",
                filesystemPath = "/opt/stacks/alpha",
            )
        )

        viewModel.submitProjectSettingsEdit()

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(true, state.data.showProjectSettingsDialog)
        assertEquals(ProjectHomeUiEvent.ShowMessage("Update failed"), event.await())
    }

    @Test
    fun `new project draft is not ready when required fields are whitespace only`() {
        val draft = NewProjectDraft(
            name = "   ",
            filesystemPath = "   ",
        )

        assertEquals(false, draft.isReadyToSubmit())
    }

    @Test
    fun `submitManualProjectCreation ignores invalid whitespace-only draft`() = runTest {
        val viewModel = ProjectHomeViewModel(repository)

        viewModel.startManualProjectCreation()
        viewModel.updateNewProjectDraft(
            NewProjectDraft(
                name = "   ",
                filesystemPath = "   ",
            )
        )

        viewModel.submitManualProjectCreation()

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(true, state.data.showManualCreateDialog)
        assertEquals("", state.data.newProjectDraft.name)
        assertEquals("", state.data.newProjectDraft.filesystemPath)
    }

    @Test
    fun `startConversationalProjectCreation exposes pending message`() = runTest {
        val viewModel = ProjectHomeViewModel(repository)
        val event = async { viewModel.events.first() }

        viewModel.showCreateProjectOptions()
        viewModel.startConversationalProjectCreation()

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(false, state.data.showCreateOptions)
        assertEquals(
            ProjectHomeUiEvent.ShowMessage("Conversational project setup isn't wired to the registry API yet."),
            event.await(),
        )
    }

    @Test
    fun `startConversationalProjectCreation dismisses manual create dialog`() = runTest {
        val viewModel = ProjectHomeViewModel(repository)
        val event = async { viewModel.events.first() }

        viewModel.startManualProjectCreation()
        viewModel.updateNewProjectDraft(
            NewProjectDraft(
                name = "Graphiti",
                filesystemPath = "/opt/stacks/graphiti",
            )
        )
        viewModel.startConversationalProjectCreation()

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(false, state.data.showManualCreateDialog)
        assertEquals("", state.data.newProjectDraft.name)
        assertEquals(
            ProjectHomeUiEvent.ShowMessage("Conversational project setup isn't wired to the registry API yet."),
            event.await(),
        )
    }

    @Test
    fun `loadProjects degrades to empty success state on repository failure`() = runTest {
        fakeApi.shouldFail = true

        val viewModel = ProjectHomeViewModel(repository)

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        val success = state as UiState.Success
        assertTrue(success.data.projects.isEmpty())
        assertEquals(false, success.data.isRefreshing)
    }

    private fun project(
        identifier: String,
        name: String,
        updatedAt: String? = null,
        filesystemPath: String? = null,
        gitUrl: String? = null,
    ) = ProjectSummary(
        identifier = identifier,
        name = name,
        updatedAt = updatedAt,
        filesystemPath = filesystemPath,
        gitUrl = gitUrl,
        lettaAgentId = "agent-$identifier",
    )

    private class FakeProjectApi : ProjectApi(mockk(relaxed = true)) {
        var projects = mutableListOf<ProjectSummary>()
        var shouldFail = false
        var createShouldFail = false
        var updateShouldFail = false
        val calls = mutableListOf<String>()

        override suspend fun listProjects(): ProjectCatalog {
            calls.add("listProjects")
            if (shouldFail) throw ApiException(500, "Server error")
            return ProjectCatalog(total = projects.size, projects = projects.toList())
        }

        override suspend fun getProject(identifier: String): ProjectSummary {
            calls.add("getProject:$identifier")
            if (shouldFail) throw ApiException(500, "Server error")
            return projects.firstOrNull { it.identifier == identifier }
                ?: throw ApiException(404, "Not found")
        }

        override suspend fun createProject(request: com.letta.mobile.data.api.ProjectCreateRequest): ProjectSummary {
            calls.add("createProject:${request.name}:${request.filesystemPath}:${request.gitUrl}")
            if (createShouldFail) throw ApiException(400, "Create failed")
            val created = ProjectSummary(
                identifier = (request.name ?: "Project").uppercase(),
                name = request.name ?: "Project",
                filesystemPath = request.filesystemPath,
                gitUrl = request.gitUrl,
                lettaAgentId = null,
            )
            projects += created
            return created
        }

        override suspend fun updateProject(
            identifier: String,
            request: com.letta.mobile.data.api.ProjectUpdateRequest,
        ): ProjectSummary {
            calls.add("updateProject:$identifier:${request.filesystemPath}:${request.gitUrl}")
            if (updateShouldFail) throw ApiException(400, "Update failed")
            val index = projects.indexOfFirst { it.identifier == identifier }
            val existing = projects[index]
            val updated = existing.copy(
                filesystemPath = request.filesystemPath ?: existing.filesystemPath,
                gitUrl = request.gitUrl ?: existing.gitUrl,
            )
            projects[index] = updated
            return updated
        }
    }
}
