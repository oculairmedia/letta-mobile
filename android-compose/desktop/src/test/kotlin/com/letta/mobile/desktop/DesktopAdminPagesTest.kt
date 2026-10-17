package com.letta.mobile.desktop

import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.runs.RunMonitorController
import com.letta.mobile.data.session.ifSupported
import com.letta.mobile.data.shell.ShellPageErrors
import com.letta.mobile.data.templates.AgentTemplatesController
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.desktop.data.desktopSessionGraphFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.45 / by4vm.13: a direct App Server desktop session serves no runs, and no
 * session without a backend serves agents. The pages ask the session's capabilities first and say
 * why, instead of calling a stand-in repository (or crashing on one).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAdminPagesTest {
    private val directAppServer = LettaConfig(id = "direct", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "ws://localhost:4500")

    @Test
    fun theRunsPageOverASessionWithoutRunsShowsWhyNotAnEmptyList() = runTest {
        val graph = desktopSessionGraphFactory(configProvider = { directAppServer }).create()
        val runs = graph.ifSupported(SessionRoute.RUNS) { runRepository }
        assertNull(runs, "a direct App Server session serves no runs")

        val controller = RunMonitorController(runs, backgroundScope, ShellPageErrors::message)
        controller.start()
        runCurrent()
        val state = controller.state.value
        assertEquals("This backend does not serve this page yet.", state.error)
        assertFalse(state.loading)
        assertTrue(state.runs.isEmpty())
        assertFalse(controller.canManageRuns)
        graph.close()
    }

    @Test
    fun theTemplatesPageOverASessionWithoutAgentsOffersNoCreate() = runTest {
        val graph = desktopSessionGraphFactory().create()
        val agents = graph.ifSupported(SessionRoute.AGENTS) { agentRepository }
        assertNull(agents, "no backend configured serves no agents")

        val controller = AgentTemplatesController(agents, scope = backgroundScope, errorMessageMapper = ShellPageErrors::message)
        assertEquals("This backend cannot create agents yet.", controller.state.value.createUnavailableReason)
        val opened = mutableListOf<String>()
        controller.create("default") { opened += it }
        runCurrent()
        assertTrue(opened.isEmpty())
        assertNull(controller.state.value.error)
        assertNull(controller.state.value.creatingId)
        graph.close()
    }

    @Test
    fun theUnifiedRouteFailureReadsAsTheNotServedReason() {
        assertEquals(
            "This backend does not serve this page yet.",
            ShellPageErrors.message(AppServerRouteUnavailable(SessionRoute.PROJECTS), "fallback"),
        )
        assertEquals("boom", ShellPageErrors.message(IllegalStateException("boom"), "fallback"))
    }
}
