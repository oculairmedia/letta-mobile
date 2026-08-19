package com.letta.mobile.data.session

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ProjectIssueDetail
import com.letta.mobile.data.model.ProjectIssueListParams
import com.letta.mobile.data.model.ProjectIssueSummary
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
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)

// letta-mobile-g2ff0: tests must wrap DAOs in dagger.Lazy because
// production constructor now takes Lazy<AgentDao> / Lazy<ConversationDao>.
private fun <T> lazyOf(value: T): dagger.Lazy<T> = dagger.Lazy { value }
class SessionScopedProjectWorkRepositoryTest {

    @Test
    fun `project work repository proxy switches caches to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeProjectWorkApi = FakeProjectWorkApi().apply {
            readyWork["letta-mobile"] = listOf(sampleIssue("letta-mobile-a"))
            issues["letta-mobile"] = listOf(sampleIssue("letta-mobile-a"))
            issueDetails["letta-mobile-a"] = sampleIssueDetail("letta-mobile-a", "Backend A")
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = createDefaultSessionRepositoryGraphFactory(
                FakeAgentApi(),
                lazyOf(FakeAgentDao()),
                FakeConversationApi(),
                lazyOf(FakeConversationDao()),
                FakeArchiveApi(),
                FakeFolderApi(),
                FakeGroupApi(),
                FakeIdentityApi(),
                fakeLettaApiClient(),
                FakeMcpServerApi(),
                FakeModelApi(),
                FakePassageApi(),
                FakeProjectApi(),
                fakeProjectWorkApi,
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeScheduleApi(),
                FakeStepApi(),
                FakeToolApi(),
                appContext = mockk(relaxed = true),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val workProxy = SessionScopedProjectWorkRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        workProxy.refreshReadyWork("letta-mobile")
        workProxy.refreshIssues("letta-mobile", ProjectIssueListParams())
        assertEquals("Backend A", workProxy.getIssue("letta-mobile-a").title)
        advanceUntilIdle()
        assertEquals(listOf("letta-mobile-a"), workProxy.readyWorkByProject.value["letta-mobile"]?.map { it.id })
        assertEquals(listOf("letta-mobile-a"), workProxy.issuesByProject.value["letta-mobile"]?.map { it.id })

        fakeProjectWorkApi.readyWork["letta-mobile"] = listOf(sampleIssue("letta-mobile-b"))
        fakeProjectWorkApi.issues["letta-mobile"] = listOf(sampleIssue("letta-mobile-b"))
        fakeProjectWorkApi.issueDetails.clear()
        fakeProjectWorkApi.issueDetails["letta-mobile-b"] = sampleIssueDetail("letta-mobile-b", "Backend B")
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        advanceUntilIdle()

        workProxy.refreshReadyWork("letta-mobile")
        workProxy.refreshIssues("letta-mobile", ProjectIssueListParams())
        val backendBIssue = workProxy.getIssue("letta-mobile-b")
        advanceUntilIdle()

        assertEquals(listOf("letta-mobile-b"), workProxy.readyWorkByProject.value["letta-mobile"]?.map { it.id })
        assertEquals(listOf("letta-mobile-b"), workProxy.issuesByProject.value["letta-mobile"]?.map { it.id })
        assertEquals("Backend B", backendBIssue.title)
        assertNull(workProxy.issueDetails.value["letta-mobile-a"])
    }

    private fun sampleIssue(id: String) = ProjectIssueSummary(
        id = id,
        projectId = "letta-mobile",
        provider = "beads",
        title = "Issue $id",
        type = "task",
        priority = "high",
        status = "open",
        ready = true,
        etag = "$id:1",
    )

    private fun sampleIssueDetail(id: String, title: String) = ProjectIssueDetail(
        id = id,
        projectId = "letta-mobile",
        title = title,
        status = "open",
        description = "Description for $id",
    )
}
