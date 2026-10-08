package com.letta.mobile.desktop

import com.letta.mobile.data.runtime.supervisor.RuntimeHealth
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.desktop.runtime.DesktopRuntimeLaunchPreference
import com.letta.mobile.desktop.runtime.DesktopRuntimeLaunchSettings
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** letta-mobile-bzvro.3 / .5: the Local runtime settings card's state holder. */
class DesktopLocalRuntimeControllerTest {
    private val root = createTempDirectory("desktop-runtime-controller").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        DesktopRuntimeLaunchPreference.workingDirectory = null
    }

    private class FakeBindings : DesktopLocalRuntimeBindings {
        override val health: StateFlow<RuntimeHealth> = MutableStateFlow(RuntimeHealth())
        var forceRestarts = 0

        override fun forceRestart() {
            forceRestarts += 1
        }

        override fun currentLaunchState(store: SecureSettingsStore) = DesktopRuntimeLaunchState(
            instanceId = "install-1",
            workingDirectory = DesktopRuntimeLaunchSettings.readWorkingDirectory(store),
            effectiveWorkingDirectory = DesktopRuntimeLaunchSettings.readWorkingDirectory(store) ?: "documents",
            heapMb = 8_192,
        )
    }

    private class InMemoryStore : SecureSettingsStore {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() = values.clear()
    }

    private fun TestScope.controller(bindings: FakeBindings, onRestart: () -> Unit) =
        DesktopLocalRuntimeController(InMemoryStore(), this, onRestart, bindings, io = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))

    @Test
    fun aValidDirectoryIsSavedAndRestartsTheRuntime() = runTest(StandardTestDispatcher()) {
        var restarts = 0
        val controller = controller(FakeBindings()) { restarts += 1 }
        val work = java.io.File(root, "work").apply { mkdirs() }

        controller.changeWorkingDirectory(work.path)
        advanceUntilIdle()

        assertEquals(work.absolutePath, controller.launch.value.workingDirectory)
        assertEquals(1, restarts)
        assertEquals(false, controller.launch.value.isError)
    }

    @Test
    fun aMissingDirectoryIsRejectedWithoutARestart() = runTest(StandardTestDispatcher()) {
        var restarts = 0
        val controller = controller(FakeBindings()) { restarts += 1 }

        controller.changeWorkingDirectory(java.io.File(root, "missing").path)
        advanceUntilIdle()

        assertTrue(controller.launch.value.isError)
        assertEquals(0, restarts)
    }

    @Test
    fun forceRestartGoesToTheSupervisor() = runTest {
        val bindings = FakeBindings()
        controller(bindings) {}.forceRestart()

        assertEquals(1, bindings.forceRestarts)
    }

    @Test
    fun healthDescriptionsSayWhatTheUserCanDo() {
        assertTrue(RuntimeHealth(gaveUp = true, consecutiveCrashes = 5).describe().contains("Automatic restart is off"))
        assertTrue(RuntimeHealth(restartPending = true, lastExitCode = 1).describe().startsWith("Crashed (exit 1)"))
        assertEquals("Running.", RuntimeHealth(active = true).describe())
    }
}
