package com.letta.mobile.ui.screens.usage

import com.letta.mobile.data.model.Run
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.RunRepository
import com.letta.mobile.data.repository.StepRepository
import com.letta.mobile.ui.common.UiState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class UsageViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val stepRepository: StepRepository = mockk(relaxed = true)
    private val runRepository: RunRepository = mockk(relaxed = true)
    private val agentRepository: AgentRepository = mockk(relaxed = true)
    private val agentsFlow = MutableStateFlow<List<com.letta.mobile.data.model.Agent>>(emptyList())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { agentRepository.agents } returns agentsFlow
    }

    @Test
    fun loadsAnalyticsOnInit() = runTest(testDispatcher) {
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

        val state = vm.uiState.first()
        assert(state is UiState.Success) {
            "Expected Success, got $state"
        }
    }

    @Test
    fun refreshReloadsData() = runTest(testDispatcher) {
        coEvery { stepRepository.listSteps(any()) } returnsMany listOf(emptyList(), emptyList())
        coEvery { runRepository.getRecentRuns(any()) } returnsMany listOf(emptyList(), emptyList())

        val vm = UsageViewModel(stepRepository, runRepository, agentRepository)
        vm.refresh()

        val state = vm.uiState.first()
        assert(state is UiState.Success) {
            "Expected Success, got $state"
        }
    }

    @Test
    fun clearOperationErrorClearsFromState() = runTest(testDispatcher) {
        coEvery { stepRepository.listSteps(any()) } returns emptyList()
        coEvery { runRepository.getRecentRuns(any()) } returns emptyList()

        val vm = UsageViewModel(stepRepository, runRepository, agentRepository)
        vm.clearOperationError()

        val state = vm.uiState.first() as UiState.Success
        assert(state.data.operationError == null) {
            "Expected null operationError, got ${state.data.operationError}"
        }
    }

    @Test
    fun selectTimeRangeChangesSelectedRange() = runTest(testDispatcher) {
        coEvery { stepRepository.listSteps(any()) } returns emptyList()
        coEvery { runRepository.getRecentRuns(any()) } returns emptyList()

        val vm = UsageViewModel(stepRepository, runRepository, agentRepository)
        vm.selectTimeRange(TimeRange.SEVEN_DAYS)

        val state = vm.uiState.first() as UiState.Success
        assert(state.data.selectedTimeRange == TimeRange.SEVEN_DAYS) {
            "Expected SEVEN_DAYS, got ${state.data.selectedTimeRange}"
        }
    }
}
