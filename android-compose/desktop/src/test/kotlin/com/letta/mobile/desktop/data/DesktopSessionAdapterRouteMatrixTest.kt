package com.letta.mobile.desktop.data

import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.SessionRouteMatrix
import com.letta.mobile.data.backend.isUnavailable
import com.letta.mobile.data.backend.unavailableRouteOf
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.desktop.desktopHttpApisFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * letta-mobile-by4vm.13: the desktop factory binds exactly the desktop row of the
 * [SessionRouteMatrix] for every backend kind. This replaces the "adding a backend means editing
 * three factories" note: a new [BackendKind] or [SessionRoute] fails to compile here ([configFor],
 * [serves]) until it is decided, then fails here until the factory binds what the row says.
 */
class DesktopSessionAdapterRouteMatrixTest {
    private val kinds = listOf<BackendKind?>(null) + BackendKind.entries

    @Test
    fun theDesktopFactoryBindsExactlyItsRowOfTheRouteMatrix() {
        for (kind in kinds) {
            val adapters = DesktopRepositoryAdapters(configFor(kind), channelTransport = NoOpChannelTransport())
            val row = SessionRouteMatrix.bound(SessionPlatform.DESKTOP, kind)

            assertEquals(row, SessionRoute.entries.filter { serves(adapters, it) }.toSet(), "desktop bindings x $kind")
            assertEquals(row, adapters.capabilities.bound, "desktop capabilities x $kind")
            adapters.closeables.forEach { it.close() }
        }
    }

    @Test
    fun anUnservedRouteThrowsItsRouteEvenForAPropertyRead() {
        // The old desktop stand-in answered `agents` with an empty list; now every member says why.
        val adapters = DesktopRepositoryAdapters(configFor(null))
        val error = assertFailsWith<AppServerRouteUnavailable> { adapters.agentRepository.agents }
        assertEquals(SessionRoute.AGENTS, error.route)
        assertEquals(SessionRoute.AGENTS, unavailableRouteOf(adapters.agentRepository))
        adapters.closeables.forEach { it.close() }
    }

    private fun configFor(kind: BackendKind?): LettaConfig? = when (kind) {
        null -> null
        BackendKind.LOCAL_RUNTIME -> LettaConfig(id = "local", mode = LettaConfig.Mode.LOCAL, serverUrl = "")
        BackendKind.DIRECT_WS -> LettaConfig(id = "direct", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "ws://localhost:4500")
        BackendKind.IROH -> LettaConfig(id = "iroh", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = IROH_URL)
    }

    /** Whether the factory bound something real for [route] (not the [isUnavailable] stand-in). */
    private fun serves(adapters: DesktopRepositoryAdapters, route: SessionRoute): Boolean = when (route) {
        SessionRoute.AGENTS -> bound(adapters.agentRepository)
        SessionRoute.BLOCKS -> bound(adapters.blockRepository)
        SessionRoute.CONVERSATIONS -> bound(adapters.conversationRepository)
        SessionRoute.CONVERSATION_RECOMPILE ->
            bound(adapters.conversationRepository) && adapters.conversationRepository.canRecompileConversations()
        SessionRoute.MCP_SERVERS -> bound(adapters.mcpServerRepository)
        SessionRoute.MODELS -> bound(adapters.modelRepository)
        SessionRoute.PASSAGES -> bound(adapters.passageRepository)
        SessionRoute.PROJECTS -> bound(adapters.projectRepository)
        SessionRoute.PROJECT_WORK -> bound(adapters.projectWorkRepository)
        SessionRoute.PROVIDERS -> bound(adapters.providerRepository)
        SessionRoute.RUNS -> bound(adapters.runRepository)
        SessionRoute.SCHEDULES -> bound(adapters.scheduleRepository)
        SessionRoute.SKILLS ->
            desktopHttpApisFor(adapters.servesIrohRelays, IrohAdminRpcAgentDirectory(NoOpChannelTransport())).skillApi != null
        SessionRoute.STEPS -> bound(adapters.stepRepository)
        SessionRoute.TOOLS -> bound(adapters.toolRepository)
        SessionRoute.VIBESYNC -> bound(adapters.vibesyncEventStreamRepository)
    }

    private fun bound(repository: Any): Boolean = !isUnavailable(repository)

    private companion object {
        const val IROH_URL = "iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f@192.168.50.90:4501"
    }
}
