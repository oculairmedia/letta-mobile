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
class SessionScopedProjectWorkRepositoryTest {

    @Test
    fun `project work repository proxy switches caches to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeProjectWorkApi = FakeProjectWorkApi().apply {
            readyWork["letta-mobile"] = listOf(sampleIssue("letta-mobile-a"))
            issues["letta-mobile"] = listOf(sampleIssue("letta-mobile-a"))
            issueDetails["letta-mobile-a"] = sampleIssueDetail("letta-mobile-a", "Backend A")
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
        settingsRepository.activeConfigState.value = config("backend-b")
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

    private fun fakeLettaApiClient(): LettaApiClient = mockk(relaxed = true)

    private fun config(id: String, serverUrl: String = "https://$id.example.test"): LettaConfig = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = serverUrl,
    )

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

    private class FakeAgentDao : AgentDao {
        private val agents = MutableStateFlow<List<AgentEntity>>(emptyList())

        override fun getAll(): Flow<List<AgentEntity>> = agents

        override suspend fun getAllOnce(): List<AgentEntity> = agents.value

        override suspend fun insertAll(agents: List<AgentEntity>) {
            this.agents.value = agents
        }

        override suspend fun upsert(agent: AgentEntity) {
            agents.value = agents.value.filterNot { it.id == agent.id } + agent
        }

        override suspend fun deleteExcept(keepIds: List<String>) {
            agents.value = agents.value.filter { it.id in keepIds }
        }

        override suspend fun deleteById(id: String) {
            agents.value = agents.value.filterNot { it.id == id }
        }

        override suspend fun deleteAll() {
            agents.value = emptyList()
        }
    }

    private class FakeConversationDao : ConversationDao {
        private val conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
        private val refreshStates = mutableMapOf<String, ConversationRefreshEntity>()

        override fun observeForAgent(agentId: String): Flow<List<ConversationEntity>> =
            conversations.map { rows -> rows.filter { it.agentId == agentId } }

        override suspend fun getForAgentOnce(agentId: String): List<ConversationEntity> =
            conversations.value.filter { it.agentId == agentId }

        override suspend fun getAllOnce(): List<ConversationEntity> = conversations.value

        override suspend fun getByIdOnce(conversationId: String): ConversationEntity? =
            conversations.value.firstOrNull { it.id == conversationId }

        override suspend fun upsert(conversation: ConversationEntity) {
            conversations.value = conversations.value.filterNot { it.id == conversation.id } + conversation
        }

        override suspend fun upsertAll(conversations: List<ConversationEntity>) {
            conversations.forEach { upsert(it) }
        }

        override suspend fun delete(conversationId: String) {
            conversations.value = conversations.value.filterNot { it.id == conversationId }
        }

        override suspend fun deleteForAgent(agentId: String) {
            conversations.value = conversations.value.filterNot { it.agentId == agentId }
        }

        override suspend fun deleteForAgentExcept(agentId: String, keepIds: List<String>) {
            conversations.value = conversations.value.filterNot { it.agentId == agentId && it.id !in keepIds }
        }

        override suspend fun getRefreshState(agentId: String): ConversationRefreshEntity? = refreshStates[agentId]

        override suspend fun getAllRefreshStatesOnce(): List<ConversationRefreshEntity> = refreshStates.values.toList()

        override suspend fun upsertRefreshState(state: ConversationRefreshEntity) {
            refreshStates[state.agentId] = state
        }

        override suspend fun deleteAll() {
            conversations.value = emptyList()
        }

        override suspend fun deleteAllRefreshStates() {
            refreshStates.clear()
        }
    }
}
