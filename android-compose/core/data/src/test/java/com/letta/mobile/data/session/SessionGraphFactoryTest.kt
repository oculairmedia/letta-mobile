package com.letta.mobile.data.session

import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.api.ArchiveApi
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.api.FolderApi
import com.letta.mobile.data.api.GroupApi
import com.letta.mobile.data.api.IdentityApi
import com.letta.mobile.data.api.JobApi
import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.api.McpServerApi
import com.letta.mobile.data.api.ModelApi
import com.letta.mobile.data.api.PassageApi
import com.letta.mobile.data.api.ProjectApi
import com.letta.mobile.data.api.ProjectWorkApi
import com.letta.mobile.data.api.ProviderApi
import com.letta.mobile.data.api.RunApi
import com.letta.mobile.data.api.ScheduleApi
import com.letta.mobile.data.api.StepApi
import com.letta.mobile.data.api.ToolApi
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeEventOutbox
import com.letta.mobile.runtime.MemFsStore
import com.letta.mobile.runtime.TurnEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SessionGraphFactoryTest {

    private val agentApi: AgentApi = mockk(relaxed = true)
    private val agentDao: AgentDao = mockk(relaxed = true)
    private val conversationApi: ConversationApi = mockk(relaxed = true)
    private val conversationDao: ConversationDao = mockk(relaxed = true)
    private val archiveApi: ArchiveApi = mockk(relaxed = true)
    private val folderApi: FolderApi = mockk(relaxed = true)
    private val groupApi: GroupApi = mockk(relaxed = true)
    private val identityApi: IdentityApi = mockk(relaxed = true)
    private val lettaApiClient: LettaApiClient = mockk(relaxed = true)
    private val mcpServerApi: McpServerApi = mockk(relaxed = true)
    private val modelApi: ModelApi = mockk(relaxed = true)
    private val passageApi: PassageApi = mockk(relaxed = true)
    private val projectApi: ProjectApi = mockk(relaxed = true)
    private val projectWorkApi: ProjectWorkApi = mockk(relaxed = true)
    private val runApi: RunApi = mockk(relaxed = true)
    private val jobApi: JobApi = mockk(relaxed = true)
    private val providerApi: ProviderApi = mockk(relaxed = true)
    private val scheduleApi: ScheduleApi = mockk(relaxed = true)
    private val stepApi: StepApi = mockk(relaxed = true)
    private val toolApi: ToolApi = mockk(relaxed = true)
    private val appContext: android.content.Context = mockk(relaxed = true)

    @Test
    fun `create clears daos and produces remote descriptor by default`() {
        val factory = SessionGraphFactory(
            agentApi = agentApi,
            agentDao = agentDao,
            conversationApi = conversationApi,
            conversationDao = conversationDao,
            archiveApi = archiveApi,
            folderApi = folderApi,
            groupApi = groupApi,
            identityApi = identityApi,
            lettaApiClient = lettaApiClient,
            mcpServerApi = mcpServerApi,
            modelApi = modelApi,
            passageApi = passageApi,
            projectApi = projectApi,
            projectWorkApi = projectWorkApi,
            runApi = runApi,
            jobApi = jobApi,
            providerApi = providerApi,
            scheduleApi = scheduleApi,
            stepApi = stepApi,
            toolApi = toolApi,
            appContext = appContext,
        )

        val graph = factory.create()

        coVerify { agentDao.deleteAll() }
        coVerify { conversationDao.deleteAll() }
        coVerify { conversationDao.deleteAllRefreshStates() }

        assertEquals(BackendKind.RemoteLetta, graph.backendDescriptor.kind)
        assertEquals("remote-letta:default", graph.backendDescriptor.backendId.value)
        assertNull(graph.localRuntimeBackend)
    }

    @Test
    fun `create with remote config uses config for descriptor`() {
        val config = LettaConfig(
            id = "test-remote",
            mode = LettaConfig.Mode.CLOUD,
            serverUrl = "https://test.letta.com"
        )
        val settingsRepository: ISettingsRepository = mockk()
        every { settingsRepository.activeConfig } returns MutableStateFlow(config)

        val factory = SessionGraphFactory(
            agentApi = agentApi,
            agentDao = agentDao,
            conversationApi = conversationApi,
            conversationDao = conversationDao,
            archiveApi = archiveApi,
            folderApi = folderApi,
            groupApi = groupApi,
            identityApi = identityApi,
            lettaApiClient = lettaApiClient,
            mcpServerApi = mcpServerApi,
            modelApi = modelApi,
            passageApi = passageApi,
            projectApi = projectApi,
            projectWorkApi = projectWorkApi,
            runApi = runApi,
            jobApi = jobApi,
            providerApi = providerApi,
            scheduleApi = scheduleApi,
            stepApi = stepApi,
            toolApi = toolApi,
            appContext = appContext,
            settingsRepository = settingsRepository
        )

        val graph = factory.create()

        assertEquals(BackendKind.RemoteLetta, graph.backendDescriptor.kind)
        assertEquals("remote-letta:test-remote", graph.backendDescriptor.backendId.value)
        assertEquals("https://test.letta.com", graph.backendDescriptor.label)
        assertNull(graph.localRuntimeBackend)
    }

    @Test
    fun `create with local config but disabled options uses remote descriptor`() {
        val config = LettaConfig(
            id = "test-local",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "local"
        )
        val settingsRepository: ISettingsRepository = mockk()
        every { settingsRepository.activeConfig } returns MutableStateFlow(config)

        val factory = SessionGraphFactory(
            agentApi = agentApi,
            agentDao = agentDao,
            conversationApi = conversationApi,
            conversationDao = conversationDao,
            archiveApi = archiveApi,
            folderApi = folderApi,
            groupApi = groupApi,
            identityApi = identityApi,
            lettaApiClient = lettaApiClient,
            mcpServerApi = mcpServerApi,
            modelApi = modelApi,
            passageApi = passageApi,
            projectApi = projectApi,
            projectWorkApi = projectWorkApi,
            runApi = runApi,
            jobApi = jobApi,
            providerApi = providerApi,
            scheduleApi = scheduleApi,
            stepApi = stepApi,
            toolApi = toolApi,
            appContext = appContext,
            settingsRepository = settingsRepository,
            localRuntimeOptions = LocalRuntimeOptions.Disabled
        )

        val graph = factory.create()

        assertEquals(BackendKind.RemoteLetta, graph.backendDescriptor.kind)
        assertEquals("remote-letta:test-local", graph.backendDescriptor.backendId.value)
        assertNull(graph.localRuntimeBackend)
    }

    @Test
    fun `create with local config and enabled options creates local backend`() {
        val config = LettaConfig(
            id = "test-local-enabled",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "local"
        )
        val settingsRepository: ISettingsRepository = mockk()
        every { settingsRepository.activeConfig } returns MutableStateFlow(config)

        val provider: LocalRuntimeProvider = mockk()
        every { provider.supports(config) } returns true
        every { provider.priority } returns 1
        every { provider.providerId } returns "test-provider"
        val descriptor = BackendDescriptor(
            backendId = BackendId("local:test"),
            runtimeId = RuntimeId("local:test"),
            kind = BackendKind.LocalKoog,
            label = "local",
            capabilities = mockk(relaxed = true)
        )
        every { provider.descriptor(config) } returns descriptor
        val engine: TurnEngine = mockk()
        every { provider.turnEngine(config) } returns engine
        
        val runtimeEventOutbox: RuntimeEventOutbox = mockk()
        val memFsStore: MemFsStore = mockk()

        val factory = SessionGraphFactory(
            agentApi = agentApi,
            agentDao = agentDao,
            conversationApi = conversationApi,
            conversationDao = conversationDao,
            archiveApi = archiveApi,
            folderApi = folderApi,
            groupApi = groupApi,
            identityApi = identityApi,
            lettaApiClient = lettaApiClient,
            mcpServerApi = mcpServerApi,
            modelApi = modelApi,
            passageApi = passageApi,
            projectApi = projectApi,
            projectWorkApi = projectWorkApi,
            runApi = runApi,
            jobApi = jobApi,
            providerApi = providerApi,
            scheduleApi = scheduleApi,
            stepApi = stepApi,
            toolApi = toolApi,
            appContext = appContext,
            settingsRepository = settingsRepository,
            localRuntimeOptions = LocalRuntimeOptions.Enabled(
                runtimeEventOutbox = runtimeEventOutbox,
                memFsStore = memFsStore,
                providers = setOf(provider)
            )
        )

        val graph = factory.create()

        assertEquals(BackendKind.LocalKoog, graph.backendDescriptor.kind)
        assertEquals("local:test", graph.backendDescriptor.backendId.value)
        assertNotNull(graph.localRuntimeBackend)
    }

    @Test
    fun `create with local config and enabled options falls back if no provider supports`() {
        val config = LettaConfig(
            id = "test-local-unsupported",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "local"
        )
        val settingsRepository: ISettingsRepository = mockk()
        every { settingsRepository.activeConfig } returns MutableStateFlow(config)

        val provider: LocalRuntimeProvider = mockk()
        every { provider.supports(config) } returns false

        val runtimeEventOutbox: RuntimeEventOutbox = mockk()
        val memFsStore: MemFsStore = mockk()

        val factory = SessionGraphFactory(
            agentApi = agentApi,
            agentDao = agentDao,
            conversationApi = conversationApi,
            conversationDao = conversationDao,
            archiveApi = archiveApi,
            folderApi = folderApi,
            groupApi = groupApi,
            identityApi = identityApi,
            lettaApiClient = lettaApiClient,
            mcpServerApi = mcpServerApi,
            modelApi = modelApi,
            passageApi = passageApi,
            projectApi = projectApi,
            projectWorkApi = projectWorkApi,
            runApi = runApi,
            jobApi = jobApi,
            providerApi = providerApi,
            scheduleApi = scheduleApi,
            stepApi = stepApi,
            toolApi = toolApi,
            appContext = appContext,
            settingsRepository = settingsRepository,
            localRuntimeOptions = LocalRuntimeOptions.Enabled(
                runtimeEventOutbox = runtimeEventOutbox,
                memFsStore = memFsStore,
                providers = setOf(provider)
            )
        )

        val graph = factory.create()

        // Should fall back to remoteLettaDescriptor
        assertEquals(BackendKind.RemoteLetta, graph.backendDescriptor.kind)
        assertEquals("remote-letta:test-local-unsupported", graph.backendDescriptor.backendId.value)
        assertNull(graph.localRuntimeBackend)
    }
}
