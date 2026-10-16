package com.letta.mobile.data.session.graph

import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.SessionRouteMatrix
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.repository.iroh.FakeIrohAdminTransport
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.transport.appserver.AppServerClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-ckmub.17.1: desktop and the browser build their session graph with the one
 * [HostSessionGraphFactory], so each binds exactly its row of the [SessionRouteMatrix] for every
 * backend kind. Probed through the repositories themselves, as Android's matrix test does: a route
 * the row leaves out fails with [AppServerRouteUnavailable]; a route it serves reaches its source
 * (which these fakes answer with an error of their own).
 */
class HostSessionGraphFactoryTest {
    private val kinds = listOf<BackendKind?>(null) + BackendKind.entries

    @Test
    fun desktopAndTheBrowserBindExactlyTheirRowOfTheRouteMatrix() = runTest {
        for (platform in listOf(SessionPlatform.DESKTOP, SessionPlatform.WEB)) {
            for (kind in kinds) {
                val adapters = HostRepositoryAdapters(bindingFor(platform, configFor(kind)))
                val row = SessionRouteMatrix.bound(platform, kind)

                val served = SessionRoute.entries.filter { route -> serves { probe(route, adapters) } }.toSet()

                assertEquals(row, served, "$platform bindings x $kind")
                assertEquals(row, adapters.capabilities.bound, "$platform capabilities x $kind")
                adapters.closeables.forEach { it.close() }
            }
        }
    }

    @Test
    fun anUnservedRouteNamesItsRoute() = runTest {
        val adapters = HostRepositoryAdapters(bindingFor(SessionPlatform.WEB, configFor(null)))
        val error = assertFailsWith<AppServerRouteUnavailable> { adapters.agentRepository.refreshAgents() }
        assertEquals(SessionRoute.AGENTS, error.route)
        assertNull(adapters.blockRepository)
        adapters.closeables.forEach { it.close() }
    }

    @Test
    fun eachGraphIsANewGenerationNamedForItsPlatform() {
        val config = configFor(BackendKind.IROH)
        val factory = HostSessionGraphFactory { bindingFor(SessionPlatform.WEB, config) }

        val first = factory.create()
        val second = factory.create()

        assertEquals(1L, first.id)
        assertEquals(2L, second.id)
        assertEquals("web-remote-letta:iroh", second.backendDescriptor.backendId.value)
        assertFalse(first.isClosed)
        first.close()
        second.close()
        assertTrue(first.isClosed && second.isClosed)
    }

    private fun bindingFor(platform: SessionPlatform, config: LettaConfig?): HostSessionBinding {
        val transport = FakeIrohAdminTransport()
        val directory = IrohAdminRpcAgentDirectory(transport)
        return HostSessionBinding(
            platform = platform,
            config = config,
            appServerClients = HostAppServerClients { kind ->
                val reachable = kind == BackendKind.DIRECT_WS || platform == SessionPlatform.DESKTOP && kind == BackendKind.LOCAL_RUNTIME
                if (reachable) NO_APP_SERVER_IN_TEST else null
            },
            channelTransport = transport,
            irohAgentDirectoryProvider = { directory },
        )
    }

    private fun configFor(kind: BackendKind?): LettaConfig? = when (kind) {
        null -> null
        BackendKind.LOCAL_RUNTIME -> LettaConfig(id = "local", mode = LettaConfig.Mode.LOCAL, serverUrl = "")
        BackendKind.DIRECT_WS -> LettaConfig(id = "direct", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "ws://localhost:4500")
        BackendKind.IROH -> LettaConfig(id = "iroh", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = IROH_URL)
    }

    /** One read per route, through the repositories the factory bound. */
    private suspend fun probe(route: SessionRoute, adapters: HostRepositoryAdapters): Any? {
        val host = adapters.hostRepositories()
        return when (route) {
            SessionRoute.AGENTS -> host.agentRepository.refreshAgents()
            SessionRoute.BLOCKS -> host.blockRepository?.getBlocks(AGENT) ?: unserved(route)
            SessionRoute.CONVERSATIONS -> host.conversationRepository.getCachedConversations(AgentId(AGENT))
            SessionRoute.CONVERSATION_RECOMPILE ->
                host.conversationRepository.canRecompileConversations().takeIf { it } ?: unserved(route)
            SessionRoute.SKILLS -> adapters.servesIrohRelays.takeIf { it } ?: unserved(route)
            SessionRoute.STEPS -> host.stepRepository.supportsStepQueries.takeIf { it } ?: unserved(route)
            SessionRoute.VIBESYNC -> host.vibesyncEventStreamRepository.start()
            else -> probeAdmin(route, host)
        }
    }

    /** The admin, schedule and project-work routes: one refresh each. */
    private suspend fun probeAdmin(route: SessionRoute, host: HostSessionRepositories): Any? {
        return when (route) {
            SessionRoute.MCP_SERVERS -> host.mcpServerRepository.refreshServers()
            SessionRoute.MODELS -> host.modelRepository.refreshLlmModels()
            SessionRoute.PASSAGES -> host.passageRepository.refreshPassages(AgentId(AGENT))
            SessionRoute.PROJECTS -> host.projectRepository.refreshProjects()
            SessionRoute.PROJECT_WORK -> host.projectWorkRepository.refreshReadyWork("project-1")
            SessionRoute.PROVIDERS -> host.providerRepository.refreshProviders()
            SessionRoute.RUNS -> host.runRepository.refreshRuns(RunListParams())
            SessionRoute.SCHEDULES -> host.scheduleRepository.refreshSchedules(AGENT)
            SessionRoute.TOOLS -> host.toolRepository.refreshTools()
            else -> error("$route is probed by probe()")
        }
    }

    private fun unserved(route: SessionRoute): Nothing = throw AppServerRouteUnavailable(route, "probe")

    /** True unless [read] fails with the route backstop (anywhere in its cause chain). */
    private suspend fun serves(read: suspend () -> Any?): Boolean {
        val failure = try {
            read()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            error
        }
        return generateSequence(failure) { it.cause }.none { it is AppServerRouteUnavailable }
    }

    private companion object {
        const val AGENT = "agent-1"
        const val IROH_URL = "iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f@192.168.50.90:4501"
        val NO_APP_SERVER_IN_TEST: suspend () -> AppServerClient = { error("no App Server in this test") }
    }
}
