package com.letta.mobile.desktop.data

import com.letta.mobile.data.health.ServerHealthState
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.runtime.BackendKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
        val bindings = createDefaultDesktopDataBindings()

        bindings.secureSettingsStore.putString("accessToken", "token-1")
        assertEquals("token-1", bindings.secureSettingsStore.getString("accessToken"))
        bindings.secureSettingsStore.remove("accessToken")
        assertEquals("fallback", bindings.secureSettingsStore.getString("accessToken", "fallback"))

        val health = bindings.healthRepository as DesktopServerHealthRepository
        health.setState("desktop-local", ServerHealthState.UNKNOWN)
        health.refreshAll()

        assertEquals(ServerHealthState.PROBING, health.states.value["desktop-local"])
        assertEquals(1L, bindings.sessionGraphProvider.current.id)
    }
}
