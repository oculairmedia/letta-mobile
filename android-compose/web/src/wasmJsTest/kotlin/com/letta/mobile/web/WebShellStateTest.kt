package com.letta.mobile.web

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.web.data.AgentItemState
import com.letta.mobile.web.data.cleanBrowserErrorMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** letta-mobile-ckmub.8: the web container's state as the shared navigation drawer sees it. */
class WebShellStateTest {
    private val config = LettaConfig(id = "default", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "")
    private val now = Instant.parse("2026-10-11T12:00:00Z")
    private val nora = AgentItemState(id = "agent-1", name = "Nora")
    private val ada = AgentItemState(id = "agent-2", name = "Ada")
    private val tom = AgentItemState(id = "agent-3", name = "Tom")

    @Test
    fun aLoadedRosterOpensTheFirstAgentAndRailsTheRest() {
        val shell = WebShellState(config)
        shell.rosterLoaded(listOf(nora, ada, tom))

        val drawer = shell.navDrawerState(now)

        assertEquals("Nora", drawer.panel.agent.name)
        assertEquals(listOf("Ada", "Tom"), drawer.rail.entries.map { it.name })
        assertTrue(drawer.panel.hiddenSections.containsAll(WebHiddenSections))
    }

    @Test
    fun aRefreshKeepsTheSelectedAgentWhileItIsStillThere() {
        val shell = WebShellState(config)
        shell.rosterLoaded(listOf(nora, ada))
        shell.selectAgent(ada.id)

        shell.rosterLoaded(listOf(nora, ada, tom))
        assertEquals(ada.id, shell.selectedAgent?.id)

        shell.rosterLoaded(listOf(tom))
        assertEquals(tom.id, shell.selectedAgent?.id)
    }

    @Test
    fun railAndPanelNavigateAndCloseTheDrawer() {
        val shell = WebShellState(config)
        shell.rosterLoaded(listOf(nora, ada))
        var closed = 0
        val actions = shell.navDrawerActions(afterNavigate = { closed += 1 })

        actions.panel.onOpenSettings()
        assertEquals(ShellDestination.Settings(), shell.destination)
        assertTrue(shell.navDrawerState(now).panel.settingsSelected)

        actions.rail.onHome()
        assertTrue(shell.navDrawerState(now).rail.homeSelected)

        actions.rail.onAgentSelected(ada.id)
        assertEquals(ShellDestination.Chat(ada.id), shell.destination)
        assertEquals(ada.id, shell.selectedAgent?.id)
        assertEquals(3, closed)
    }

    // letta-mobile-ckmub.3.4: the web's route model is the canonical ShellRoute in the URL fragment.
    @Test
    fun theRouteRoundTripsThroughTheUrlFragment() {
        val shell = WebShellState(config)
        shell.rosterLoaded(listOf(nora, ada))
        shell.selectAgent(ada.id)
        assertEquals("chat?agent=agent-2", shell.route)

        val reopened = WebShellState(config)
        assertTrue(reopened.openRoute(shell.route))
        reopened.rosterLoaded(listOf(nora, ada))
        assertEquals(ada.id, reopened.selectedAgent?.id)
        assertEquals(shell.route, reopened.route)

        assertTrue(reopened.openRoute("settings"))
        assertEquals(ShellDestination.Settings(), reopened.destination)
        reopened.closeSettings()
        assertEquals(ShellDestination.Chat(ada.id), reopened.destination)

        assertFalse(reopened.openRoute("nowhere"))
        assertFalse(reopened.openRoute(""))
        assertEquals(ShellDestination.Chat(ada.id), reopened.destination)
    }

    @Test
    fun aLostRosterClearsTheSelection() {
        val shell = WebShellState(config)
        shell.rosterLoaded(listOf(nora))
        shell.rosterLost()

        assertNull(shell.selectedAgent)
        assertEquals("Letta", shell.navDrawerState(now).panel.agent.name)
        assertFalse(shell.navDrawerState(now).panel.home)
    }

    @Test
    fun aJsErrorReadsAsItsMessage() {
        assertEquals(
            "invalid Iroh endpoint id: invalid length",
            cleanBrowserErrorMessage("Non-Kotlin exception Error: invalid Iroh endpoint id: invalid length of type 'class Error'"),
        )
        assertEquals("Connection closed", cleanBrowserErrorMessage("Connection closed"))
    }
}
