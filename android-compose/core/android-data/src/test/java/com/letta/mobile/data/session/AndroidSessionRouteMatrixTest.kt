package com.letta.mobile.data.session

import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.SessionRouteMatrix
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.IrohAdminRpcBlockSource
import com.letta.mobile.data.repository.SlashCommandRepository
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeSettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * letta-mobile-by4vm.13: the Android session factory binds exactly the Android row of the
 * [SessionRouteMatrix] for every backend kind, probed through the graph's own repositories: a route
 * the row leaves out fails with [AppServerRouteUnavailable], and a route it serves never does (it
 * reaches the on-device or Iroh source, which this fake transport answers with an error of its own).
 *
 * A new [BackendKind] or [SessionRoute] fails to compile here ([configFor], [probe]) until it is
 * decided, then fails here until the assembler binds what the row says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSessionRouteMatrixTest {
    private val kinds = listOf<BackendKind?>(null) + BackendKind.entries

    @Test
    fun theAndroidFactoryBindsExactlyItsRowOfTheRouteMatrix() = runTest {
        for (kind in kinds) {
            val config = configFor(kind)
            val transport = FakeChannelTransport().apply {
                // Every host feature a route can need, so the row alone decides.
                advertisedCapabilities = SessionRoute.entries.mapNotNull { it.hostCapability }.toSet()
            }
            val settings = FakeSettingsRepository(initialActiveConfig = config)
            val graph = assemble(config, transport, settings)
            val row = SessionRouteMatrix.bound(SessionPlatform.ANDROID, kind)

            val served = SessionRoute.entries.filter { route -> serves { probe(route, graph, transport, settings) } }.toSet()

            assertEquals("android bindings x $kind", row, served)
            assertEquals("android capabilities x $kind", row, SessionRoute.entries.filter(graph.capabilities::supports).toSet())
        }
    }

    @Test
    fun aHostWithoutTheRecompileFeatureDoesNotOfferIt() = runTest {
        val config = configFor(BackendKind.IROH)
        val transport = FakeChannelTransport()
        val graph = assemble(config, transport, FakeSettingsRepository(initialActiveConfig = config))

        assertEquals(false, graph.capabilities.supports(SessionRoute.CONVERSATION_RECOMPILE))
        assertEquals(false, graph.conversationRepository.canRecompileConversations())
    }

    private fun TestScope.assemble(
        config: LettaConfig?,
        transport: FakeChannelTransport,
        settings: FakeSettingsRepository,
    ): SessionGraph = SessionGraphAssembler(
        agentDao = dagger.Lazy<AgentDao> { FakeAgentDao() },
        conversationDao = dagger.Lazy<ConversationDao> { FakeConversationDao() },
        // Production: SessionRemoteSources.None, the default.
        blockRepository = BlockRepository(IrohAdminRpcBlockSource(transport, settings)),
        localConversationSource = mockk(relaxed = true),
        localAgentSource = mockk(relaxed = true),
        localModelSource = mockk(relaxed = true),
    ).assemble(
        SessionGraphAssembleRequest(
            graphId = 1L,
            activeConfig = config,
            localRuntimeBackend = null,
            scope = backgroundScope,
            channelTransport = transport,
            settingsRepository = settings,
        ),
    )

    private fun configFor(kind: BackendKind?): LettaConfig? = when (kind) {
        null -> null
        BackendKind.LOCAL_RUNTIME -> LettaConfig(id = "local", mode = LettaConfig.Mode.LOCAL, serverUrl = "local-lettacode://runtime")
        BackendKind.REST -> LettaConfig(id = "direct", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "ws://localhost:4500")
        BackendKind.IROH -> LettaConfig(id = "iroh", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = IROH_URL)
    }

    /** One read per route, through the graph the factory built; returns what the read returned. */
    private suspend fun probe(
        route: SessionRoute,
        graph: SessionGraph,
        transport: FakeChannelTransport,
        settings: FakeSettingsRepository,
    ): Any? = when (route) {
        SessionRoute.AGENTS -> graph.agentRepository.listAgentSummaries()
        SessionRoute.BLOCKS -> graph.blockRepository?.getBlocks(AGENT) ?: unserved(route)
        SessionRoute.CONVERSATIONS -> graph.allConversationsRepository.refresh()
        SessionRoute.CONVERSATION_RECOMPILE ->
            graph.conversationRepository.canRecompileConversations().takeIf { it } ?: unserved(route)
        SessionRoute.MCP_SERVERS -> graph.mcpServerRepository.refreshServers()
        SessionRoute.MODELS -> graph.modelRepository.refreshLlmModels()
        SessionRoute.PASSAGES -> graph.passageRepository.refreshPassages(AgentId(AGENT))
        SessionRoute.PROJECTS -> graph.projectRepository.refreshProjects()
        SessionRoute.PROJECT_WORK -> graph.projectWorkRepository.refreshReadyWork("project-1")
        SessionRoute.PROVIDERS -> graph.providerRepository.refreshProviders()
        SessionRoute.RUNS -> graph.runRepository.refreshRuns(RunListParams())
        SessionRoute.SCHEDULES -> graph.scheduleRepository.refreshSchedules(AGENT)
        // Skills ride the app-wide slash-command repository, over the same transport and settings.
        SessionRoute.SKILLS -> SlashCommandRepository(settings, transport).listGlobal().getOrThrow()
        SessionRoute.STEPS -> graph.stepRepository.supportsStepQueries.takeIf { it } ?: unserved(route)
        SessionRoute.TOOLS -> graph.toolRepository.refreshTools()
        // No backend carries the VibeSync stream: the graph binds a source that always says so.
        SessionRoute.VIBESYNC -> unserved(route)
    }

    private fun unserved(route: SessionRoute): Nothing = throw AppServerRouteUnavailable(route, "probe")

    /** True unless [read] fails with the route backstop (anywhere in its cause chain). */
    private suspend fun serves(read: suspend () -> Any?): Boolean {
        val failure = try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) { read() }
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
        const val PROBE_TIMEOUT_MS = 5_000L
        const val IROH_URL = "iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f@192.168.50.90:4501"
    }
}
