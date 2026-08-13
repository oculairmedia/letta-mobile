package com.letta.mobile.ui.screens.usage

import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.RunRepository
import com.letta.mobile.data.repository.StepRepository
import com.letta.mobile.ui.common.UiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import com.letta.mobile.testutil.MainDispatcherRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class UsageViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val stepRepository: StepRepository = mockk(relaxed = true)
    private val runRepository: RunRepository = mockk(relaxed = true)
    private val agentRepository: AgentRepository = mockk(relaxed = true)
    private val agentsFlow = MutableStateFlow<List<com.letta.mobile.data.model.Agent>>(emptyList())

    @Before
    fun setup() {
        every { agentRepository.agents } returns agentsFlow
    }

    @Test
    fun loadsAnalyticsOnInit() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { stepRepository.listSteps(any()) } returns emptyList()
        coEvery { runRepository.getRecentRuns(any()) } returns listOf(
            Run(
                id = "run-1",
                agentId = "agent-1",
                status = "completed",
                createdAt = java.time.Instant.now().toString(),
            )
        )

        val vm = UsageViewModel(stepRepository, runRepository, agentRepository)
        advanceUntilIdle()

        val state = vm.uiState.first()
        assert(state is UiState.Success) {
            "Expected Success, got $state"
        }
    }

    @Test
    fun refreshReloadsData() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { stepRepository.listSteps(any()) } returnsMany listOf(emptyList(), emptyList())
        coEvery { runRepository.getRecentRuns(any()) } returnsMany listOf(emptyList(), emptyList())

        val vm = UsageViewModel(stepRepository, runRepository, agentRepository)
        vm.refresh()
        advanceUntilIdle()

        val state = vm.uiState.first()
        assert(state is UiState.Success) {
            "Expected Success, got $state"
        }
    }

    @Test
    fun clearOperationErrorClearsFromState() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { stepRepository.listSteps(any()) } returns emptyList()
        coEvery { runRepository.getRecentRuns(any()) } returns emptyList()

        val vm = UsageViewModel(stepRepository, runRepository, agentRepository)
        vm.clearOperationError()
        advanceUntilIdle()

        val state = vm.uiState.first() as UiState.Success
        assert(state.data.operationError == null) {
            "Expected null operationError, got ${state.data.operationError}"
        }
    }

    @Test
    fun selectTimeRangeChangesSelectedRange() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { stepRepository.listSteps(any()) } returns emptyList()
        coEvery { runRepository.getRecentRuns(any()) } returns emptyList()

        val vm = UsageViewModel(stepRepository, runRepository, agentRepository)
        advanceUntilIdle()
        vm.selectTimeRange(TimeRange.SEVEN_DAYS)
        advanceUntilIdle()

        val state = vm.uiState.first() as UiState.Success
        assert(state.data.selectedTimeRange == TimeRange.SEVEN_DAYS) {
            "Expected SEVEN_DAYS, got ${state.data.selectedTimeRange}"
        }
    }

    @Test
    fun stepsFailureDegradesGracefullyToSuccessWithRuns() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { stepRepository.listSteps(any()) } throws IllegalStateException("steps unavailable")
        coEvery { runRepository.getRecentRuns(any()) } returns listOf(
            Run(
                id = "run-1",
                agentId = "agent-1",
                status = "completed",
                createdAt = Instant.now().toString(),
            ),
        )

        val vm = UsageViewModel(stepRepository, runRepository, agentRepository)
        advanceUntilIdle()

        val state = vm.uiState.first()
        assert(state is UiState.Success) { "Expected success degradation path, got $state" }
        state as UiState.Success
        assert(state.data.recentRuns.size == 1) { "Expected 1 run summary" }
    }

    @Test
    fun malformedRunTimestampIsExcludedFromRecentRuns() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { stepRepository.listSteps(any()) } returns emptyList()
        coEvery { runRepository.getRecentRuns(any()) } returns listOf(
            Run(id = "bad-run", agentId = "agent-1", createdAt = "not-a-date"),
            Run(id = "good-run", agentId = "agent-1", createdAt = Instant.now().toString()),
        )

        val vm = UsageViewModel(stepRepository, runRepository, agentRepository)
        advanceUntilIdle()

        val state = vm.uiState.first() as UiState.Success
        assert(state.data.recentRuns.size == 1) { "Expected malformed timestamp run to be excluded" }
        assert(state.data.recentRuns.first().id == "good-run")
    }

    @Test
    fun runSummaryFallsBackToAgentIdPrefixWhenAgentNameMissing() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { stepRepository.listSteps(any()) } returns emptyList()
        coEvery { runRepository.getRecentRuns(any()) } returns listOf(
            Run(id = "run-1", agentId = "agent-abcdefgh", createdAt = Instant.now().toString()),
        )
        agentsFlow.value = emptyList()

        val vm = UsageViewModel(stepRepository, runRepository, agentRepository)
        advanceUntilIdle()

        val state = vm.uiState.first() as UiState.Success
        assert(state.data.recentRuns.first().agentName == "agent-ab")
    }

    @Test
    fun runSummaryTransformsModelTokensDurationAndErrorFlags() = runTest(mainDispatcherRule.dispatcher) {
        val run = Run(
            id = "run-1",
            agentId = "agent-1",
            status = "completed",
            stopReason = "error",
            totalDurationNs = 2_000_000_000,
            createdAt = Instant.now().toString(),
        )
        val steps = listOf(
            Step(
                id = "step-1",
                runId = "run-1",
                model = "gpt-4o",
                promptTokens = 100,
                completionTokens = 50,
            ),
        )
        coEvery { stepRepository.listSteps(any()) } returns steps
        coEvery { runRepository.getRecentRuns(any()) } returns listOf(run)

        val vm = UsageViewModel(stepRepository, runRepository, agentRepository)
        advanceUntilIdle()

        val summary = (vm.uiState.first() as UiState.Success).data.recentRuns.first()
        assert(summary.model == "gpt-4o")
        assert(summary.totalTokens == 150)
        assert(summary.durationMs == 2000L)
        assert(summary.hasError)
    }

    @Test
    fun loadDataUsesExpectedRepositoryParameters() = runTest(mainDispatcherRule.dispatcher) {
        val paramsSlot = slot<com.letta.mobile.data.model.StepListParams>()
        coEvery { stepRepository.listSteps(capture(paramsSlot)) } returns emptyList()
        coEvery { runRepository.getRecentRuns(any()) } returns emptyList()

        val vm = UsageViewModel(stepRepository, runRepository, agentRepository)
        advanceUntilIdle()
        vm.selectTimeRange(TimeRange.TWENTY_FOUR_HOURS)
        advanceUntilIdle()

        coVerify(atLeast = 1) { runRepository.getRecentRuns(200) }
        assert(paramsSlot.isCaptured)
        assert(paramsSlot.captured.limit == com.letta.mobile.data.repository.PaginationConstants.DEFAULT_PAGE_SIZE)
        assert(!paramsSlot.captured.startDate.isNullOrBlank())
        assert(!paramsSlot.captured.endDate.isNullOrBlank())
    }
}
