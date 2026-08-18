package com.letta.mobile.data.session

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.model.LettaConfig
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
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals

internal fun fakeLettaApiClient(): LettaApiClient = mockk(relaxed = true)

internal data class ProxySwitchScenario<T, R>(
    val setupGraph: TestSessionGraphFactoryBuilder.() -> Unit,
    val createProxy: (SessionManager, CoroutineScope) -> T,
    val refresh: suspend (T) -> Unit,
    val observeIds: (T) -> List<R>,
    val mutateForBackendB: () -> Unit,
    val expectedBefore: List<R>,
    val expectedAfter: List<R>,
)

internal data class AdminProxySwitchSpec<A, T, R>(
    val api: A,
    val setup: TestSessionGraphFactoryBuilder.() -> Unit,
    val createProxy: (SessionManager, CoroutineScope) -> T,
    val refresh: suspend (T) -> Unit,
    val observeIds: (T) -> List<R>,
    val mutate: (A) -> Unit,
    val before: List<R>,
    val after: List<R>,
)

internal fun <A, T, R> AdminProxySwitchSpec<A, T, R>.toScenario(): ProxySwitchScenario<T, R> =
    ProxySwitchScenario(
        setupGraph = setup,
        createProxy = createProxy,
        refresh = refresh,
        observeIds = observeIds,
        mutateForBackendB = { mutate(api) },
        expectedBefore = before,
        expectedAfter = after,
    )

@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T, R> assertProxySwitchesCaches(
    scheduler: TestCoroutineScheduler,
    scenario: ProxySwitchScenario<T, R>,
) {
    val dispatcher = StandardTestDispatcher(scheduler)
    val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
    val sessionManager = SessionManager(
        settingsRepository = settingsRepository,
        sessionGraphFactory = createTestSessionGraphFactory(scenario.setupGraph),
        managerScope = CoroutineScope(SupervisorJob() + dispatcher),
    )
    val proxy = scenario.createProxy(sessionManager, CoroutineScope(SupervisorJob() + dispatcher))

    kotlinx.coroutines.test.runTest(scheduler) {
        scenario.refresh(proxy)
        advanceUntilIdle()
        assertEquals(scenario.expectedBefore, scenario.observeIds(proxy))

        scenario.mutateForBackendB()
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        advanceUntilIdle()

        assertEquals(emptyList<R>(), scenario.observeIds(proxy))

        scenario.refresh(proxy)
        advanceUntilIdle()
        assertEquals(scenario.expectedAfter, scenario.observeIds(proxy))
    }
}

internal fun sessionTestConfig(id: String): LettaConfig = LettaConfig(
    id = id,
    mode = LettaConfig.Mode.SELF_HOSTED,
    serverUrl = "https://$id.example.test",
)

internal class TestSessionGraphFactoryBuilder(
    var agentApi: FakeAgentApi = FakeAgentApi(),
    var agentDao: FakeAgentDao = FakeAgentDao(),
    var conversationApi: FakeConversationApi = FakeConversationApi(),
    var conversationDao: FakeConversationDao = FakeConversationDao(),
    var archiveApi: FakeArchiveApi = FakeArchiveApi(),
    var folderApi: FakeFolderApi = FakeFolderApi(),
    var groupApi: FakeGroupApi = FakeGroupApi(),
    var identityApi: FakeIdentityApi = FakeIdentityApi(),
    var lettaApiClient: LettaApiClient = fakeLettaApiClient(),
    var mcpServerApi: FakeMcpServerApi = FakeMcpServerApi(),
    var modelApi: FakeModelApi = FakeModelApi(),
    var passageApi: FakePassageApi = FakePassageApi(),
    var projectApi: FakeProjectApi = FakeProjectApi(),
    var projectWorkApi: FakeProjectWorkApi = FakeProjectWorkApi(),
    var runApi: FakeRunApi = FakeRunApi(),
    var jobApi: FakeJobApi = FakeJobApi(),
    var providerApi: FakeProviderApi = FakeProviderApi(),
    var scheduleApi: FakeScheduleApi = FakeScheduleApi(),
    var stepApi: FakeStepApi = FakeStepApi(),
    var toolApi: FakeToolApi = FakeToolApi(),
    var settingsRepository: FakeSettingsRepository? = null,
    var localRuntimeOptions: LocalRuntimeOptions = LocalRuntimeOptions.Disabled,
) {
    fun build(): SessionGraphFactory = SessionGraphFactory(
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
        appContext = mockk(relaxed = true),
        settingsRepository = settingsRepository,
        localRuntimeOptions = localRuntimeOptions,
    )
}

internal fun createTestSessionGraphFactory(
    init: TestSessionGraphFactoryBuilder.() -> Unit = {},
): SessionGraphFactory = TestSessionGraphFactoryBuilder().apply(init).build()

internal class FakeAgentDao : AgentDao {
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

internal class FakeConversationDao : ConversationDao {
    val conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
    val refreshStates = mutableMapOf<String, ConversationRefreshEntity>()

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
