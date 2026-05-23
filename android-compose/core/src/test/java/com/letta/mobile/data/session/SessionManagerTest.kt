package com.letta.mobile.data.session

import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.JobListParams
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.model.ProjectIssueDetail
import com.letta.mobile.data.model.ProjectIssueListParams
import com.letta.mobile.data.model.ProjectIssueSummary
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.RunRequestConfig
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.SchedulePayload
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
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
import com.letta.mobile.testutil.TestData
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
                fakeLettaApiClient(),
                FakeMcpServerApi(),
                FakeModelApi(),
                FakePassageApi(),
                FakeProjectApi(),
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeScheduleApi(),
                FakeStepApi(),
                FakeToolApi(),
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
                fakeLettaApiClient(),
                FakeMcpServerApi(),
                FakeModelApi(),
                FakePassageApi(),
                FakeProjectApi(),
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeScheduleApi(),
                FakeStepApi(),
                FakeToolApi(),
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
                fakeLettaApiClient(),
                FakeMcpServerApi(),
                FakeModelApi(),
                FakePassageApi(),
                FakeProjectApi(),
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeScheduleApi(),
                FakeStepApi(),
                FakeToolApi(),
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
                fakeLettaApiClient(),
                FakeMcpServerApi(),
                FakeModelApi(),
                FakePassageApi(),
                FakeProjectApi(),
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeScheduleApi(),
                FakeStepApi(),
                FakeToolApi(),
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
                fakeLettaApiClient(),
                FakeMcpServerApi(),
                FakeModelApi(),
                FakePassageApi(),
                FakeProjectApi(),
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                fakeProviderApi,
                FakeScheduleApi(),
                FakeStepApi(),
                FakeToolApi(),
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

    @Test
    fun `model schedule and passage repository proxies switch caches to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeModelApi = FakeModelApi().apply {
            llmModels = mutableListOf(sampleLlmModel("llm-a"))
            embeddingModels = mutableListOf(sampleEmbeddingModel("embedding-a"))
        }
        val fakePassageApi = FakePassageApi().apply {
            setPassages("agent-1", listOf(Passage(id = "passage-a", text = "Backend A", agentId = "agent-1")))
        }
        val fakeScheduleApi = FakeScheduleApi().apply {
            schedules["agent-1"] = mutableListOf(sampleScheduledMessage("schedule-a"))
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
                fakeModelApi,
                fakePassageApi,
                FakeProjectApi(),
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                fakeScheduleApi,
                FakeStepApi(),
                FakeToolApi(),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val modelProxy = SessionScopedModelRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val passageProxy = SessionScopedPassageRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val scheduleProxy = SessionScopedScheduleRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val passages = passageProxy.getPassages("agent-1")

        modelProxy.refreshLlmModels()
        modelProxy.refreshEmbeddingModels()
        passageProxy.refreshPassages("agent-1")
        scheduleProxy.refreshSchedules("agent-1")
        advanceUntilIdle()
        assertEquals(listOf("llm-a"), modelProxy.llmModels.value.map { it.id })
        assertEquals(listOf("embedding-a"), modelProxy.embeddingModels.value.map { it.id })
        assertEquals(listOf("passage-a"), passages.value.map { it.id })
        assertEquals(listOf("schedule-a"), scheduleProxy.getSchedules("agent-1").first().map { it.id })

        fakeModelApi.llmModels = mutableListOf(sampleLlmModel("llm-b"))
        fakeModelApi.embeddingModels = mutableListOf(sampleEmbeddingModel("embedding-b"))
        fakePassageApi.setPassages(
            "agent-1",
            listOf(Passage(id = "passage-b", text = "Backend B", agentId = "agent-1")),
        )
        fakeScheduleApi.schedules["agent-1"] = mutableListOf(sampleScheduledMessage("schedule-b"))
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), modelProxy.llmModels.value.map { it.id })
        assertEquals(emptyList<String>(), modelProxy.embeddingModels.value.map { it.id })
        assertEquals(emptyList<String>(), passages.value.map { it.id })
        assertEquals(emptyList<String>(), scheduleProxy.getSchedules("agent-1").first().map { it.id })

        modelProxy.refreshLlmModels()
        modelProxy.refreshEmbeddingModels()
        passageProxy.refreshPassages("agent-1")
        scheduleProxy.refreshSchedules("agent-1")
        advanceUntilIdle()

        assertEquals(listOf("llm-b"), modelProxy.llmModels.value.map { it.id })
        assertEquals(listOf("embedding-b"), modelProxy.embeddingModels.value.map { it.id })
        assertEquals(listOf("passage-b"), passages.value.map { it.id })
        assertEquals(listOf("schedule-b"), scheduleProxy.getSchedules("agent-1").first().map { it.id })
    }

    @Test
    fun `tool and mcp repository proxies switch caches to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeToolApi = FakeToolApi().apply {
            tools = mutableListOf(sampleTool("tool-a"))
        }
        val fakeMcpServerApi = FakeMcpServerApi().apply {
            servers = mutableListOf(sampleMcpServer("server-a"))
            serverTools["server-a"] = listOf(sampleTool("mcp-tool-a"))
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
                fakeMcpServerApi,
                FakeModelApi(),
                FakePassageApi(),
                FakeProjectApi(),
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeScheduleApi(),
                FakeStepApi(),
                fakeToolApi,
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val toolProxy = SessionScopedToolRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val mcpProxy = SessionScopedMcpServerRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val agentTools = toolProxy.getAgentTools("agent-1")
        val serverTools = mcpProxy.getServerTools("server-a")

        toolProxy.refreshTools()
        toolProxy.attachTool("agent-1", "tool-a")
        mcpProxy.refreshServers()
        mcpProxy.refreshServerTools("server-a")
        advanceUntilIdle()
        assertEquals(listOf("tool-a"), toolProxy.getTools().value.map { it.id.value })
        assertEquals(listOf("tool-a"), agentTools.first().map { it.id.value })
        assertEquals(listOf("server-a"), mcpProxy.servers.value.map { it.id })
        assertEquals(listOf("mcp-tool-a"), serverTools.first().map { it.id.value })

        fakeToolApi.tools = mutableListOf(sampleTool("tool-b"))
        fakeMcpServerApi.servers = mutableListOf(sampleMcpServer("server-b"))
        fakeMcpServerApi.serverTools = mutableMapOf("server-a" to listOf(sampleTool("mcp-tool-b")))
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), toolProxy.getTools().value.map { it.id.value })
        assertEquals(emptyList<String>(), agentTools.first().map { it.id.value })
        assertEquals(emptyList<String>(), mcpProxy.servers.value.map { it.id })
        assertEquals(emptyList<String>(), serverTools.first().map { it.id.value })

        toolProxy.refreshTools()
        toolProxy.attachTool("agent-1", "tool-b")
        mcpProxy.refreshServers()
        mcpProxy.refreshServerTools("server-a")
        advanceUntilIdle()

        assertEquals(listOf("tool-b"), toolProxy.getTools().value.map { it.id.value })
        assertEquals(listOf("tool-b"), agentTools.first().map { it.id.value })
        assertEquals(listOf("server-b"), mcpProxy.servers.value.map { it.id })
        assertEquals(listOf("mcp-tool-b"), serverTools.first().map { it.id.value })
    }

    @Test
    fun `project repository proxy switches cache to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeProjectApi = FakeProjectApi().apply {
            projects = mutableListOf(sampleProject("project-a", "Backend A"))
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
                fakeProjectApi,
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeScheduleApi(),
                FakeStepApi(),
                FakeToolApi(),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val projectProxy = SessionScopedProjectRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        projectProxy.refreshProjects()
        advanceUntilIdle()
        assertEquals(listOf("project-a"), projectProxy.projects.value.map { it.identifier })
        assertTrue(projectProxy.hasFreshProjects(maxAgeMs = 60_000))

        fakeProjectApi.projects = mutableListOf(sampleProject("project-b", "Backend B"))
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), projectProxy.projects.value.map { it.identifier })
        assertTrue(!projectProxy.hasFreshProjects(maxAgeMs = 60_000))

        projectProxy.refreshProjects()
        advanceUntilIdle()

        assertEquals(listOf("project-b"), projectProxy.projects.value.map { it.identifier })
        assertEquals("Backend B", projectProxy.getProject("project-b").name)
    }

    @Test
    fun `all conversations repository proxy switches cache to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeConversationApi = FakeConversationApi().apply {
            conversations = mutableListOf(TestData.conversation(id = "conv-a", summary = "Backend A"))
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
                fakeLettaApiClient(),
                FakeMcpServerApi(),
                FakeModelApi(),
                FakePassageApi(),
                FakeProjectApi(),
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeScheduleApi(),
                FakeStepApi(),
                FakeToolApi(),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val conversationsProxy = SessionScopedAllConversationsRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        conversationsProxy.refresh()
        advanceUntilIdle()
        assertEquals(listOf("conv-a"), conversationsProxy.conversations.value.map { it.id })

        fakeConversationApi.conversations = mutableListOf(TestData.conversation(id = "conv-b", summary = "Backend B"))
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        conversationsProxy.refresh()
        advanceUntilIdle()

        assertEquals(listOf("conv-b"), conversationsProxy.conversations.value.map { it.id })
        assertTrue(conversationsProxy.hasFreshConversations(maxAgeMs = 60_000))
    }

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

    @Test
    fun `transport adjacent state holders are recreated when graph rebuilds`() = runTest {
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
                fakeLettaApiClient(),
                FakeMcpServerApi(),
                FakeModelApi(),
                FakePassageApi(),
                FakeProjectApi(),
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeScheduleApi(),
                FakeStepApi(),
                FakeToolApi(),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        val firstGraph = sessionManager.current
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        val secondGraph = sessionManager.current
        assertNotEquals(System.identityHashCode(firstGraph.channelTransport), System.identityHashCode(secondGraph.channelTransport))
        assertNotEquals(System.identityHashCode(firstGraph.cronRepository), System.identityHashCode(secondGraph.cronRepository))
        assertNotEquals(
            System.identityHashCode(firstGraph.vibesyncEventStreamRepository),
            System.identityHashCode(secondGraph.vibesyncEventStreamRepository),
        )
    }

    @Test
    fun `channel transport proxy switches to rebuilt graph state`() = runTest {
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
                fakeLettaApiClient(),
                FakeMcpServerApi(),
                FakeModelApi(),
                FakePassageApi(),
                FakeProjectApi(),
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeScheduleApi(),
                FakeStepApi(),
                FakeToolApi(),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val proxy = SessionScopedChannelTransport(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        assertTrue(proxy.state.value is com.letta.mobile.data.transport.ChannelTransport.State.Idle)

        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        assertTrue(proxy.state.value is com.letta.mobile.data.transport.ChannelTransport.State.Idle)
    }

    private fun fakeLettaApiClient(): LettaApiClient = mockk(relaxed = true)

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

    private fun sampleLlmModel(id: String) = LlmModel(
        id = id,
        name = id,
        providerType = "openai",
    )

    private fun sampleEmbeddingModel(id: String) = EmbeddingModel(
        id = id,
        name = id,
        providerType = "openai",
    )

    private fun sampleScheduledMessage(id: String) = ScheduledMessage(
        id = id,
        agentId = "agent-1",
        message = SchedulePayload(
            messages = listOf(ScheduleMessage(content = "hello", role = "user")),
        ),
        schedule = ScheduleDefinition(type = "one-time", scheduledAt = 1_700_000_000.0),
    )

    private fun sampleTool(id: String) = Tool(
        id = ToolId(id),
        name = id,
    )

    private fun sampleMcpServer(id: String) = McpServer(
        id = id,
        serverName = id,
    )

    private fun sampleProject(identifier: String, name: String) = ProjectSummary(
        identifier = identifier,
        name = name,
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
