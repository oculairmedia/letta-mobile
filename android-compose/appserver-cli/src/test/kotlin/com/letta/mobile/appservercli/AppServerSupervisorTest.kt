package com.letta.mobile.appservercli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppServerSupervisorTest {

    // Fake clock advanced by the injected sleep, so bounded timeouts are
    // exercised deterministically without real waiting.
    private class FakeClock {
        var nowMs = 0L
        val sleep: (Long) -> Unit = { nowMs += it }
        val now: () -> Long = { nowMs }
    }

    /**
     * Programmable process: [readyAfterMs] gates readiness relative to the fake
     * clock; [exitAfterMs]/[exitCode] simulate a child exiting.
     */
    private class FakeProcess(
        private val clock: FakeClock,
        private val readyAfterMs: Long? = null,
        private val exitAfterMs: Long? = null,
        private val exitCode: Int = 0,
        private val gracefulExitAfterMs: Long? = 0,
        val diagnostics: String = "",
    ) : AppServerSupervisor.ProcessController {
        var spawned = false
        var terminatedGracefully = false
        var destroyedTree = false
        private var spawnAtMs = 0L
        private var terminateAtMs: Long? = null

        override fun spawn() { spawned = true; spawnAtMs = clock.nowMs }
        override fun exitCodeOrNull(): Int? {
            val elapsed = clock.nowMs - spawnAtMs
            if (exitAfterMs != null && elapsed >= exitAfterMs) return exitCode
            val t = terminateAtMs
            if (t != null && gracefulExitAfterMs != null && clock.nowMs - t >= gracefulExitAfterMs) return 143
            return null
        }
        override fun terminateGracefully() { terminatedGracefully = true; terminateAtMs = clock.nowMs }
        override fun destroyTree() { destroyedTree = true }
        override fun drainDiagnostics(limit: Int): String = diagnostics.take(limit)
        fun readyProbe(): AppServerSupervisor.ReadinessProbe =
            AppServerSupervisor.ReadinessProbe { readyAfterMs != null && clock.nowMs - spawnAtMs >= readyAfterMs }
    }

    private val fastConfig = SupervisorConfig(
        startupTimeoutMs = 5_000,
        gracefulShutdownMs = 1_000,
        readinessPollIntervalMs = 100,
        shutdownPollIntervalMs = 100,
    )

    @Test
    fun `start returns Ready once readiness passes`() {
        val clock = FakeClock()
        val proc = FakeProcess(clock, readyAfterMs = 300)
        val supervisor = AppServerSupervisor(proc, proc.readyProbe(), fastConfig, clock.sleep, clock.now)
        val outcome = supervisor.start()
        assertEquals(AppServerSupervisor.StartupOutcome.Ready, outcome)
        assertTrue(proc.spawned)
    }

    @Test
    fun `child exit before readiness reports bounded diagnostics`() {
        val clock = FakeClock()
        val proc = FakeProcess(clock, exitAfterMs = 200, exitCode = 1, diagnostics = "boom stacktrace")
        val supervisor = AppServerSupervisor(proc, proc.readyProbe(), fastConfig, clock.sleep, clock.now)
        val outcome = supervisor.start()
        val failure = assertInstanceOf(AppServerSupervisor.StartupOutcome.ExitedBeforeReady::class.java, outcome)
        assertEquals(1, failure.exitCode)
        assertEquals("boom stacktrace", failure.diagnostics)
    }

    @Test
    fun `startup timeout stops the half-started process`() {
        val clock = FakeClock()
        // Never ready, never exits on its own.
        val proc = FakeProcess(clock, readyAfterMs = null, gracefulExitAfterMs = 0)
        val supervisor = AppServerSupervisor(proc, proc.readyProbe(), fastConfig, clock.sleep, clock.now)
        val outcome = supervisor.start()
        assertInstanceOf(AppServerSupervisor.StartupOutcome.StartupTimedOut::class.java, outcome)
        assertTrue(proc.terminatedGracefully, "timed-out startup must stop the process")
    }

    @Test
    fun `graceful stop escalates to force kill after deadline and leaves no descendants`() {
        val clock = FakeClock()
        // Process ignores graceful terminate (never exits gracefully).
        val proc = FakeProcess(clock, readyAfterMs = 0, gracefulExitAfterMs = null)
        val supervisor = AppServerSupervisor(proc, proc.readyProbe(), fastConfig, clock.sleep, clock.now)
        supervisor.start()
        supervisor.stop()
        assertTrue(proc.terminatedGracefully)
        assertTrue(proc.destroyedTree, "must force-kill the tree after graceful deadline")
    }

    @Test
    fun `graceful stop reaps tree even on clean exit`() {
        val clock = FakeClock()
        val proc = FakeProcess(clock, readyAfterMs = 0, gracefulExitAfterMs = 200)
        val supervisor = AppServerSupervisor(proc, proc.readyProbe(), fastConfig, clock.sleep, clock.now)
        supervisor.start()
        supervisor.stop()
        assertTrue(proc.destroyedTree, "must reap stragglers even after graceful exit")
    }

    @Test
    fun `stop on already-exited process is a no-op`() {
        val clock = FakeClock()
        val proc = FakeProcess(clock, readyAfterMs = 0, exitAfterMs = 0)
        val events = mutableListOf<AppServerSupervisor.SupervisorLifecycleEvent>()
        val supervisor = AppServerSupervisor(proc, proc.readyProbe(), fastConfig, clock.sleep, clock.now) { events += it }
        // exitAfterMs=0 means it exits immediately; stop should short-circuit.
        supervisor.stop()
        assertFalse(proc.terminatedGracefully)
        assertTrue(events.any { it is AppServerSupervisor.SupervisorLifecycleEvent.AlreadyStopped })
    }

    @Test
    fun `config rejects non-positive timeouts`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            SupervisorConfig(startupTimeoutMs = 0)
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            SupervisorConfig(gracefulShutdownMs = -1)
        }
    }
}
