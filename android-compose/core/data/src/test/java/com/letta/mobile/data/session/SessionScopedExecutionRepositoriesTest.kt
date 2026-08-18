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
    fun `run repository proxy switches caches to rebuilt graph`() {
        val fakeRunApi = FakeRunApi().apply {
            runs = mutableListOf(sampleRun("run-a", "agent-a"))
        }
        assertExecutionProxySwitchesCaches(
            setupGraph = { runApi = fakeRunApi },
            createProxy = { sm, scope -> SessionScopedRunRepository(sm, scope) },
            refresh = { it.refreshRuns(RunListParams()) },
            observeIds = { it.runs.value.map { r -> r.id } },
            mutateForBackendB = {
                fakeRunApi.runs = mutableListOf(sampleRun("run-b", "agent-b"))
            },
            expectedBefore = listOf("run-a"),
            expectedAfter = listOf("run-b"),
        )
    }

    @Test
    fun `job repository proxy switches caches to rebuilt graph`() {
        val fakeJobApi = FakeJobApi().apply {
            jobs = mutableListOf(sampleJob("job-a"))
        }
        assertExecutionProxySwitchesCaches(
            setupGraph = { jobApi = fakeJobApi },
            createProxy = { sm, scope -> SessionScopedJobRepository(sm, scope) },
            refresh = { it.refreshJobs(JobListParams()) },
            observeIds = { it.jobs.value.map { j -> j.id } },
            mutateForBackendB = {
                fakeJobApi.jobs = mutableListOf(sampleJob("job-b"))
            },
            expectedBefore = listOf("job-a"),
            expectedAfter = listOf("job-b"),
        )
    }

    @Test
    fun `step repository proxy switches caches to rebuilt graph`() {
        val fakeStepApi = FakeStepApi().apply {
            steps = mutableListOf(sampleStep("step-a"))
        }
        assertExecutionProxySwitchesCaches(
            setupGraph = { stepApi = fakeStepApi },
            createProxy = { sm, scope -> SessionScopedStepRepository(sm, scope) },
            refresh = { it.refreshSteps(StepListParams()) },
            observeIds = { it.steps.value.map { s -> s.id } },
            mutateForBackendB = {
                fakeStepApi.steps = mutableListOf(sampleStep("step-b"))
            },
            expectedBefore = listOf("step-a"),
            expectedAfter = listOf("step-b"),
        )
    }

    private fun <T, R> assertExecutionProxySwitchesCaches(
        setupGraph: TestSessionGraphFactoryBuilder.() -> Unit,
        createProxy: (SessionManager, CoroutineScope) -> T,
        refresh: suspend (T) -> Unit,
        observeIds: (T) -> List<R>,
        mutateForBackendB: () -> Unit,
        expectedBefore: List<R>,
        expectedAfter: List<R>,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = config("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = createTestSessionGraphFactory(setupGraph),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val proxy = createProxy(sessionManager, CoroutineScope(SupervisorJob() + dispatcher))

        refresh(proxy)
        advanceUntilIdle()
        assertEquals(expectedBefore, observeIds(proxy))

        mutateForBackendB()
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        assertEquals(emptyList<R>(), observeIds(proxy))

        refresh(proxy)
        advanceUntilIdle()
        assertEquals(expectedAfter, observeIds(proxy))
    }

    private fun config(id: String): LettaConfig = sessionTestConfig(id)

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
