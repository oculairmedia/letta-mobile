package com.letta.mobile.desktop

import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.desktop.data.DesktopShellLayoutStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises [DesktopShellLayoutController] against a real (temp-directory)
 * [DesktopShellLayoutStore] — the production persistence binding, not a
 * fake — including the "restart" scenario: `rememberSaveable` does not
 * survive a desktop process restart, so a *second* controller instance
 * reading the same store is how this bead's restart-restoration AC is
 * actually satisfied.
 */
class DesktopShellLayoutControllerTest {
    @Test
    fun explicitCollapseSurvivesASimulatedRestart() {
        val dir = Files.createTempDirectory("shell-layout-controller-test")
        val store = DesktopShellLayoutStore(dir.resolve("shell-layout.properties"))

        val firstLaunch = DesktopShellLayoutController(backendConfigId = "backend-a", store = store)
        assertFalse(firstLaunch.state.collapsedPreference)
        firstLaunch.dispatch(ShellLayoutEvent.ToggleSidebar)
        assertTrue(firstLaunch.state.collapsedPreference)

        // Simulate a process restart: a brand new controller instance reading
        // the same on-disk store (a real desktop restart also drops all
        // rememberSaveable state, so nothing but the store can carry this).
        val afterRestart = DesktopShellLayoutController(backendConfigId = "backend-a", store = store)
        assertTrue(afterRestart.state.collapsedPreference)
        assertTrue(afterRestart.state.hasExplicitPreference)
    }

    @Test
    fun switchingBackendDoesNotInheritAnotherBackendsCollapsedState() {
        val dir = Files.createTempDirectory("shell-layout-controller-test")
        val store = DesktopShellLayoutStore(dir.resolve("shell-layout.properties"))

        val backendA = DesktopShellLayoutController(backendConfigId = "backend-a", store = store)
        backendA.dispatch(ShellLayoutEvent.ToggleSidebar)
        assertTrue(backendA.state.collapsedPreference)

        val backendB = DesktopShellLayoutController(backendConfigId = "backend-b", store = store)
        assertFalse(backendB.state.collapsedPreference)
    }

    @Test
    fun narrowWidthAutoCollapseIsNotPersisted() {
        val dir = Files.createTempDirectory("shell-layout-controller-test")
        val store = DesktopShellLayoutStore(dir.resolve("shell-layout.properties"))

        val narrowSession = DesktopShellLayoutController(backendConfigId = "backend-a", store = store)
        narrowSession.dispatch(ShellLayoutEvent.WindowWidthChanged(400f))
        assertTrue(narrowSession.state.collapsedPreference)
        assertFalse(narrowSession.state.hasExplicitPreference)

        // A later launch on a wide window must not start collapsed just
        // because a previous session happened to be narrow.
        val laterWideLaunch = DesktopShellLayoutController(backendConfigId = "backend-a", store = store)
        laterWideLaunch.dispatch(ShellLayoutEvent.WindowWidthChanged(1400f))
        assertFalse(laterWideLaunch.state.collapsedPreference)
    }
}
