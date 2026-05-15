package com.letta.mobile.ui.screens.projects

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ProjectApi
import com.letta.mobile.data.api.ProjectCreateRequest
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ProjectCatalog
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.repository.ProjectRepository
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
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class CreateProjectViewModelTest {

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
    fun `initial state starts at goal step`() = runTest {
        val viewModel = CreateProjectViewModel(repository)

        val state = viewModel.uiState.value
        assertEquals(ConversationalProjectStep.Goal, state.step)
        assertEquals(ConversationalProjectDraft(), state.draft)
        assertEquals(false, state.isSubmitting)
    }

    @Test
    fun `advanceOrSubmit advances through guided steps`() = runTest {
        val viewModel = CreateProjectViewModel(repository)

        viewModel.updateDraft(ConversationalProjectDraft(goal = "Ship a mobile PM surface"))
        viewModel.advanceOrSubmit()

        var state = viewModel.uiState.value
        assertEquals(ConversationalProjectStep.Name, state.step)

        viewModel.updateDraft(state.draft.copy(name = "Letta Mobile"))
        viewModel.advanceOrSubmit()

        state = viewModel.uiState.value
        assertEquals(ConversationalProjectStep.FilesystemPath, state.step)

        viewModel.updateDraft(state.draft.copy(filesystemPath = "/opt/stacks/letta-mobile"))
        viewModel.advanceOrSubmit()

        state = viewModel.uiState.value
        assertEquals(ConversationalProjectStep.GitUrl, state.step)

        viewModel.advanceOrSubmit()

        state = viewModel.uiState.value
        assertEquals(ConversationalProjectStep.Review, state.step)
    }

    @Test
    fun `goBack steps backward before caller should pop`() = runTest {
        val viewModel = CreateProjectViewModel(repository)

        viewModel.updateDraft(ConversationalProjectDraft(goal = "Ship a mobile PM surface"))
        viewModel.advanceOrSubmit()

        assertEquals(true, viewModel.goBack())
        assertEquals(ConversationalProjectStep.Goal, viewModel.uiState.value.step)
        assertEquals(false, viewModel.goBack())
    }

    @Test
    fun `advanceOrSubmit blocks invalid path and stays on path step`() = runTest {
        val viewModel = CreateProjectViewModel(repository)

        viewModel.updateDraft(ConversationalProjectDraft(goal = "Goal"))
        viewModel.advanceOrSubmit()
        viewModel.updateDraft(viewModel.uiState.value.draft.copy(name = "Graphiti"))
        viewModel.advanceOrSubmit()
        viewModel.updateDraft(viewModel.uiState.value.draft.copy(filesystemPath = "relative/path"))

        viewModel.advanceOrSubmit()

        val state = viewModel.uiState.value
        assertEquals(ConversationalProjectStep.FilesystemPath, state.step)
        assertEquals("relative/path", state.draft.filesystemPath)
    }

    @Test
    fun `advanceOrSubmit creates project and emits created event`() = runTest {
        val viewModel = CreateProjectViewModel(repository)
        val event = async { viewModel.events.first() }

        moveToReview(
            viewModel = viewModel,
            draft = ConversationalProjectDraft(
                goal = "  Ship project workflow on mobile  ",
                name = "  Letta Mobile  ",
                filesystemPath = "  /opt/stacks/letta-mobile  ",
                gitUrl = "  https://github.com/example/letta-mobile.git  ",
            )
        )
        assertEquals(ConversationalProjectStep.Review, viewModel.uiState.value.step)

        viewModel.advanceOrSubmit()

        assertTrue(
            fakeApi.calls.contains(
                "createProject:Letta Mobile:/opt/stacks/letta-mobile:https://github.com/example/letta-mobile.git"
            )
        )
        assertEquals(
            CreateProjectEvent.ProjectCreated(name = "Letta Mobile", hadGoal = true),
            event.await(),
        )
    }

    @Test
    fun `advanceOrSubmit exposes creation error and keeps review step`() = runTest {
        fakeApi.createShouldFail = true
        val viewModel = CreateProjectViewModel(repository)
        val event = async { viewModel.events.first() }

        moveToReview(
            viewModel = viewModel,
            draft = ConversationalProjectDraft(
                goal = "Ship project workflow on mobile",
                name = "Letta Mobile",
                filesystemPath = "/opt/stacks/letta-mobile",
            )
        )

        viewModel.advanceOrSubmit()

        val state = viewModel.uiState.value
        assertEquals(ConversationalProjectStep.Review, state.step)
        assertEquals(false, state.isSubmitting)
        assertEquals(CreateProjectEvent.CreationFailed("Create failed"), event.await())
    }

    private fun moveToReview(
        viewModel: CreateProjectViewModel,
        draft: ConversationalProjectDraft,
    ) {
        viewModel.updateDraft(ConversationalProjectDraft(goal = draft.goal))
        viewModel.advanceOrSubmit()
        viewModel.updateDraft(viewModel.uiState.value.draft.copy(name = draft.name))
        viewModel.advanceOrSubmit()
        viewModel.updateDraft(viewModel.uiState.value.draft.copy(filesystemPath = draft.filesystemPath))
        viewModel.advanceOrSubmit()
        viewModel.updateDraft(viewModel.uiState.value.draft.copy(gitUrl = draft.gitUrl))
        viewModel.advanceOrSubmit()
    }

    private class FakeProjectApi : ProjectApi(mockk(relaxed = true)) {
        val projects = mutableListOf<ProjectSummary>()
        var createShouldFail = false
        val calls = mutableListOf<String>()

        override suspend fun listProjects(): ProjectCatalog {
            calls.add("listProjects")
            return ProjectCatalog(total = projects.size, projects = projects.toList())
        }

        override suspend fun createProject(request: ProjectCreateRequest): ProjectSummary {
            calls.add("createProject:${request.name}:${request.filesystemPath}:${request.gitUrl}")
            if (createShouldFail) throw ApiException(400, "Create failed")
            val created = ProjectSummary(
                identifier = (request.name ?: "Project").uppercase(),
                name = request.name ?: "Project",
                filesystemPath = request.filesystemPath,
                gitUrl = request.gitUrl,
                lettaAgentId = AgentId("agent-${request.name ?: "project"}"),
            )
            projects += created
            return created
        }
    }
}
