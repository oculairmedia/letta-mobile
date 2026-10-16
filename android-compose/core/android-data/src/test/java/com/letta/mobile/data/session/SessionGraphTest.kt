package com.letta.mobile.data.session

import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        // An http(s) address is the remote-source seam's, not an App Server socket: no channel, and
        // the NoOp stays Idle, never Disconnected, which chat would render as loss.
        assertTrue(graph.channelTransport is com.letta.mobile.data.transport.api.NoOpChannelTransport)
        assertEquals(ChannelTransportState.Idle, graph.channelTransport.state.value)
    }

    @Test
    fun `session graph satisfies shared repository graph contract`() = runTest {
        val graph = createTestDefaultSessionRepositoryGraphFactory().create()

        val sharedGraph: SessionRepositoryGraph = graph

        assertEquals(graph.backendDescriptor, sharedGraph.backendDescriptor)
        assertEquals(graph.agentRepository, sharedGraph.agentRepository)
        assertEquals(graph.channelTransport, sharedGraph.channelTransport)
    }
}
