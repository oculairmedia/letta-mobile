package com.letta.mobile.data.capability

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ProjectCatalog
import com.letta.mobile.data.repository.IrohAdminRpcProjectSource
import com.letta.mobile.data.repository.ProjectListLimit
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
import kotlinx.coroutines.test.TestScope
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
        val repository = repository(settings)
        advanceUntilIdle()

        assertFalse(repository.projectsSupported.value)
        assertFalse(repository.projectWorkSupported.value)
    }

    /** letta-mobile-ckmub.4: only an Iroh host relays projects; a non-Iroh remote has no route. */
    @Test
    fun `non-iroh remote config has no project route`() = runTest {
        val settings = FakeSettingsRepository(initialActiveConfig = remoteConfig())
        val repository = repository(settings)
        advanceUntilIdle()

        assertFalse(repository.projectsSupported.value)
        assertFalse(repository.projectWorkSupported.value)
    }

    @Test
    fun `iroh config keeps projects probe but disables project work`() = runTest {
        val settings = irohSettings()
        val irohSource = emptyCatalogIrohSource(settings)
        val repository = irohRepository(
            settings = settings,
            irohSource = irohSource,
            sessionManager = sessionManagerWithTransport(irohChannelTransport()),
        )
        advanceUntilIdle()

        assertTrue(repository.projectsSupported.value)
        assertFalse(repository.projectWorkSupported.value)
        assertEquals(1, irohSource.probeCount)
    }

    @Test
    fun `iroh probe defers when transport is not ready and retries after session rebuild`() = runTest {
        val settings = irohSettings()
        val staleTransport = mockk<IChannelTransport>(relaxed = true)
        val irohTransport = irohChannelTransport()
        val graphFlow = MutableStateFlow(sessionGraph(id = 1L, transport = staleTransport))
        val sessionManager = mockk<SessionManager>(relaxed = true) {
            every { currentGraph } returns graphFlow
            every { current } answers { graphFlow.value }
        }
        val irohSource = emptyCatalogIrohSource(settings)
        val repository = irohRepository(
            settings = settings,
            irohSource = irohSource,
            sessionManager = sessionManager,
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

    private fun TestScope.testScope() = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.repository(settings: FakeSettingsRepository) = CapabilityRepository(settings, testScope())

    private fun TestScope.irohRepository(
        settings: FakeSettingsRepository,
        irohSource: FakeIrohProjectSource,
        sessionManager: SessionManager,
    ) = CapabilityRepository(
        settingsRepository = settings,
        irohProjectSource = irohSource,
        sessionManager = sessionManager,
        scope = testScope(),
    )

    private fun irohSettings() = FakeSettingsRepository(initialActiveConfig = irohConfig())

    private fun emptyCatalogIrohSource(settings: ISettingsRepository) = FakeIrohProjectSource(
        settings = settings,
        probeResult = { ProjectCatalog(projects = emptyList()) },
    )

    private fun TestScope.irohChannelTransport() = IrohChannelTransport(
        scope = testScope(),
        onConnect = {},
        activeConfigProvider = { null },
    )

    private fun sessionManagerWithTransport(transport: IChannelTransport): SessionManager {
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

    private class FakeIrohProjectSource(
        settings: ISettingsRepository,
        private val probeResult: suspend () -> ProjectCatalog,
    ) : IrohAdminRpcProjectSource(
        channelTransport = mockk(relaxed = true),
        settingsRepository = settings,
    ) {
        var probeCount = 0

        override suspend fun refreshProjects(limit: ProjectListLimit?): ProjectCatalog {
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
