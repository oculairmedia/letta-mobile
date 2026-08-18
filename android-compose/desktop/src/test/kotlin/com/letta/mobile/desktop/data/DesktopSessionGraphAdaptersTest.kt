package com.letta.mobile.desktop.data

import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.BackendConfigStore
import com.letta.mobile.data.chat.runtime.SecureTokenStore
import com.letta.mobile.data.health.ServerHealthState
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.runtime.BackendKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

class DesktopSessionGraphAdaptersTest {
    @Test
    fun factoryCreatesDesktopGraphWithoutAndroidImplementations() {
        val config = LettaConfig(
            id = "desktop-self-hosted",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8283",
        )
        val factory = DesktopSessionGraphFactory(configProvider = { config })

        val graph = factory.create()

        assertEquals(1L, graph.id)
        assertEquals(BackendKind.RemoteLetta, graph.backendDescriptor.kind)
        assertEquals("http://localhost:8283", graph.backendDescriptor.label)
        assertEquals("desktop-remote-letta:desktop-self-hosted", graph.backendDescriptor.backendId.value)
        assertNull(graph.localRuntimeBackend)
        assertIs<ChannelTransportState.Idle>(graph.channelTransport.state.value)
        assertTrue(graph.agentRepository.agents.value.isEmpty())
        assertFalse(graph.agentRepository.isRefreshing.value)
        assertNull(graph.agentRepository.refreshError.value)
        assertFalse(graph.isClosed)
    }

    @Test
    fun localModeKeepsHttpScheduleAndToolRepositories() {
        val adapters = DesktopRepositoryAdapters(
            LettaConfig(
                id = "desktop-local",
                mode = LettaConfig.Mode.LOCAL,
                serverUrl = "http://127.0.0.1:8283",
            ),
        )

        assertIs<DesktopLettaHttpAdminRepositories>(adapters.scheduleRepository)
        assertIs<DesktopLettaHttpAdminRepositories>(adapters.toolRepository)
    }

    /**
     * letta-mobile-9v9nu regression: before the fix, `irohMode` was computed
     * purely from `IrohChannelTransport.isIrohUrl(config.serverUrl)`, so a
     * LOCAL config with a leftover `iroh://` serverUrl still built
     * [buildIrohRepositories] and routed `toolRepository`/`scheduleRepository`
     * through the Iroh-backed implementations instead of falling back to the
     * (correctly local-only) unavailable repository.
     */
    @Test
    fun localModeWithStaleIrohServerUrlNeverUsesIrohRepositories() {
        val adapters = DesktopRepositoryAdapters(
            LettaConfig(
                id = "desktop-local",
                mode = LettaConfig.Mode.LOCAL,
                serverUrl = "iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f@192.168.50.90:4501",
            ),
        )

        assertFalse(adapters.toolRepository is DesktopIrohToolRepository)
        assertFalse(adapters.scheduleRepository is DesktopIrohScheduleRepository)
    }

    @Test
    fun unavailableRepositoriesFailWhenInvokedBeforeJvmBindingExists() = runTest {
        val graph = DesktopSessionGraphFactory().create()

        val error = assertFailsWith<DesktopRepositoryUnavailableException> {
            graph.agentRepository.countAgents()
        }

        assertTrue(error.message.orEmpty().contains("IAgentRepository"))
        assertTrue(error.message.orEmpty().contains("countAgents"))
    }

    @Test
    fun providerRebuildClosesPreviousGraphAndPublishesNextGraph() {
        val provider = DesktopSessionGraphProvider(DesktopSessionGraphFactory())
        val first = provider.current

        val second = provider.rebuild()

        assertTrue(first.isClosed)
        assertFalse(second.isClosed)
        assertEquals(2L, second.id)
        assertEquals(second, provider.currentGraph.value)
        assertNull(provider.sessionError.value)
    }

    @Test
    fun dataBindingsExposeDesktopStorageHealthAndSessionGraph() = runTest {
        val bindings = createDefaultDesktopDataBindings(
            secureSettingsStore = DesktopInMemorySecureSettingsStore(),
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
        val chatGraph = bindings.chatSessionGraphFactory.create()
        try {
            assertEquals(2L, chatGraph.repositories.id)
        } finally {
            chatGraph.close()
        }
    }

    @Test
    fun configStorePersistsBackendAndHidesBlankTokenUpdates() {
        val settingsStore = DesktopInMemorySecureSettingsStore()
        val configStore = DesktopLettaConfigStore(settingsStore)

        configStore.save(
            LettaConfig(
                id = "desktop-test",
                mode = LettaConfig.Mode.CLOUD,
                serverUrl = "https://api.letta.com",
                accessToken = "secret-token",
            ),
        )

        val loaded = configStore.load()
        assertEquals("desktop-test", loaded.id)
        assertEquals(LettaConfig.Mode.CLOUD, loaded.mode)
        assertEquals("https://api.letta.com", loaded.serverUrl)
        assertEquals("secret-token", loaded.accessToken)
        assertEquals(listOf("https://api.letta.com"), configStore.recentBackends())

        configStore.save(loaded.copy(accessToken = ""))

        assertNull(configStore.load().accessToken)
    }

    @Test
    fun configStoreImplementsSharedBackendConfigAndTokenContracts() = runTest {
        val configStore = DesktopLettaConfigStore(DesktopInMemorySecureSettingsStore())
        val backendStore: BackendConfigStore = configStore
        val tokenStore: SecureTokenStore = configStore

        backendStore.saveActiveConfig(
            LettaConfig(
                id = "",
                mode = LettaConfig.Mode.CLOUD,
                serverUrl = " https://api.letta.com ",
                accessToken = " first ",
            ),
        )

        assertEquals(desktopConfigIdFor("https://api.letta.com"), backendStore.loadActiveConfig()?.id)
        assertEquals("https://api.letta.com", backendStore.loadActiveConfig()?.serverUrl)
        assertEquals("first", tokenStore.loadToken())

        tokenStore.saveToken(" second ")

        assertEquals("second", backendStore.loadActiveConfig()?.accessToken)

        tokenStore.clearToken()

        assertNull(backendStore.loadActiveConfig()?.accessToken)
    }

    /**
     * letta-mobile-9v9nu regression: reproduces the real-world broken state —
     * `letta.config.mode=LOCAL` plus a leftover `letta.config.serverUrl=
     * iroh://...` from before mode became authoritative — by writing the raw
     * keys directly (bypassing `save()`, which already migrates on write).
     * Loading the store must self-heal: the in-memory config comes back with
     * mode still LOCAL and the stale serverUrl dropped, and the on-disk value
     * is corrected too so the file stops disagreeing with what the app uses.
     */
    @Test
    fun configStoreMigratesStaleIrohServerUrlOnLoadForLocalMode() {
        val settingsStore = DesktopInMemorySecureSettingsStore()
        settingsStore.putString("letta.config.id", "desktop-361c792e")
        settingsStore.putString("letta.config.mode", "LOCAL")
        settingsStore.putString(
            "letta.config.serverUrl",
            "iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f@192.168.50.90:4501",
        )
        val configStore = DesktopLettaConfigStore(settingsStore)

        val loaded = configStore.load()

        assertEquals(LettaConfig.Mode.LOCAL, loaded.mode)
        assertEquals("", loaded.serverUrl)
        // The fix persists too: a subsequent, independent load of the same
        // backing store must not see the stale URL either.
        assertEquals("", settingsStore.getString("letta.config.serverUrl"))
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
        val settingsStore = DesktopInMemorySecureSettingsStore()
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
        val provider = DesktopSessionGraphProvider(
            DesktopSessionGraphFactory(configProvider = { config }),
        )

        val first = provider.current
        config = config.copy(id = "two", serverUrl = "http://two.example")
        val second = provider.rebuild()

        assertTrue(first.isClosed)
        assertEquals("desktop-remote-letta:two", second.backendDescriptor.backendId.value)
        assertEquals("http://two.example", second.backendDescriptor.label)
    }

    @Test
    fun chatSessionGraphClosesRepositoriesAndGatewayTogether() {
        var closedGatewayCount = 0
        val repositoryGraph = DesktopSessionGraphFactory().create()
        val chatGraph = DesktopChatSessionGraph(
            repositories = repositoryGraph,
            gateway = object : ChatGateway, AutoCloseable {
                override suspend fun listConversations(limit: Int, archiveStatus: String?) = emptyList<Conversation>()
                override suspend fun getConversation(conversationId: String): Conversation =
                    error("not used")

                override suspend fun sendConversationMessage(
                    conversationId: String,
                    request: MessageCreateRequest,
                ) = emptyFlow<LettaMessage>()

                override suspend fun streamConversation(conversationId: String) =
                    emptyFlow<TimelineStreamFrame>()

                override suspend fun listConversationMessages(
                    conversationId: String,
                    limit: Int?,
                    after: String?,
                    order: String?,
                ) = emptyList<LettaMessage>()

                override suspend fun listAgentMessages(
                    agentId: String,
                    limit: Int?,
                    order: String?,
                    conversationId: String?,
                ) = emptyList<LettaMessage>()

                override fun close() {
                    closedGatewayCount += 1
                }
            },
        )

        chatGraph.close()

        assertTrue(repositoryGraph.isClosed)
        assertEquals(1, closedGatewayCount)
    }
}
