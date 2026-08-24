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
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val graph = createTestDefaultSessionRepositoryGraphFactory {
            this.settingsRepository = settingsRepository
        }.create()

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
        val graph = createTestDefaultSessionRepositoryGraphFactory().create()

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
        val graph = createTestDefaultSessionRepositoryGraphFactory {
            this.settingsRepository = settingsRepository
            this.localRuntimeOptions = createLocalRuntimeOptions()
        }.create()

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
        val graph = createTestDefaultSessionRepositoryGraphFactory {
            this.settingsRepository = settingsRepository
            this.localRuntimeOptions = createLocalRuntimeOptions(
                createLocalRuntimeProvider(),
                createLocalRuntimeProvider(
                    LocalProviderSpec(
                        providerId = "local-koog",
                        scheme = "local-koog",
                        kind = BackendKind.LocalKoog,
                        label = "Local Koog runtime",
                        supportsTools = false,
                        supportsApprovals = false,
                        priority = 10,
                    ),
                ),
            )
        }.create()

        assertEquals(BackendKind.LocalKoog, graph.backendDescriptor.kind)
        assertEquals("local-koog:backend-a", graph.backendDescriptor.backendId.value)
        assertEquals("Local Koog runtime", graph.backendDescriptor.label)
        assertFalse(graph.backendDescriptor.capabilities.supportsTools)
    }

    @Test
    fun `session graph keeps remote backend for non-local config when local runtime is available`() = runTest {
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val graph = createTestDefaultSessionRepositoryGraphFactory {
            this.settingsRepository = settingsRepository
            this.localRuntimeOptions = createLocalRuntimeOptions()
        }.create()

        assertEquals(BackendKind.RemoteLetta, graph.backendDescriptor.kind)
        assertEquals("remote-letta:backend-a", graph.backendDescriptor.backendId.value)
        assertNull(graph.localRuntimeBackend)
    }

    private fun createLocalRuntimeOptions(
        vararg providers: LocalRuntimeProvider = arrayOf(createLocalRuntimeProvider()),
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

    private fun createLocalRuntimeProvider(
        spec: LocalProviderSpec = LocalProviderSpec(),
    ): LocalRuntimeProvider = object : LocalRuntimeProvider {
        override val providerId: String = spec.providerId
        override val priority: Int = spec.priority

        override fun supports(config: LettaConfig): Boolean =
            config.serverUrl.startsWith("${spec.scheme}://")

        override fun descriptor(config: LettaConfig): BackendDescriptor {
            val backendKey = config.id.takeIf { it.isNotBlank() } ?: "device"
            return BackendDescriptor(
                backendId = BackendId("${spec.providerId}:$backendKey"),
                runtimeId = RuntimeId("${spec.providerId}:$backendKey"),
                kind = spec.kind,
                label = spec.label,
                capabilities = BackendCapabilities(
                    supportsStreaming = true,
                    supportsMemFs = true,
                    supportsToolEvents = spec.supportsTools,
                    supportsToolExecution = spec.supportsTools,
                    supportsApprovals = spec.supportsApprovals,
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

    private data class LocalProviderSpec(
        val providerId: String = "local-lettacode",
        val scheme: String = "local",
        val kind: BackendKind = BackendKind.LocalLettaCode,
        val label: String = "Local LettaCode",
        val supportsTools: Boolean = true,
        val supportsApprovals: Boolean = supportsTools,
        val priority: Int = 100,
    )

    private fun localConfig(
        id: String,
        serverUrl: String = "local://device",
    ): LettaConfig = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = serverUrl,
    )
}
