package com.letta.mobile.data.session.graph

import com.letta.mobile.data.backend.SessionCapabilities
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.unavailable
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.remoteLettaBackendDescriptor
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** letta-mobile-by4vm.86: the one graph class Android and desktop both hand out. */
class SharedSessionRepositoryGraphTest {
    @Test
    fun exposesTheHostAndTransportBoundRepositoriesItWasBuiltFrom() = runTest {
        val transport = NoOpChannelTransport()
        val host = unavailableHost()
        val transportBound = SharedSessionGraph.transportBound(transport, backgroundScope)

        val graph: SessionRepositoryGraph = graph(host, transportBound, transport)

        assertEquals(7L, graph.id)
        assertSame(transport, graph.channelTransport)
        assertSame(host.agentRepository, graph.agentRepository)
        assertSame(host.toolRepository, graph.toolRepository)
        assertSame(host.vibesyncEventStreamRepository, graph.vibesyncEventStreamRepository)
        assertSame(transportBound.cronRepository, graph.cronRepository)
        assertSame(transportBound.selfTodoRepository, graph.selfTodoRepository)
        assertSame(transportBound.subagentRepository, graph.subagentRepository)
        assertSame(transportBound.contextTokenReadings, (graph as SharedSessionRepositoryGraph).contextTokenReadings)
    }

    @Test
    fun closeRunsTheHostCleanupExactlyOnce() = runTest {
        var closes = 0
        val graph = graph(
            host = unavailableHost(),
            transportBound = SharedSessionGraph.transportBound(NoOpChannelTransport(), backgroundScope),
            onClose = { closes++ },
        )
        assertFalse(graph.isClosed)

        graph.close()
        graph.close()

        assertTrue(graph.isClosed)
        assertEquals(1, closes)
    }

    private fun graph(
        host: HostSessionRepositories,
        transportBound: TransportBoundSessionRepositories,
        transport: NoOpChannelTransport = NoOpChannelTransport(),
        onClose: () -> Unit = {},
    ) = SharedSessionRepositoryGraph(
        identity = SessionGraphIdentity(
            id = 7L,
            backendDescriptor = remoteLettaBackendDescriptor(config = null, idPrefix = "test"),
            channelTransport = transport,
            capabilities = SessionCapabilities.None,
        ),
        host = host,
        transportBound = transportBound,
        onClose = onClose,
    )

    private fun unavailableHost() = HostSessionRepositories(
        agentRepository = unavailable(SessionRoute.AGENTS),
        blockRepository = null,
        conversationRepository = unavailable(SessionRoute.CONVERSATIONS),
        mcpServerRepository = unavailable(SessionRoute.MCP_SERVERS),
        modelRepository = unavailable(SessionRoute.MODELS),
        passageRepository = unavailable(SessionRoute.PASSAGES),
        projectRepository = unavailable(SessionRoute.PROJECTS),
        projectWorkRepository = unavailable(SessionRoute.PROJECT_WORK),
        runRepository = unavailable(SessionRoute.RUNS),
        providerRepository = unavailable(SessionRoute.PROVIDERS),
        scheduleRepository = unavailable(SessionRoute.SCHEDULES),
        stepRepository = unavailable(SessionRoute.STEPS),
        toolRepository = unavailable(SessionRoute.TOOLS),
        vibesyncEventStreamRepository = unavailable(SessionRoute.VIBESYNC),
    )
}
