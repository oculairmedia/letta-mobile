package com.letta.mobile.data.runs

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.RunMetrics
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.runtime.RunId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-ckmub.2.5 (S17): the shared run monitor over an Iroh host and a full host. */
@OptIn(ExperimentalCoroutinesApi::class)
class RunMonitorControllerTest {
    private val running = Run(id = "run-1", agentId = "agent-a", conversationId = "conv-1", status = "running")
    private val failed = Run(id = "run-2", agentId = "agent-b", status = "failed", stopReason = "error")
    private val done = Run(id = "run-3", agentId = "agent-a", status = "completed", totalDurationNs = 4_200_000_000)

    private fun TestScope.controller(repository: FakeRunRepository): RunMonitorController =
        RunMonitorController(repository, backgroundScope).also {
            it.start()
            runCurrent()
        }

    @Test
    fun loadsTheRunsAndCountsThem() = runTest {
        val controller = controller(FakeRunRepository(listOf(running, failed, done)))
        val state = controller.state.value
        assertFalse(state.loading)
        assertEquals(listOf("run-1", "run-2", "run-3"), state.runs.map { it.id })
        assertEquals(RunSummary(total = 3, active = 1, failed = 1), state.summary)
    }

    @Test
    fun theSearchMatchesIdsAgentsAndStates() = runTest {
        val controller = controller(FakeRunRepository(listOf(running, failed, done)))
        controller.setQuery("AGENT-A")
        assertEquals(listOf("run-1", "run-3"), controller.state.value.visibleRuns.map { it.id })
        controller.setQuery("error")
        assertEquals(listOf("run-2"), controller.state.value.visibleRuns.map { it.id })
    }

    @Test
    fun activeOnlyReloadsWithTheActiveFilter() = runTest {
        val repository = FakeRunRepository(listOf(running, done))
        val controller = controller(repository)
        controller.setActiveOnly(true)
        runCurrent()
        assertEquals(true, repository.lastParams?.active)
        assertTrue(controller.state.value.activeOnly)
    }

    @Test
    fun aListThatCannotLoadIsAnError() = runTest {
        val controller = controller(FakeRunRepository(emptyList(), failure = IllegalStateException("host down")))
        val state = controller.state.value
        assertEquals("host down", state.error)
        assertFalse(state.loading)
    }

    @Test
    fun anIrohHostOpensTheRunAndItsStepsOnly() = runTest {
        val repository = FakeRunRepository(listOf(running), supportsDetail = false)
        val controller = controller(repository)
        controller.open("run-1")
        runCurrent()
        val detail = controller.state.value.detail
        assertEquals("run-1", detail?.run?.id)
        assertEquals(listOf("step-1"), detail?.steps?.map { it.id })
        assertFalse(detail?.full ?: true)
        assertNull(detail?.usage)
        assertFalse(controller.canManageRuns)
        controller.delete("run-1")
        runCurrent()
        assertTrue(repository.deleted.isEmpty(), "no delete over Iroh")
    }

    @Test
    fun aFullHostShowsUsageAndDeletesTheOpenRun() = runTest {
        val repository = FakeRunRepository(listOf(running, done), supportsDetail = true)
        val controller = controller(repository)
        controller.open("run-1")
        runCurrent()
        assertEquals(42, controller.state.value.detail?.usage?.totalTokens)
        controller.delete("run-1")
        runCurrent()
        assertEquals(listOf("run-1"), repository.deleted)
        assertNull(controller.state.value.detail)
        assertEquals(listOf("run-3"), controller.state.value.runs.map { it.id })
    }

    @Test
    fun cancellingReplacesTheRun() = runTest {
        val controller = controller(FakeRunRepository(listOf(running), supportsDetail = true))
        controller.cancel("run-1")
        runCurrent()
        assertEquals("cancelled", controller.state.value.runs.single().status)
    }

    @Test
    fun aDetailThatFailsIsAnOperationErrorOverTheList() = runTest {
        val repository = FakeRunRepository(listOf(running), detailFailure = IllegalStateException("gone"))
        val controller = controller(repository)
        controller.open("run-1")
        runCurrent()
        assertEquals("gone", controller.state.value.operationError)
        assertEquals(1, controller.state.value.runs.size)
        controller.clearOperationError()
        assertNull(controller.state.value.operationError)
    }

    private class FakeRunRepository(
        initial: List<Run>,
        private val failure: Throwable? = null,
        private val supportsDetail: Boolean = false,
        private val detailFailure: Throwable? = null,
    ) : IRunRepository {
        private val flow = MutableStateFlow<List<Run>>(emptyList())
        private val stored = initial.toMutableList()
        var lastParams: RunListParams? = null
        val deleted = mutableListOf<String>()

        override val runs: StateFlow<List<Run>> = flow
        override val supportsRunDetail: Boolean get() = supportsDetail

        override suspend fun refreshRuns(params: RunListParams) {
            lastParams = params
            failure?.let { throw it }
            flow.value = stored.toList()
        }

        override suspend fun getRecentRuns(limit: Int): List<Run> = stored.take(limit)
        override suspend fun getRun(runId: RunId): Run {
            detailFailure?.let { throw it }
            return stored.first { it.id == runId.value }
        }
        override suspend fun getRunMessages(runId: RunId): List<LettaMessage> = emptyList()
        override suspend fun getRunUsage(runId: RunId): UsageStatistics = UsageStatistics(totalTokens = 42)
        override suspend fun getRunMetrics(runId: RunId): RunMetrics = RunMetrics(id = runId.value)
        override suspend fun getRunSteps(runId: RunId): List<Step> = listOf(Step(id = "step-1", runId = runId.value, model = "gpt", totalTokens = 7))
        override suspend fun cancelRun(run: Run): Run = run.copy(status = "cancelled")
        override suspend fun deleteRun(runId: RunId) {
            deleted += runId.value
            stored.removeAll { it.id == runId.value }
        }
        override fun upsertRun(run: Run) = Unit
    }
}
