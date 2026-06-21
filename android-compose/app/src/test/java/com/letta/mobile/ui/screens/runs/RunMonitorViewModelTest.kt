package com.letta.mobile.ui.screens.runs

import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunRequestConfig
import com.letta.mobile.data.repository.RunRepository
import com.letta.mobile.data.repository.StepRepository
import com.letta.mobile.testutil.FakeRunApi
import com.letta.mobile.testutil.FakeStepApi
import com.letta.mobile.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class RunMonitorViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeApi: FakeRunApi
    private lateinit var fakeStepApi: FakeStepApi
    private lateinit var repository: RunRepository
    private lateinit var stepRepository: StepRepository
    private lateinit var viewModel: RunMonitorViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeApi = FakeRunApi()
        fakeStepApi = FakeStepApi()
        fakeApi.runs.addAll(
            listOf(
                sampleRun("r1", "running"),
                sampleRun("r2", "cancelled"),
            )
        )
        repository = RunRepository(fakeApi)
        stepRepository = StepRepository(fakeStepApi)
        viewModel = RunMonitorViewModel(repository, stepRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadRuns populates state`() = runTest {
        viewModel.loadRuns()

        val state = awaitSuccessState()
        assertEquals(2, state.runs.size)
    }

    @Test
    fun `toggleActiveOnly filters runs through repository`() = runTest {
        viewModel.toggleActiveOnly(true)

        val state = awaitSuccessState()
        assertTrue(state.activeOnly)
        assertEquals(1, state.runs.size)
        assertEquals("running", state.runs.first().status)
    }

    @Test
    fun `updateSearchQuery filters runs locally`() = runTest {
        viewModel.loadRuns()
        viewModel.updateSearchQuery("cancelled")

        val filtered = viewModel.getFilteredRuns()
        assertEquals(1, filtered.size)
        assertEquals("r2", filtered.first().id)
    }

    @Test
    fun `inspectRun loads detailed run`() = runTest {
        awaitSuccessState()
        viewModel.inspectRun("r1")

        val state = awaitSuccessState()
        assertEquals("r1", state.selectedRun?.id)
        assertEquals(1, state.selectedRunMessages.size)
        assertEquals(1, state.selectedRunSteps.size)
        assertEquals(30, state.selectedRunUsage?.totalTokens)
        assertEquals("r1", state.selectedRunMetrics?.id)
        assertEquals("org-1", state.selectedRunMetrics?.organizationId)
        assertEquals("provider-1", state.selectedRunSteps.first().providerId)
        assertEquals("positive", state.selectedRunSteps.first().feedback)
    }

    @Test
    fun `inspectStep loads metrics trace and messages`() = runTest {
        awaitSuccessState()
        viewModel.inspectRun("r1")
        awaitSuccessState()

        viewModel.inspectStep("step-1")

        val state = awaitSuccessState()
        assertEquals("step-1", state.selectedStep?.id)
        assertEquals(1, state.selectedStepMessages.size)
        assertEquals(500L, state.selectedStepMetrics?.stepNs)
        assertEquals("step-1", state.selectedStepTrace?.stepId)
    }

    @Test
    fun `updateStepFeedback refreshes selected step`() = runTest {
        awaitSuccessState()
        viewModel.inspectRun("r1")
        awaitSuccessState()
        viewModel.inspectStep("step-1")
        awaitSuccessState()

        viewModel.updateStepFeedback("step-1", "negative")

        val state = awaitSuccessState()
        assertEquals("negative", state.selectedStep?.feedback)
        assertEquals("negative", state.selectedRunSteps.first().feedback)
    }

    @Test
    fun `cancelRun updates selected run state`() = runTest {
        awaitSuccessState()
        viewModel.inspectRun("r1")
        awaitSuccessState()

        viewModel.cancelRun("r1")

        val state = awaitSuccessState()
        assertEquals("cancelled", state.selectedRun?.status)
    }

    @Test
    fun `cancelRun removes cancelled run from active-only list`() = runTest {
        viewModel.toggleActiveOnly(true)
        awaitSuccessState()
        viewModel.inspectRun("r1")
        awaitSuccessState()

        viewModel.cancelRun("r1")

        val state = awaitSuccessState()
        assertTrue(state.runs.isEmpty())
        assertEquals(null, state.selectedRun)
    }

    @Test
    fun `deleteRun removes run from list`() = runTest {
        awaitSuccessState()
        viewModel.deleteRun("r1")

        val state = awaitSuccessState()
        assertEquals(1, state.runs.size)
        assertEquals("r2", state.runs.first().id)
    }

    private suspend fun awaitSuccessState(): RunMonitorUiState {
        return viewModel.uiState.first { it is UiState.Success }.let { (it as UiState.Success).data }
    }
}

private fun sampleRun(id: String, status: String) = Run(
    id = id,
    agentId = "agent-1",
    conversationId = "conv-1",
    status = status,
    background = status == "running",
    stopReason = if (status == "cancelled") "cancelled" else null,
    createdAt = "2026-04-09T10:00:00Z",
    requestConfig = RunRequestConfig(useAssistantMessage = true),
)
