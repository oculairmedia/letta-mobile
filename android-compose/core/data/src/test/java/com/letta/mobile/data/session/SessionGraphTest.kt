package com.letta.mobile.data.session

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.EpochMillis
import com.letta.mobile.runtime.InMemoryMemFsStore
import com.letta.mobile.runtime.InMemoryRuntimeEventOutbox
import com.letta.mobile.runtime.MemFsCommitId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnEngine
import com.letta.mobile.runtime.TurnInput
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionGraphTest {

    @Test
    fun `session graph exposes shared backend descriptor from active config`() = runTest {
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = config("backend-a"))
        val graph = SessionGraphFactory(
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
            appContext = mockk(relaxed = true),
            settingsRepository = settingsRepository,
        ).create()

        assertEquals(BackendKind.RemoteLetta, graph.backendDescriptor.kind)
        assertEquals("remote-letta:backend-a", graph.backendDescriptor.backendId.value)
        assertEquals("remote-letta:backend-a", graph.backendDescriptor.runtimeId.value)
        assertEquals("https://backend-a.example.test", graph.backendDescriptor.label)
        assertTrue(graph.backendDescriptor.capabilities.supportsMemFs)
        assertTrue(graph.backendDescriptor.capabilities.supportsApprovals)
        assertTrue(graph.channelTransport is ChannelTransport)
    }

    @Test
    fun `session graph satisfies shared repository graph contract`() = runTest {
        val graph = SessionGraphFactory(
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
            appContext = mockk(relaxed = true),
        ).create()

        val sharedGraph: SessionRepositoryGraph = graph

        assertEquals(graph.backendDescriptor, sharedGraph.backendDescriptor)
        assertEquals(graph.agentRepository, sharedGraph.agentRepository)
        assertEquals(graph.channelTransport, sharedGraph.channelTransport)
        assertEquals(graph.folderRepository, sharedGraph.folderRepository)
        assertEquals(graph.groupRepository, sharedGraph.groupRepository)
    }

    @Test
    fun `session graph can select local runtime backend behind internal option`() = runTest {
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = localConfig("backend-a"))
        val graph = SessionGraphFactory(
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
            appContext = mockk(relaxed = true),
            settingsRepository = settingsRepository,
            localRuntimeOptions = localRuntimeOptions(),
        ).create()

        assertEquals(BackendKind.LocalLettaCode, graph.backendDescriptor.kind)
        assertEquals("local-lettacode:backend-a", graph.backendDescriptor.backendId.value)
        assertEquals("local-lettacode:backend-a", graph.backendDescriptor.runtimeId.value)
        assertEquals("Local LettaCode", graph.backendDescriptor.label)
        assertTrue(graph.backendDescriptor.capabilities.supportsMemFs)
        assertTrue(graph.backendDescriptor.capabilities.supportsTools)
        assertTrue(graph.channelTransport is NoOpChannelTransport)

        val backend = graph.localRuntimeBackend ?: error("Expected local runtime backend")
        val emitted = backend.runTurn(
            TurnCommand(
                backendId = graph.backendDescriptor.backendId,
                runtimeId = graph.backendDescriptor.runtimeId,
                agentId = AgentId("agent-1"),
                conversationId = ConversationId("conv-1"),
                input = TurnInput.UserMessage(
                    localMessageId = "local-1",
                    text = "hello local",
                ),
            )
        ).toList()

        assertEquals(3, emitted.size)
        val completed = emitted.last().payload as RuntimeEventPayload.RunLifecycleChanged
        assertEquals(RuntimeRunStatus.Completed, completed.status)
    }

    @Test
    fun `session graph can select explicit koog provider alongside lettacode provider`() = runTest {
        val settingsRepository = FakeSettingsRepository(
            initialActiveConfig = localConfig("backend-a", serverUrl = "local-koog://device"),
        )
        val graph = SessionGraphFactory(
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
            appContext = mockk(relaxed = true),
            settingsRepository = settingsRepository,
            localRuntimeOptions = localRuntimeOptions(
                localRuntimeProvider(),
                localRuntimeProvider(
                    providerId = "local-koog",
                    scheme = "local-koog",
                    kind = BackendKind.LocalKoog,
                    label = "Local Koog runtime",
                    supportsTools = false,
                    supportsApprovals = false,
                    priority = 10,
                ),
            ),
        ).create()

        assertEquals(BackendKind.LocalKoog, graph.backendDescriptor.kind)
        assertEquals("local-koog:backend-a", graph.backendDescriptor.backendId.value)
        assertEquals("Local Koog runtime", graph.backendDescriptor.label)
        assertFalse(graph.backendDescriptor.capabilities.supportsTools)
    }

    @Test
    fun `session graph keeps remote backend for non-local config when local runtime is available`() = runTest {
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = config("backend-a"))
        val graph = SessionGraphFactory(
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
            appContext = mockk(relaxed = true),
            settingsRepository = settingsRepository,
            localRuntimeOptions = localRuntimeOptions(),
        ).create()

        assertEquals(BackendKind.RemoteLetta, graph.backendDescriptor.kind)
        assertEquals("remote-letta:backend-a", graph.backendDescriptor.backendId.value)
        assertNull(graph.localRuntimeBackend)
    }

    private fun fakeLettaApiClient(): LettaApiClient = mockk(relaxed = true)

    private fun localRuntimeOptions(
        vararg providers: LocalRuntimeProvider = arrayOf(localRuntimeProvider()),
    ): LocalRuntimeOptions = LocalRuntimeOptions.Enabled(
        runtimeEventOutbox = InMemoryRuntimeEventOutbox(
            eventIdFactory = { _, offset -> RuntimeEventId("local-event-${offset.value}") },
            clock = { EpochMillis(1_000) },
        ),
        memFsStore = InMemoryMemFsStore(
            commitIdFactory = { path, revision, operation ->
                MemFsCommitId("${operation.name.lowercase()}-${path.value}-${revision.value}")
            },
            clock = { EpochMillis(1_000) },
        ),
        providers = providers.toSet(),
    )

    private fun localRuntimeProvider(
        providerId: String = "local-lettacode",
        scheme: String = "local",
        kind: BackendKind = BackendKind.LocalLettaCode,
        label: String = "Local LettaCode",
        supportsTools: Boolean = true,
        supportsApprovals: Boolean = supportsTools,
        priority: Int = 100,
    ): LocalRuntimeProvider = object : LocalRuntimeProvider {
        override val providerId: String = providerId
        override val priority: Int = priority

        override fun supports(config: LettaConfig): Boolean =
            config.serverUrl.startsWith("$scheme://")

        override fun descriptor(config: LettaConfig): BackendDescriptor {
            val backendKey = config.id.takeIf { it.isNotBlank() } ?: "device"
            return BackendDescriptor(
                backendId = BackendId("$providerId:$backendKey"),
                runtimeId = RuntimeId("$providerId:$backendKey"),
                kind = kind,
                label = label,
                capabilities = BackendCapabilities(
                    supportsStreaming = true,
                    supportsMemFs = true,
                    supportsToolEvents = supportsTools,
                    supportsToolExecution = supportsTools,
                    supportsApprovals = supportsApprovals,
                    supportsAgentFileImport = false,
                    supportsAgentFileExport = false,
                ),
            )
        }

        override fun turnEngine(config: LettaConfig): TurnEngine = TurnEngine {
            flowOf(
                RuntimeEventDraft(
                    backendId = it.backendId,
                    runtimeId = it.runtimeId,
                    agentId = it.agentId,
                    conversationId = it.conversationId,
                    source = RuntimeEventSource.LocalRuntime,
                    payload = RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Completed),
                ),
            )
        }
    }

    private fun config(id: String, serverUrl: String = "https://$id.example.test"): LettaConfig = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = serverUrl,
    )

    private fun localConfig(
        id: String,
        serverUrl: String = "local://device",
    ): LettaConfig = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = serverUrl,
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
