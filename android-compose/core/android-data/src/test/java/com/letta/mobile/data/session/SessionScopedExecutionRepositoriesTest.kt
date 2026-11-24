package com.letta.mobile.data.session

import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.RunRequestConfig
import com.letta.mobile.testutil.FakeRunApi
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
                createProxy = { sm, _ -> sm.sessionScoped(IRunRepository::class) { it.runRepository } },
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

    private fun sampleRun(id: String, agentId: String) = Run(
        id = id,
        agentId = agentId,
        status = "running",
        background = false,
        requestConfig = RunRequestConfig(useAssistantMessage = true),
    )
}
