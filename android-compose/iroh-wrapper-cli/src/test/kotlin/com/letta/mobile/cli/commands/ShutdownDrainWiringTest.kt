package com.letta.mobile.cli.commands

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The CLI's shutdown-drain wiring: the option's bound (clamped, defaulted to the value the systemd
 * unit's `TimeoutStopSec=360s` covers) and the second-signal cut. The drain scenarios themselves
 * (wait, bound, cut, refusal) are pinned in `:sharedLogic` against [com.letta.mobile.data.controller.node.iroh.NodeTurnHost]
 * and the endpoint's seam; here the CLI's own pieces are pinned.
 */
class ShutdownDrainWiringTest {
    @Test
    fun `the drain bound defaults to the unit's 300s and clamps negatives to zero`() {
        assertEquals(300.seconds, shutdownDrainBound(DEFAULT_SHUTDOWN_DRAIN_SECONDS))
        assertEquals(300.seconds, shutdownDrainBound(300))
        assertEquals(0.seconds, shutdownDrainBound(-1), "a negative bound closes at once instead of waiting backwards")
        assertEquals(0.seconds, shutdownDrainBound(0))
    }

    @Test
    fun `no endpoint means no drain and no cut signal registration`() = runBlocking {
        val registered = AtomicBoolean(false)
        drainActiveTurnsBeforeShutdown(
            endpoint = null,
            bound = 300.seconds,
            registerCutSignal = { registered.set(true); AutoCloseable { } },
        )
        assertFalse(registered.get(), "nothing to drain, so no signal handler is installed")
    }

    @Test
    fun `a second shutdown signal reaches the registered cut handler and closing restores the JVM's own`() {
        val cut = AtomicBoolean(false)
        val registration = registerShutdownCutSignals { cut.set(true) }
        try {
            sun.misc.Signal.raise(sun.misc.Signal("TERM"))
            val deadline = System.currentTimeMillis() + 5_000
            while (!cut.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(cut.get(), "the raised SIGTERM must reach the drain's cut")
        } finally {
            registration.close()
        }
        // After the drain, TERM belongs to the JVM again: raising it runs the JDK's own
        // handler (shutdown hooks), which a unit test must not trigger, so only assert the
        // registration is closed without error.
    }
}
