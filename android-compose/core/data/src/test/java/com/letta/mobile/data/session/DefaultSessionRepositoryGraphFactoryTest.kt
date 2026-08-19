package com.letta.mobile.data.session

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.iroh.IrohScheduleRepository
import com.letta.mobile.data.repository.ScheduleRepository
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeEventOutbox
import com.letta.mobile.runtime.MemFsStore
import com.letta.mobile.runtime.TurnEngine
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun <T> lazyOf(value: T): dagger.Lazy<T> = dagger.Lazy { value }

class DefaultSessionRepositoryGraphFactoryTest {

    private val agentDao: com.letta.mobile.data.local.AgentDao = mockk(relaxed = true)
    private val conversationDao: com.letta.mobile.data.local.ConversationDao = mockk(relaxed = true)
    private val appContext: android.content.Context = mockk(relaxed = true)

    private fun factory(
        settingsRepository: ISettingsRepository? = null,
        localRuntimeOptions: LocalRuntimeOptions = LocalRuntimeOptions.Disabled,
    ): DefaultSessionRepositoryGraphFactory =
        createTestDefaultSessionRepositoryGraphFactory {
            this.agentDao = lazyOf(this@DefaultSessionRepositoryGraphFactoryTest.agentDao)
            this.conversationDao = lazyOf(this@DefaultSessionRepositoryGraphFactoryTest.conversationDao)
            this.appContext = this@DefaultSessionRepositoryGraphFactoryTest.appContext
            this.settingsRepository = settingsRepository
            this.localRuntimeOptions = localRuntimeOptions
        }

    private fun settingsWith(config: LettaConfig): ISettingsRepository {
        every { appContext.filesDir } returns java.io.File(
            System.getProperty("java.io.tmpdir"),
            "letta-session-graph-test",
        )
        val settingsRepository: ISettingsRepository = mockk()
        every { settingsRepository.activeConfig } returns MutableStateFlow(config)
        return settingsRepository
    }

    @Test
    fun `create clears daos and produces remote descriptor by default`() {
        val graph = factory().create()

        coVerify { agentDao.deleteAll() }
        coVerify { conversationDao.deleteAll() }
        coVerify { conversationDao.deleteAllRefreshStates() }

        assertEquals(BackendKind.RemoteLetta, graph.backendDescriptor.kind)
        assertEquals("remote-letta:default", graph.backendDescriptor.backendId.value)
        assertNull(graph.localRuntimeBackend)
    }

    @Test
    fun `remote and local-disabled configs map to remote descriptors`() {
        val remote = factory(
            settingsRepository = settingsWith(
                LettaConfig(
                    id = "test-remote",
                    mode = LettaConfig.Mode.CLOUD,
                    serverUrl = "https://test.letta.com",
                ),
            ),
        ).create()
        assertEquals(BackendKind.RemoteLetta, remote.backendDescriptor.kind)
        assertEquals("remote-letta:test-remote", remote.backendDescriptor.backendId.value)
        assertEquals("https://test.letta.com", remote.backendDescriptor.label)
        assertNull(remote.localRuntimeBackend)

        val localDisabled = factory(
            settingsRepository = settingsWith(
                LettaConfig(
                    id = "test-local",
                    mode = LettaConfig.Mode.LOCAL,
                    serverUrl = "local",
                ),
            ),
            localRuntimeOptions = LocalRuntimeOptions.Disabled,
        ).create()
        assertEquals("remote-letta:test-local", localDisabled.backendDescriptor.backendId.value)
        assertNull(localDisabled.localRuntimeBackend)
    }

    @Test
    fun `iroh graph binds canonical shared schedule repository and iroh transport`() {
        val config = LettaConfig(
            id = "test-iroh",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "iroh://test",
            accessToken = "token",
        )
        val graph = factory(settingsRepository = settingsWith(config)).create()

        assertTrue(graph.scheduleRepository is IrohScheduleRepository)
        assertTrue(graph.channelTransport is IrohChannelTransport)
        assertFalse(graph.scheduleRepository is ScheduleRepository)
        graph.close()
    }

    @Test
    fun `create with local config and enabled options creates local backend`() {
        val config = LettaConfig(
            id = "test-local-enabled",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "local",
        )
        val provider: LocalRuntimeProvider = mockk()
        every { provider.supports(config) } returns true
        every { provider.priority } returns 1
        every { provider.providerId } returns "test-provider"
        every { provider.descriptor(config) } returns BackendDescriptor(
            backendId = BackendId("local:test"),
            runtimeId = RuntimeId("local:test"),
            kind = BackendKind.LocalKoog,
            label = "local",
            capabilities = mockk(relaxed = true),
        )
        every { provider.turnEngine(config) } returns mockk<TurnEngine>()

        val graph = factory(
            settingsRepository = settingsWith(config),
            localRuntimeOptions = LocalRuntimeOptions.Enabled(
                runtimeEventOutbox = mockk<RuntimeEventOutbox>(),
                memFsStore = mockk<MemFsStore>(),
                providers = setOf(provider),
            ),
        ).create()

        assertEquals(BackendKind.LocalKoog, graph.backendDescriptor.kind)
        assertEquals("local:test", graph.backendDescriptor.backendId.value)
        assertNotNull(graph.localRuntimeBackend)
    }

    @Test
    fun `create with local config and enabled options falls back if no provider supports`() {
        val config = LettaConfig(
            id = "test-local-unsupported",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "local",
        )
        val provider: LocalRuntimeProvider = mockk()
        every { provider.supports(config) } returns false

        val graph = factory(
            settingsRepository = settingsWith(config),
            localRuntimeOptions = LocalRuntimeOptions.Enabled(
                runtimeEventOutbox = mockk<RuntimeEventOutbox>(),
                memFsStore = mockk<MemFsStore>(),
                providers = setOf(provider),
            ),
        ).create()

        assertEquals(BackendKind.RemoteLetta, graph.backendDescriptor.kind)
        assertNull(graph.localRuntimeBackend)
    }
}
