package com.letta.mobile.desktop.runtime

import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** letta-mobile-bzvro.3 (F03) and .4 (F04): crash supervision of the bundled runtime with a fake launcher. */
class DesktopRuntimeSupervisorTest {
    @Test
    fun `crashes restart with growing backoff`() = supervised { f ->
        f.supervisor.ensureStarted()
        f.latest.crash()
        assertTrue(f.supervisor.health.value.restartPending)

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, f.launches.size, "no restart before the 2 s backoff")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, f.launches.size)
        assertTrue(f.supervisor.health.value.active)

        f.latest.crash()
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(2, f.launches.size, "the second restart waits 4 s")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, f.launches.size)
        assertEquals(2, f.supervisor.health.value.consecutiveCrashes)
    }

    @Test
    fun `five crashes inside a minute give up until force restart`() = supervised { f ->
        f.supervisor.ensureStarted()
        // Each restart dies at once: crashes at 0, 2, 6, 14 and 30 s, all inside one minute.
        listOf(2_000L, 4_000L, 8_000L, 16_000L).forEach { backoff ->
            f.latest.crash()
            advanceTimeBy(backoff)
            runCurrent()
        }
        assertEquals(5, f.launches.size)
        f.supervisor.onStderr("FATAL ERROR: heap out of memory")
        f.latest.crash(exitCode = 9)
        advanceTimeBy(120_000)
        runCurrent()

        val health = f.supervisor.health.value
        assertTrue(health.gaveUp)
        assertTrue(health.needsForceRestart)
        assertEquals(9, health.lastExitCode)
        assertEquals(5, f.launches.size, "no restart after giving up")

        f.supervisor.forceRestart()
        runCurrent()
        assertEquals(6, f.launches.size)
        assertFalse(f.supervisor.health.value.gaveUp)
        assertEquals(0, f.supervisor.health.value.consecutiveCrashes)
    }

    @Test
    fun `a minute of healthy uptime resets the backoff`() = supervised { f ->
        f.supervisor.ensureStarted()
        f.latest.crash()
        advanceTimeBy(2_000)
        runCurrent()
        f.latest.crash()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(3, f.launches.size)

        advanceTimeBy(60_000)
        f.latest.crash()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(4, f.launches.size, "back to the 2 s first-crash delay")
        assertEquals(1, f.supervisor.health.value.consecutiveCrashes)
    }

    @Test
    fun `intentional stops and clean exits never restart`() = supervised { f ->
        f.supervisor.ensureStarted()
        f.manager.close()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, f.launches.size)
        assertFalse(f.supervisor.health.value.restartPending)

        assertFalse(f.supervisor.health.value.needsForceRestart, "our own stop needs nothing from the user")

        f.supervisor.ensureStarted()
        f.latest.crash(exitCode = 0)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, f.launches.size)
        assertTrue(f.supervisor.health.value.needsForceRestart, "an unasked-for clean exit offers Restart")
    }

    @Test
    fun `nothing restarts once no lease holder wants the runtime`() = supervised { f ->
        f.supervisor.ensureStarted()
        f.wanted = false
        f.latest.crash()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, f.launches.size)
    }

    @Test
    fun `stderr tail is kept and flushed to the log on a crash`() = supervised { f ->
        f.supervisor.ensureStarted()
        (1..12).forEach { f.supervisor.onStderr("err $it") }
        f.latest.crash()

        assertEquals((3..12).map { "err $it" }, f.supervisor.health.value.recentStderr)
        assertTrue(f.log.any { it == "[supervisor] stderr: err 12" })
    }

    @Test
    fun `a crash during sleep is not counted and wake restarts at once`() = supervised { f ->
        f.supervisor.ensureStarted()
        f.latest.crash()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, f.supervisor.health.value.consecutiveCrashes)

        f.supervisor.onSuspended()
        f.latest.crash()
        assertFalse(f.supervisor.health.value.restartPending, "exits during sleep are not crashes")
        f.supervisor.onResumed()
        runCurrent()

        assertEquals(3, f.launches.size, "wake restarted the dead child without backoff")
        assertEquals(0, f.supervisor.health.value.consecutiveCrashes)
    }

    @Test
    fun `supervised restarts are announced for the chat to reconnect`() = supervised { f ->
        val urls = mutableListOf<String>()
        backgroundScope.launch { f.supervisor.restarted.collect { urls += it } }
        runCurrent()
        f.supervisor.ensureStarted()
        f.latest.crash()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(listOf("ws://127.0.0.1:40002"), urls, "lease-driven starts are not announced, restarts are")
    }

    @Test
    fun `a restart that dies before ready counts as a crash`() = supervised { f ->
        f.supervisor.ensureStarted()
        f.failNextStart = true
        f.latest.crash()
        advanceTimeBy(2_000)
        runCurrent()

        val health = f.supervisor.health.value
        assertEquals(2, health.consecutiveCrashes)
        assertTrue(health.restartPending)
        assertTrue(health.lastError.orEmpty().contains("exit=3"))
    }

    private class Fixture(root: File, scope: TestScope) {
        val launches = mutableListOf<FakeProcess>()
        val log = mutableListOf<String>()
        var wanted = true
        var failNextStart = false
        val latest: FakeProcess get() = launches.last()
        val manager = DesktopLocalRuntimeManager(
            installationProvider = {
                DesktopLettaCodeInstallation(
                    File(root, "node.exe").apply { createNewFile() },
                    File(root, "letta.js").apply { createNewFile() },
                )
            },
            backendDirectory = { File(root, "backend") },
            processLauncher = DesktopRuntimeProcessLauncher {
                val process = if (failNextStart) {
                    failNextStart = false
                    FakeProcess(stdoutText = "", alive = false, exitCode = 3)
                } else {
                    FakeProcess(stdoutText = "Listening on ws://127.0.0.1:${40_001 + launches.size}\n")
                }
                process.also { launches += it }
            },
            logLine = {},
            readyTimeoutMs = 2_000,
            stopTimeoutMs = 1,
        )
        val supervisor = DesktopRuntimeSupervisor(
            manager = manager,
            scope = scope.backgroundScope,
            wanted = { wanted },
            logLine = { log += it },
            clockMs = { scope.testScheduler.currentTime },
        )
    }

    private fun supervised(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        val root = createTempDirectory("desktop-runtime-supervisor").toFile()
        try {
            block(Fixture(root, this))
        } finally {
            root.deleteRecursively()
        }
    }

    private class FakeProcess(
        stdoutText: String,
        alive: Boolean = true,
        private val exitCode: Int? = null,
    ) : DesktopRuntimeProcess {
        override val stdout = BufferedReader(StringReader(stdoutText))
        override val stderr = BufferedReader(StringReader(""))
        override val descendants: List<DesktopRuntimeProcessHandle> = emptyList()
        var alive = alive
        private var code: Int? = exitCode
        private var exitCallback: ((Int?) -> Unit)? = null
        override val isAlive: Boolean get() = alive
        override val exitCodeOrNull: Int? get() = code.takeIf { !alive }
        override fun waitFor(timeoutMs: Long): Boolean = !alive
        override fun onExit(callback: (exitCode: Int?) -> Unit) {
            if (!alive) callback(code) else exitCallback = callback
        }

        override fun destroy() = end(1)
        override fun destroyForcibly() = end(137)

        /** The child died on its own. */
        fun crash(exitCode: Int = 1) = end(exitCode)

        private fun end(exitCode: Int) {
            if (!alive) return
            alive = false
            code = exitCode
            exitCallback?.invoke(exitCode)
        }
    }
}
