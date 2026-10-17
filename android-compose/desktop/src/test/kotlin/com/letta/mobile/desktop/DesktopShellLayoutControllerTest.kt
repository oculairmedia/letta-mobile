package com.letta.mobile.desktop

import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DesktopShellLayoutController] over the shared layout store, including the "restart" scenario:
 * `rememberSaveable` does not survive a desktop process restart, so a second controller reading
 * the same preferences is how a restart restores the sidebar.
 */
class DesktopShellLayoutControllerTest {
    private val preferences = InMemorySecureSettingsStore()

    private fun controller(backend: String) = DesktopShellLayoutController(desktopShellLayoutStore(backend, preferences))

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
}
