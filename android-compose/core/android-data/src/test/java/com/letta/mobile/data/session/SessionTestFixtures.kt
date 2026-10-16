package com.letta.mobile.data.session

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeConversationApi
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

// letta-mobile-g2ff0: tests must wrap DAOs in dagger.Lazy because the
// production constructor now takes Lazy<AgentDao> / Lazy<ConversationDao>.
private fun <T> lazyOf(value: T): dagger.Lazy<T> = dagger.Lazy { value }

internal data class ProxySwitchScenario<T, R>(
    val setupGraph: TestDefaultSessionRepositoryGraphFactoryBuilder.() -> Unit,
    val createProxy: (SessionManager, CoroutineScope) -> T,
    val refresh: suspend (T) -> Unit,
    val observeIds: (T) -> List<R>,
    val mutateForBackendB: () -> Unit,
    val expectedBefore: List<R>,
    val expectedAfter: List<R>,
)

internal data class AdminProxySwitchSpec<A, T, R>(
    val api: A,
    val setup: TestDefaultSessionRepositoryGraphFactoryBuilder.() -> Unit,
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
        sessionGraphFactory = createTestDefaultSessionRepositoryGraphFactory(scenario.setupGraph),
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

internal data class TestDefaultSessionRepositoryGraphFactoryBuilder(
    var agentApi: FakeAgentApi = FakeAgentApi(),
    var agentDao: dagger.Lazy<AgentDao> = lazyOf(FakeAgentDao()),
    var conversationApi: FakeConversationApi = FakeConversationApi(),
    var conversationDao: dagger.Lazy<ConversationDao> = lazyOf(FakeConversationDao()),
    var mcpServerApi: FakeMcpServerApi = FakeMcpServerApi(),
    var modelApi: FakeModelApi = FakeModelApi(),
    var passageApi: FakePassageApi = FakePassageApi(),
    var projectApi: FakeProjectApi = FakeProjectApi(),
    var projectWorkApi: FakeProjectWorkApi = FakeProjectWorkApi(),
    var runApi: FakeRunApi = FakeRunApi(),
    var providerApi: FakeProviderApi = FakeProviderApi(),
    var scheduleApi: FakeScheduleApi = FakeScheduleApi(),
    var stepApi: FakeStepApi = FakeStepApi(),
    var toolApi: FakeToolApi = FakeToolApi(),
    var settingsRepository: ISettingsRepository? = null,
    var appContext: android.content.Context = mockk(relaxed = true),
) {
    /** Tests own the graph's outer bound; production uses the process lifecycle. */
    var graphParentScope: () -> CoroutineScope = { CoroutineScope(SupervisorJob()) }

    /**
     * letta-mobile-by4vm.94: Unconfined runs the (non-suspending) fake cache clear inside create(),
     * so most tests see an already-open gate, as they did when create() blocked on the clear.
     */
    var cacheClearContext: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Unconfined

    /** The fakes stand in for a non-App-Server backend through the letta-mobile-ckmub.4 seam. */
    private fun remoteSources() = SessionRemoteSources(
        agents = agentApi.bound(),
        conversations = conversationApi.bound(),
        mcpServers = mcpServerApi.bound(),
        models = modelApi.bound(),
        passages = passageApi.bound(),
        projects = projectApi.bound(),
        projectWork = projectWorkApi.bound(),
        providers = providerApi.bound(),
        runs = runApi.bound(),
        schedules = scheduleApi.bound(),
        steps = stepApi.bound(),
        tools = toolApi.bound(),
    )

    fun build(): DefaultSessionRepositoryGraphFactory = DefaultSessionRepositoryGraphFactory(
        assembler = SessionGraphAssembler(
            agentDao = agentDao,
            conversationDao = conversationDao,
            remoteSources = remoteSources(),
        ),
        channelTransportFactory = SessionChannelTransportFactory(appContext = appContext),
        settingsRepository = settingsRepository,
        graphParentScope = graphParentScope,
        cacheClearContext = cacheClearContext,
    )
}

internal fun createTestDefaultSessionRepositoryGraphFactory(
    init: TestDefaultSessionRepositoryGraphFactoryBuilder.() -> Unit = {},
): DefaultSessionRepositoryGraphFactory = TestDefaultSessionRepositoryGraphFactoryBuilder().apply(init).build()
