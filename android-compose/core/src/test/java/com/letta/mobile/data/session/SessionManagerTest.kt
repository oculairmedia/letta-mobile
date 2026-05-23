package com.letta.mobile.data.session

import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.JobListParams
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.Provider
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
import com.letta.mobile.testutil.FakeProviderApi
import com.letta.mobile.testutil.FakeRunApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.FakeStepApi
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {
    @Test
    fun `active config change rebuilds session graph and cancels previous scope`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
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
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeStepApi(),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        val firstGraph = sessionManager.current
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        val secondGraph = sessionManager.current
        assertNotEquals(firstGraph.id, secondGraph.id)
        assertTrue(firstGraph.scope.coroutineContext.job.isCancelled)
        assertTrue(!secondGraph.scope.coroutineContext.job.isCancelled)
    }

    @Test
    fun `agent repository proxy switches state and calls to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeApi = FakeAgentApi().apply {
            agents = mutableListOf(TestData.agent(id = "agent-a", name = "Backend A Agent"))
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = config("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = SessionGraphFactory(
                fakeApi,
                FakeAgentDao(),
                FakeConversationApi(),
                FakeConversationDao(),
                FakeArchiveApi(),
                FakeFolderApi(),
                FakeGroupApi(),
                FakeIdentityApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeStepApi(),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val proxy = SessionScopedAgentRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        proxy.refreshAgents()
        advanceUntilIdle()
        assertEquals(AgentId("agent-a"), proxy.agents.value.single().id)

        fakeApi.agents = mutableListOf(TestData.agent(id = "agent-b", name = "Backend B Agent"))
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        proxy.refreshAgents()
        advanceUntilIdle()

        assertEquals(AgentId("agent-b"), proxy.agents.value.single().id)
        assertNull(proxy.getCachedAgent("agent-a"))
    }

    @Test
    fun `conversation repository proxy switches caches to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeConversationApi = FakeConversationApi().apply {
            conversations = mutableListOf(TestData.conversation(id = "conv-a", agentId = "agent-1", summary = "Backend A"))
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = config("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = SessionGraphFactory(
                FakeAgentApi(),
                FakeAgentDao(),
                fakeConversationApi,
                FakeConversationDao(),
                FakeArchiveApi(),
                FakeFolderApi(),
                FakeGroupApi(),
                FakeIdentityApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeStepApi(),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val proxy = SessionScopedConversationRepository(sessionManager)

        proxy.refreshConversations("agent-1")
        advanceUntilIdle()
        assertEquals(listOf("conv-a"), proxy.getCachedConversations("agent-1").map { it.id })

        fakeConversationApi.conversations = mutableListOf(
            TestData.conversation(id = "conv-b", agentId = "agent-1", summary = "Backend B"),
        )
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        proxy.refreshConversations("agent-1")
        advanceUntilIdle()

        assertEquals(listOf("conv-b"), proxy.getCachedConversations("agent-1").map { it.id })
    }

    @Test
    fun `archive repository proxy switches caches to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeArchiveApi = FakeArchiveApi().apply {
            archives = mutableListOf(Archive(id = "archive-a", name = "Backend A"))
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = config("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = SessionGraphFactory(
                FakeAgentApi(),
                FakeAgentDao(),
                FakeConversationApi(),
                FakeConversationDao(),
                fakeArchiveApi,
                FakeFolderApi(),
                FakeGroupApi(),
                FakeIdentityApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeStepApi(),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val proxy = SessionScopedArchiveRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        proxy.refreshArchives()
        advanceUntilIdle()
        assertEquals(listOf("archive-a"), proxy.archives.value.map { it.id })

        fakeArchiveApi.archives = mutableListOf(Archive(id = "archive-b", name = "Backend B"))
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), proxy.archives.value.map { it.id })

        proxy.refreshArchives()
        advanceUntilIdle()

        assertEquals(listOf("archive-b"), proxy.archives.value.map { it.id })
    }

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
                fakeRunApi,
                fakeJobApi,
                FakeProviderApi(),
                fakeStepApi,
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

    @Test
    fun `admin repository proxies switch caches to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeFolderApi = FakeFolderApi().apply {
            folders = mutableListOf(Folder(id = "folder-a", name = "Backend A Folder"))
        }
        val fakeGroupApi = FakeGroupApi().apply {
            groups = mutableListOf(sampleGroup("group-a", "Backend A Group"))
        }
        val fakeIdentityApi = FakeIdentityApi().apply {
            identities = mutableListOf(sampleIdentity("identity-a", "Backend A Identity"))
        }
        val fakeProviderApi = FakeProviderApi().apply {
            providers = mutableListOf(sampleProvider("provider-a", "Backend A Provider"))
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
                fakeFolderApi,
                fakeGroupApi,
                fakeIdentityApi,
                FakeRunApi(),
                FakeJobApi(),
                fakeProviderApi,
                FakeStepApi(),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val folderProxy = SessionScopedFolderRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val groupProxy = SessionScopedGroupRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val identityProxy = SessionScopedIdentityRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val providerProxy = SessionScopedProviderRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        folderProxy.refreshFolders()
        groupProxy.refreshGroups()
        identityProxy.refreshIdentities()
        providerProxy.refreshProviders()
        advanceUntilIdle()
        assertEquals(listOf("folder-a"), folderProxy.folders.value.map { it.id })
        assertEquals(listOf("group-a"), groupProxy.groups.value.map { it.id })
        assertEquals(listOf("identity-a"), identityProxy.identities.value.map { it.id })
        assertEquals(listOf("provider-a"), providerProxy.providers.value.map { it.id })

        fakeFolderApi.folders = mutableListOf(Folder(id = "folder-b", name = "Backend B Folder"))
        fakeGroupApi.groups = mutableListOf(sampleGroup("group-b", "Backend B Group"))
        fakeIdentityApi.identities = mutableListOf(sampleIdentity("identity-b", "Backend B Identity"))
        fakeProviderApi.providers = mutableListOf(sampleProvider("provider-b", "Backend B Provider"))
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), folderProxy.folders.value.map { it.id })
        assertEquals(emptyList<String>(), groupProxy.groups.value.map { it.id })
        assertEquals(emptyList<String>(), identityProxy.identities.value.map { it.id })
        assertEquals(emptyList<String>(), providerProxy.providers.value.map { it.id })

        folderProxy.refreshFolders()
        groupProxy.refreshGroups()
        identityProxy.refreshIdentities()
        providerProxy.refreshProviders()
        advanceUntilIdle()

        assertEquals(listOf("folder-b"), folderProxy.folders.value.map { it.id })
        assertEquals(listOf("group-b"), groupProxy.groups.value.map { it.id })
        assertEquals(listOf("identity-b"), identityProxy.identities.value.map { it.id })
        assertEquals(listOf("provider-b"), providerProxy.providers.value.map { it.id })
    }

    private fun config(id: String): LettaConfig = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = "https://$id.example.test",
    )

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

    private fun sampleGroup(id: String, description: String) = Group(
        id = id,
        managerType = "round_robin",
        description = description,
        agentIds = listOf("agent-1"),
    )

    private fun sampleIdentity(id: String, name: String) = Identity(
        id = id,
        identifierKey = id,
        name = name,
        identityType = "user",
    )

    private fun sampleProvider(id: String, name: String) = Provider(
        id = id,
        name = name,
        providerType = "openai",
    )

    private class FakeAgentDao : AgentDao {
        private val agents = MutableStateFlow<List<AgentEntity>>(emptyList())

        override fun getAll(): Flow<List<AgentEntity>> = agents

        override suspend fun getAllOnce(): List<AgentEntity> = agents.value

        override suspend fun insertAll(agents: List<AgentEntity>) {
            this.agents.value = agents
        }

        override suspend fun deleteExcept(keepIds: List<String>) {
            agents.value = agents.value.filter { it.id in keepIds }
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
    }
}
