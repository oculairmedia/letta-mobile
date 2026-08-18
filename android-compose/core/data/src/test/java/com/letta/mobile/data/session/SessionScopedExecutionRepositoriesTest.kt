package com.letta.mobile.data.session

import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.JobListParams
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.RunRequestConfig
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.testutil.FakeJobApi
import com.letta.mobile.testutil.FakeRunApi
import com.letta.mobile.testutil.FakeStepApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionScopedExecutionRepositoriesTest {

    @Test
    fun `run repository proxy switches caches to rebuilt graph`() = runTest {
        val fakeRunApi = FakeRunApi().apply {
            runs = mutableListOf(sampleRun("run-a", "agent-a"))
        }
        assertProxySwitchesCaches(
            testScheduler,
            ProxySwitchScenario(
                setupGraph = { runApi = fakeRunApi },
                createProxy = { sm, scope -> SessionScopedRunRepository(sm, scope) },
                refresh = { it.refreshRuns(RunListParams()) },
                observeIds = { it.runs.value.map { r -> r.id } },
                mutateForBackendB = {
                    fakeRunApi.runs = mutableListOf(sampleRun("run-b", "agent-b"))
                },
                expectedBefore = listOf("run-a"),
                expectedAfter = listOf("run-b"),
            ),
        )
    }

    @Test
    fun `job repository proxy switches caches to rebuilt graph`() = runTest {
        val fakeJobApi = FakeJobApi().apply {
            jobs = mutableListOf(sampleJob("job-a"))
        }
        assertProxySwitchesCaches(
            testScheduler,
            ProxySwitchScenario(
                setupGraph = { jobApi = fakeJobApi },
                createProxy = { sm, scope -> SessionScopedJobRepository(sm, scope) },
                refresh = { it.refreshJobs(JobListParams()) },
                observeIds = { it.jobs.value.map { j -> j.id } },
                mutateForBackendB = {
                    fakeJobApi.jobs = mutableListOf(sampleJob("job-b"))
                },
                expectedBefore = listOf("job-a"),
                expectedAfter = listOf("job-b"),
            ),
        )
    }

    @Test
    fun `step repository proxy switches caches to rebuilt graph`() = runTest {
        val fakeStepApi = FakeStepApi().apply {
            steps = mutableListOf(sampleStep("step-a"))
        }
        assertProxySwitchesCaches(
            testScheduler,
            ProxySwitchScenario(
                setupGraph = { stepApi = fakeStepApi },
                createProxy = { sm, scope -> SessionScopedStepRepository(sm, scope) },
                refresh = { it.refreshSteps(StepListParams()) },
                observeIds = { it.steps.value.map { s -> s.id } },
                mutateForBackendB = {
                    fakeStepApi.steps = mutableListOf(sampleStep("step-b"))
                },
                expectedBefore = listOf("step-a"),
                expectedAfter = listOf("step-b"),
            ),
        )
    }

    private fun sampleRun(id: String, agentId: String) = Run(
        id = id,
        agentId = agentId,
        status = "running",
        background = false,
        requestConfig = RunRequestConfig(useAssistantMessage = true),
    )

    private fun sampleJob(id: String) = Job(
        id = id,
        status = "running",
        agentId = "agent-1",
        jobType = "job",
    )

    private fun sampleStep(id: String) = FakeStepApi().sampleStep(id)
}
