package com.letta.mobile.desktop.data

import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.chat.runtime.BackendConfigStore
import com.letta.mobile.data.chat.runtime.SecureTokenStore
import com.letta.mobile.data.health.ServerHealthState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.CachedBlockRepository
import com.letta.mobile.data.repository.ScheduleRepository
import com.letta.mobile.data.repository.appserver.AppServerAgentBlockRepository
import com.letta.mobile.data.repository.appserver.AppServerAgentRepository
import com.letta.mobile.data.repository.iroh.IrohScheduleRepository
import com.letta.mobile.data.session.graph.HostSessionGraphProvider
import com.letta.mobile.data.session.graph.SharedSessionRepositoryGraph
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.runtime.BackendKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Desktop's binding of the shared session graph (letta-mobile-ckmub.17.1). The route matrix itself
 * is held by the shared `HostSessionGraphFactoryTest`; these cover what desktop binds: its App
 * Server registries, the Iroh transport and its storage.
 */
class DesktopSessionGraphAdaptersTest {
    private fun graphFor(config: LettaConfig?): SharedSessionRepositoryGraph =
        desktopSessionGraphFactory(configProvider = { config }, channelTransportFactory = ::NoOpChannelTransport).create()

    @Test
    fun factoryCreatesDesktopGraphWithoutAndroidImplementations() {
        val config = LettaConfig(
            id = "desktop-self-hosted",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8283",
        )

        val graph = graphFor(config)

        assertEquals(1L, graph.id)
        assertEquals(BackendKind.RemoteLetta, graph.backendDescriptor.kind)
        assertEquals("http://localhost:8283", graph.backendDescriptor.label)
        assertEquals("desktop-remote-letta:desktop-self-hosted", graph.backendDescriptor.backendId.value)
        assertIs<ChannelTransportState.Idle>(graph.channelTransport.state.value)
        assertTrue(graph.agentRepository.agents.value.isEmpty())
        assertFalse(graph.agentRepository.isRefreshing.value)
        assertNull(graph.agentRepository.refreshError.value)
        assertFalse(graph.isClosed)
        graph.close()
    }

    /**
     * letta-mobile-ckmub.4: no backend binds a Letta REST repository any more. The bundled runtime
     * and a remote host reached directly (ws://) are both App Servers: agents and memory are read
     * over that session; schedules and tools have no direct App Server route yet, so they are their
     * absent-route form rather than a REST fallback.
     */
    @Test
    fun appServerBackendsReadAgentsAndMemoryOverTheAppServer() = runTest {
        val backends = listOf(
            LettaConfig(id = "desktop-local", mode = LettaConfig.Mode.LOCAL, serverUrl = ""),
            LettaConfig(id = "desktop-self-hosted", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "ws://localhost:4500"),
        )
        for (config in backends) {
            val graph = graphFor(config)

            assertIs<AppServerAgentRepository>(graph.agentRepository, config.id)
            assertIs<AppServerAgentBlockRepository>(graph.blockRepository, config.id)
            assertIs<ScheduleRepository>(graph.scheduleRepository, config.id)
            assertUnserved(SessionRoute.TOOLS) { graph.toolRepository.refreshTools() }
            graph.close()
        }
    }

    @Test
    fun irohModeBindsBlockRepositoryThroughIroh() {
        val graph = graphFor(LettaConfig(id = "desktop-iroh", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = IROH_URL))

        assertIs<CachedBlockRepository>(graph.blockRepository)
        graph.close()
    }

    /**
     * letta-mobile-9v9nu regression: a LOCAL config with a leftover `iroh://` serverUrl must not
     * route tools or schedules through the Iroh-backed implementations.
     */
    @Test
    fun localModeWithStaleIrohServerUrlNeverUsesIrohRepositories() = runTest {
        val graph = graphFor(LettaConfig(id = "desktop-local", mode = LettaConfig.Mode.LOCAL, serverUrl = IROH_URL))

        assertFalse(graph.capabilities.supports(SessionRoute.TOOLS))
        assertUnserved(SessionRoute.TOOLS) { graph.toolRepository.refreshTools() }
        assertFalse(graph.scheduleRepository is IrohScheduleRepository)
        graph.close()
    }

    @Test
    fun irohModeBindsSharedCronSelfTodoSubagentAndAdminReads() = runTest {
        val graph = graphFor(LettaConfig(id = "desktop-iroh", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = IROH_URL))

        assertIs<com.letta.mobile.data.repository.CronRepository>(graph.cronRepository)
        assertIs<com.letta.mobile.data.repository.SelfTodoRepository>(graph.selfTodoRepository)
        assertIs<com.letta.mobile.data.repository.SubagentRepository>(graph.subagentRepository)

        val adminError = runCatching { graph.providerRepository.refreshProviders() }.exceptionOrNull()
        assertFalse(adminError is AppServerRouteUnavailable, "Iroh admin reads should be bound, not unserved")
        graph.close()
    }

    @Test
    fun remoteAppServerKeepsAdminReadsWithoutARoute() = runTest {
        val graph = graphFor(
            LettaConfig(id = "desktop-self-hosted", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "http://localhost:8283"),
        )

        assertIs<com.letta.mobile.data.repository.CronRepository>(graph.cronRepository)
        assertUnserved(SessionRoute.PASSAGES) { graph.passageRepository.searchArchival(AgentId("agent-1"), "query") }
        graph.close()
    }

    @Test
    fun noBackendServesNoAgents() = runTest {
        val graph = desktopSessionGraphFactory().create()

        assertUnserved(SessionRoute.AGENTS) { graph.agentRepository.countAgents() }
        graph.close()
    }

    @Test
    fun providerRebuildClosesPreviousGraphAndPublishesNextGraph() {
        val provider = HostSessionGraphProvider(desktopSessionGraphFactory(), SessionPlatform.DESKTOP)
        val first = provider.current

        val second = provider.rebuild()

        assertTrue(first.isClosed)
        assertFalse(second.isClosed)
        assertEquals(2L, second.id)
        assertEquals(second, provider.currentGraph.value)
        assertNull(provider.sessionError.value)
    }

    private suspend fun assertUnserved(route: SessionRoute, call: suspend () -> Unit) {
        val error = assertFailsWith<AppServerRouteUnavailable> { call() }
        assertEquals(route, error.route)
    }

    @Test
    fun dataBindingsExposeDesktopStorageHealthAndSessionGraph() = runTest {
        val bindings = createDefaultDesktopDataBindings(
            secureSettingsStore = InMemorySecureSettingsStore(),
        )

        bindings.secureSettingsStore.putString("accessToken", "token-1")
        assertEquals("token-1", bindings.secureSettingsStore.getString("accessToken"))
        bindings.secureSettingsStore.remove("accessToken")
        assertEquals("fallback", bindings.secureSettingsStore.getString("accessToken", "fallback"))

        val health = bindings.healthRepository as DesktopServerHealthRepository
        health.setState("desktop-local", ServerHealthState.UNKNOWN)
        health.refreshAll()

        assertEquals(ServerHealthState.PROBING, health.states.value["desktop-local"])
        assertEquals(1L, bindings.sessionGraphProvider.current.id)
        // letta-mobile-ckmub.4: a chat graph now dials a real App Server (the bundled runtime by
        // default), which a unit test does not ship; the next repository generation is what it builds.
        val nextGraph = bindings.sessionGraphFactory.create()
        try {
            assertEquals(2L, nextGraph.id)
        } finally {
            nextGraph.close()
        }
    }

    @Test
    fun configStorePersistsBackendAndHidesBlankTokenUpdates() {
        val settingsStore = InMemorySecureSettingsStore()
        val configStore = DesktopLettaConfigStore(settingsStore)

        configStore.save(
            LettaConfig(
                id = "desktop-test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "https://remote.example.test",
                accessToken = "secret-token",
            ),
        )

        val loaded = configStore.load()
        assertEquals("desktop-test", loaded.id)
        assertEquals(LettaConfig.Mode.SELF_HOSTED, loaded.mode)
        assertEquals("https://remote.example.test", loaded.serverUrl)
        assertEquals("secret-token", loaded.accessToken)
        assertEquals(listOf("https://remote.example.test"), configStore.recentBackends())

        configStore.save(loaded.copy(accessToken = ""))

        assertNull(configStore.load().accessToken)
    }

    @Test
    fun configStoreImplementsSharedBackendConfigAndTokenContracts() = runTest {
        val configStore = DesktopLettaConfigStore(InMemorySecureSettingsStore())
        val backendStore: BackendConfigStore = configStore
        val tokenStore: SecureTokenStore = configStore

        backendStore.saveActiveConfig(
            LettaConfig(
                id = "",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = " https://remote.example.test ",
                accessToken = " first ",
            ),
        )

        assertEquals(desktopConfigIdFor("https://remote.example.test"), backendStore.loadActiveConfig()?.id)
        assertEquals("https://remote.example.test", backendStore.loadActiveConfig()?.serverUrl)
        assertEquals("first", tokenStore.loadToken())

        tokenStore.saveToken(" second ")

        assertEquals("second", backendStore.loadActiveConfig()?.accessToken)

        tokenStore.clearToken()

        assertNull(backendStore.loadActiveConfig()?.accessToken)
    }

    /**
     * letta-mobile-hhp6r: switching to Local (and back) through the store's
     * own save()/load() must not force the user to re-enter their remote
     * backend's URL and token. The blank-on-LOCAL behaviour from 9v9nu is
     * preserved — `migrateStaleLocalServerUrl` still empties `serverUrl` — but
     * the details it would otherwise discard land in dedicated parked fields
     * and come back out on the next switch to a remote mode.
     */
    @Test
    fun configStoreRoundTripsRemoteDetailsAcrossASwitchToLocalAndBack() {
        val settingsStore = InMemorySecureSettingsStore()
        val configStore = DesktopLettaConfigStore(settingsStore)

        configStore.save(
            LettaConfig(
                id = "desktop-remote",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://abc123@192.168.50.90:4501",
                accessToken = "remote-token",
            ),
        )

        // User flips the mode chip to Local without touching the URL/token
        // fields, so the save carries the stale remote serverUrl along.
        configStore.save(
            configStore.load().copy(mode = LettaConfig.Mode.LOCAL),
        )
        val local = configStore.load()
        assertEquals(LettaConfig.Mode.LOCAL, local.mode)
        assertEquals("", local.serverUrl)

        // Flip back to a remote mode with a blank serverUrl field (mirrors the
        // desktop settings card immediately after switching mode) — the parked
        // remote backend should come back with no re-entry required.
        configStore.save(
            configStore.load().copy(mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = ""),
        )
        val restored = configStore.load()
        assertEquals("iroh://abc123@192.168.50.90:4501", restored.serverUrl)
        assertEquals("remote-token", restored.accessToken)

        // And once more: Local, then back to remote — still round-trips.
        configStore.save(configStore.load().copy(mode = LettaConfig.Mode.LOCAL))
        assertEquals("", configStore.load().serverUrl)
        configStore.save(configStore.load().copy(mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = ""))
        val restoredAgain = configStore.load()
        assertEquals("iroh://abc123@192.168.50.90:4501", restoredAgain.serverUrl)
        assertEquals("remote-token", restoredAgain.accessToken)
    }

    @Test
    fun fileSettingsStorePersistsAcrossInstances() {
        val file = Files.createTempFile("letta-desktop-settings", ".properties")
        try {
            val first = DesktopFileSecureSettingsStore(file)
            first.putString("serverUrl", "http://localhost:8283")

            val second = DesktopFileSecureSettingsStore(file)

            assertEquals("http://localhost:8283", second.getString("serverUrl"))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun graphProviderRebuildUsesLatestConfigProviderValue() {
        var config = LettaConfig(
            id = "one",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://one.example",
        )
        val provider = HostSessionGraphProvider(
            desktopSessionGraphFactory(configProvider = { config }),
            SessionPlatform.DESKTOP,
        )

        val first = provider.current
        config = config.copy(id = "two", serverUrl = "http://two.example")
        val second = provider.rebuild()

        assertTrue(first.isClosed)
        assertEquals("desktop-remote-letta:two", second.backendDescriptor.backendId.value)
        assertEquals("http://two.example", second.backendDescriptor.label)
    }

    private companion object {
        const val IROH_URL = "iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f@192.168.50.90:4501"
    }
}
