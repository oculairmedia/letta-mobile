package com.letta.mobile.ui.shell.workspace

import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellLayoutConfigStore
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ShellLayoutController] over the shared layout store, including the "restart" scenario:
 * `rememberSaveable` does not survive a process restart (desktop) or a reload (browser), so a second
 * controller reading the same preferences is how a restart restores the sidebar.
 */
class ShellLayoutControllerTest {
    private val preferences = InMemorySecureSettingsStore()

    private fun controller(backend: String) = ShellLayoutController(
        shellLayoutStore(backendConfig(backend), preferences),
        ShellLayoutConfigStore.forBackend(preferences, backendConfig(backend)),
    )

    private fun backendConfig(id: String) = LettaConfig(id = id, mode = LettaConfig.Mode.entries.first(), serverUrl = "")

    @Test
    fun explicitCollapseSurvivesASimulatedRestart() {
        val firstLaunch = controller("backend-a")
        assertFalse(firstLaunch.state.collapsedPreference)
        firstLaunch.dispatch(ShellLayoutEvent.ToggleSidebar)
        assertTrue(firstLaunch.state.collapsedPreference)

        val afterRestart = controller("backend-a")
        assertTrue(afterRestart.state.collapsedPreference)
        assertTrue(afterRestart.state.hasExplicitPreference)
    }

    @Test
    fun switchingBackendDoesNotInheritAnotherBackendsCollapsedState() {
        controller("backend-a").dispatch(ShellLayoutEvent.ToggleSidebar)

        assertFalse(controller("backend-b").state.collapsedPreference)
    }

    @Test
    fun narrowWidthAutoCollapseIsNotPersisted() {
        val narrowSession = controller("backend-a")
        narrowSession.dispatch(ShellLayoutEvent.WindowWidthChanged(400f))
        assertTrue(narrowSession.state.collapsedPreference)
        assertFalse(narrowSession.state.hasExplicitPreference)

        // A later launch on a wide window must not start collapsed because a previous one was narrow.
        val laterWideLaunch = controller("backend-a")
        laterWideLaunch.dispatch(ShellLayoutEvent.WindowWidthChanged(1400f))
        assertFalse(laterWideLaunch.state.collapsedPreference)
    }

    @Test
    fun savedLanesSurviveASimulatedRestartForTheirBackendOnly() {
        val notes = ShellLayoutConfig.Default.copy(
            lanes = ShellLayoutConfig.Default.lanes + (ShellLanes.SIDE to listOf(ModuleSlot(RuntimeViewId("test.notes")))),
        )
        controller("backend-a").lanes.save(notes)

        assertEquals(notes, controller("backend-a").lanes.config.value)
        assertEquals(ShellLayoutConfig.Default, controller("backend-b").lanes.config.value)
    }
}
