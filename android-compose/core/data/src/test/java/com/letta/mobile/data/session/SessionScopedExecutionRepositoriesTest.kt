package com.letta.mobile.data.session

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.JobListParams
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.RunRequestConfig
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeArchiveApi
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeFolderApi
import com.letta.mobile.testutil.FakeGroupApi
import com.letta.mobile.testutil.FakeIdentityApi
import com.letta.mobile.testutil.FakeJobApi
import com.letta.mobile.testutil.FakeMcpServerApi
import com.letta.mobile.testutil.FakeModelApi
import com.letta.mobile.testutil.FakePassageApi
import com.letta.mobile.testutil.FakeProjectApi
import com.letta.mobile.testutil.FakeProjectWorkApi
import com.letta.mobile.testutil.FakeProviderApi
import com.letta.mobile.testutil.FakeRunApi
import com.letta.mobile.testutil.FakeScheduleApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.FakeStepApi
import com.letta.mobile.testutil.FakeToolApi
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionScopedExecutionRepositoriesTest {

    @Test
    fun `run job and step repository proxies switch caches to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeRunApi = FakeRunApi().apply {
            runs = mutableListOf(sampleRun("run-a", "agent-a"))
        }
        val fakeJobApi = FakeJobApi().apply {
            jobs = mutableListOf(sampleJob("job-a"))
        }
        val fakeStepApi = FakeStepApi().apply {
            steps = mutableListOf(sampleStep("step-a"))
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = config("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = SessionGraphFactory(
                FakeAgentApi(),
                FakeAgentDao(),
                FakeConversationApi(),
                FakeConversationDao(),
                FakeArchiveApi(),
                FakeFolderApi(),
                FakeGroupApi(),
                FakeIdentityApi(),
                fakeLettaApiClient(),
                FakeMcpServerApi(),
                FakeModelApi(),
                FakePassageApi(),
                FakeProjectApi(),
                FakeProjectWorkApi(),
                fakeRunApi,
                fakeJobApi,
                FakeProviderApi(),
                FakeScheduleApi(),
                fakeStepApi,
                FakeToolApi(),
                appContext = mockk(relaxed = true),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val runProxy = SessionScopedRunRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val jobProxy = SessionScopedJobRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val stepProxy = SessionScopedStepRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        runProxy.refreshRuns(RunListParams())
        jobProxy.refreshJobs(JobListParams())
        stepProxy.refreshSteps(StepListParams())
        advanceUntilIdle()
        assertEquals(listOf("run-a"), runProxy.runs.value.map { it.id })
        assertEquals(listOf("job-a"), jobProxy.jobs.value.map { it.id })
        assertEquals(listOf("step-a"), stepProxy.steps.value.map { it.id })

        fakeRunApi.runs = mutableListOf(sampleRun("run-b", "agent-b"))
        fakeJobApi.jobs = mutableListOf(sampleJob("job-b"))
        fakeStepApi.steps = mutableListOf(sampleStep("step-b"))
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), runProxy.runs.value.map { it.id })
        assertEquals(emptyList<String>(), jobProxy.jobs.value.map { it.id })
        assertEquals(emptyList<String>(), stepProxy.steps.value.map { it.id })

        runProxy.refreshRuns(RunListParams())
        jobProxy.refreshJobs(JobListParams())
        stepProxy.refreshSteps(StepListParams())
        advanceUntilIdle()

        assertEquals(listOf("run-b"), runProxy.runs.value.map { it.id })
        assertEquals(listOf("job-b"), jobProxy.jobs.value.map { it.id })
        assertEquals(listOf("step-b"), stepProxy.steps.value.map { it.id })
    }

    private fun config(id: String, serverUrl: String = "https://$id.example.test"): LettaConfig = sessionTestConfig(id, serverUrl)

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
