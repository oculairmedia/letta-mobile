package com.letta.mobile.data.capability

import com.letta.mobile.data.api.AdminApiUnavailableForLocalRuntimeException
import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.api.ProjectApi
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ProjectCatalog
import com.letta.mobile.data.repository.IrohAdminRpcProjectSource
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.session.SessionGraph
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.testutil.FakeSettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityRepositoryTest {
    @Test
    fun `local runtime config skips project probe and defaults unsupported`() = runTest {
        val settings = FakeSettingsRepository(initialActiveConfig = localConfig())
        val projectApi = FakeProjectApi { error("local config must not probe Admin API") }
        val repository = CapabilityRepository(
            settings,
            projectApi,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        assertFalse(repository.projectsSupported.value)
        assertFalse(repository.projectWorkSupported.value)
        assertEquals(0, projectApi.probeCount)
    }

    @Test
    fun `remote config still probes project capability`() = runTest {
        val settings = FakeSettingsRepository(initialActiveConfig = remoteConfig())
        val projectApi = FakeProjectApi { true }
        val repository = CapabilityRepository(
            settings,
            projectApi,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        assertTrue(repository.projectsSupported.value)
        assertTrue(repository.projectWorkSupported.value)
        assertEquals(1, projectApi.probeCount)
    }

    @Test
    fun `probe exception marks projects unsupported without escaping collector`() = runTest {
        val settings = FakeSettingsRepository(initialActiveConfig = remoteConfig(id = "remote-1"))
        val projectApi = FakeProjectApi { throw AdminApiUnavailableForLocalRuntimeException() }
        val repository = CapabilityRepository(
            settings,
            projectApi,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()
        settings.activeConfigState.value = remoteConfig(id = "remote-2")
        advanceUntilIdle()

        assertFalse(repository.projectsSupported.value)
        assertFalse(repository.projectWorkSupported.value)
        assertEquals(2, projectApi.probeCount)
    }

    @Test
    fun `iroh config keeps projects probe but disables project work`() = runTest {
        val irohConfig = irohConfig()
        val settings = FakeSettingsRepository(initialActiveConfig = irohConfig)
        val projectApi = FakeProjectApi { error("HTTP probe must not run for iroh backend") }
        val irohSource = FakeIrohProjectSource(
            settings = settings,
            probeResult = { ProjectCatalog(projects = emptyList()) },
        )
        val sessionManager = sessionManagerWithTransport(
            testScheduler = testScheduler,
            transport = IrohChannelTransport(
                scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                onConnect = {},
                activeConfigProvider = { null },
            ),
        )
        val repository = CapabilityRepository(
            settingsRepository = settings,
            projectApi = projectApi,
            irohProjectSource = irohSource,
            sessionManager = sessionManager,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        assertTrue(repository.projectsSupported.value)
        assertFalse(repository.projectWorkSupported.value)
        assertEquals(0, projectApi.probeCount)
        assertEquals(1, irohSource.probeCount)
    }

    @Test
    fun `iroh probe defers when transport is not ready and retries after session rebuild`() = runTest {
        val irohConfig = irohConfig()
        val settings = FakeSettingsRepository(initialActiveConfig = irohConfig)
        val projectApi = FakeProjectApi { error("HTTP probe must not run for iroh backend") }
        val staleTransport = mockk<IChannelTransport>(relaxed = true)
        val irohTransport = IrohChannelTransport(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            onConnect = {},
            activeConfigProvider = { null },
        )
        val graphFlow = MutableStateFlow(sessionGraph(id = 1L, transport = staleTransport))
        val sessionManager = mockk<SessionManager>(relaxed = true) {
            every { currentGraph } returns graphFlow
            every { current } answers { graphFlow.value }
        }
        val irohSource = FakeIrohProjectSource(
            settings = settings,
            probeResult = { ProjectCatalog(projects = emptyList()) },
        )
        val repository = CapabilityRepository(
            settingsRepository = settings,
            projectApi = projectApi,
            irohProjectSource = irohSource,
            sessionManager = sessionManager,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        assertTrue(repository.projectsSupported.value)
        assertFalse(repository.projectWorkSupported.value)
        assertEquals(0, irohSource.probeCount)

        graphFlow.value = sessionGraph(id = 2L, transport = staleTransport)
        advanceUntilIdle()
        assertEquals(0, irohSource.probeCount)

        graphFlow.value = sessionGraph(id = 3L, transport = irohTransport)
        advanceUntilIdle()

        assertTrue(repository.projectsSupported.value)
        assertFalse(repository.projectWorkSupported.value)
        assertEquals(1, irohSource.probeCount)
    }

    private fun sessionManagerWithTransport(
        testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        transport: IChannelTransport,
    ): SessionManager {
        val graph = sessionGraph(id = 1L, transport = transport)
        val graphFlow = MutableStateFlow(graph)
        return mockk(relaxed = true) {
            every { currentGraph } returns graphFlow
            every { current } returns graph
        }
    }

    private fun sessionGraph(id: Long, transport: IChannelTransport): SessionGraph =
        mockk(relaxed = true) {
            every { this@mockk.id } returns id
            every { channelTransport } returns transport
        }

    private class FakeProjectApi(
        private val probeResult: suspend () -> Boolean,
    ) : ProjectApi(apiClient = mockk<LettaApiClient>()) {
        var probeCount = 0

        override suspend fun probeAvailability(): Boolean {
            probeCount += 1
            return probeResult()
        }
    }

    private class FakeIrohProjectSource(
        settings: ISettingsRepository,
        private val probeResult: suspend () -> ProjectCatalog,
    ) : IrohAdminRpcProjectSource(
        channelTransport = mockk(relaxed = true),
        settingsRepository = settings,
    ) {
        var probeCount = 0

        override suspend fun refreshProjects(limit: Int?): ProjectCatalog {
            probeCount += 1
            return probeResult()
        }
    }

    private fun localConfig() = LettaConfig(
        id = "local",
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = "local-lettacode://device",
    )

    private fun remoteConfig(id: String = "remote") = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = "https://admin.example.test",
    )

    private fun irohConfig(id: String = "iroh") = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = "iroh://test-ticket",
    )
}
