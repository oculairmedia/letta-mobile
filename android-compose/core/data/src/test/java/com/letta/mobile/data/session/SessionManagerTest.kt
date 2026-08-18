package com.letta.mobile.data.session

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
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
import com.letta.mobile.runtime.TurnEngine
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {

    @Test
    fun `active config change rebuilds session graph and cancels previous scope`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
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
                appContext = mockk(relaxed = true),
                settingsRepository = settingsRepository,
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        val firstGraph = sessionManager.current
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        advanceUntilIdle()

        val secondGraph = sessionManager.current
        assertNotEquals(firstGraph.id, secondGraph.id)
        assertTrue(firstGraph.scope.coroutineContext.job.isCancelled)
        org.junit.Assert.assertFalse(secondGraph.scope.coroutineContext.job.isCancelled)
    }

    @Test
    fun `transport adjacent state holders are recreated when graph rebuilds`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
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
                appContext = mockk(relaxed = true),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        val firstGraph = sessionManager.current
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
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
    fun `same backend config emission does not rebuild graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
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
                appContext = mockk(relaxed = true),
                settingsRepository = settingsRepository,
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        val firstGraph = sessionManager.current
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b", serverUrl = "https://backend-a.example.test")
        advanceUntilIdle()

        assertEquals(System.identityHashCode(firstGraph), System.identityHashCode(sessionManager.current))
        assertEquals(System.identityHashCode(firstGraph.channelTransport), System.identityHashCode(sessionManager.current.channelTransport))
    }

    @Test
    fun `embedded local model selection on same config id rebuilds session graph`() = runTest {
        // letta-mobile-mlyhq: model selection edits the same config id, which
        // the id-distinct activeConfigChanges never emitted; graph must rebuild.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = localConfig("backend-a"))
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
                appContext = mockk(relaxed = true),
                settingsRepository = settingsRepository,
                localRuntimeOptions = localRuntimeOptions(),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        val firstGraph = sessionManager.current
        settingsRepository.activeConfigState.value = localConfig("backend-a").copy(
            localModelPath = "/data/user/0/com.letta.mobile.dev/files/embedded-models/gemma.litertlm",
            localModelHandle = "google/gemma-3n-E2B-it-litert-lm",
        )
        advanceUntilIdle()

        val secondGraph = sessionManager.current
        assertNotEquals(firstGraph.id, secondGraph.id)
        assertTrue(firstGraph.scope.coroutineContext.job.isCancelled)
        assertTrue(!secondGraph.scope.coroutineContext.job.isCancelled)
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

    private fun localConfig(
        id: String,
        serverUrl: String = "local://device",
    ): LettaConfig = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = serverUrl,
    )
}
