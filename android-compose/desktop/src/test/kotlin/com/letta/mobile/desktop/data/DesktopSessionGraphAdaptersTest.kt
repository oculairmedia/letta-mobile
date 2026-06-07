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
                override suspend fun listConversations(limit: Int) = emptyList<Conversation>()
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
