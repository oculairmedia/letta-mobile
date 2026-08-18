package com.letta.mobile.data.session

import com.letta.mobile.data.api.LettaApiClient
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
