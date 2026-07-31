package com.letta.mobile.cli.commands

import com.letta.mobile.cli.probe.NoHttpWrapperEvidence
import com.letta.mobile.data.transport.iroh.IrohProbeTurnMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * lgns8.21.9 — the no-http turn must carry wrapper attribution into the probe
 * summary (CI/deployment output), and unattributable windows must surface as
 * violations instead of a green turn.
 */
class NoHttpProbeEvidenceTest {
    /** A clean, fully attributable window; each test varies one field via copy(). */
    private val cleanEvidence = NoHttpWrapperEvidence(
        unit = "meridian-iroh-wrapper",
        pid = 4242,
        serviceStartTimestamp = "Fri 2026-07-31 09:12:44 UTC",
        windowStartMs = 1_000,
        windowEndMs = 6_000,
        sampleIntervalMs = 100,
        sampleCount = 5,
        maxConnections = 0,
        pidChanged = false,
        scanUnsupported = false,
    )

    private val baseTurn = IrohProbeTurnMetrics(turn = 1, scenario = "no-http")

    @Test
    fun `clean wrapper window records pid and sample interval without violations`() {
        val turn = NoHttpProbeScenario.annotateNoHttp(baseTurn, listOf(0, 0, 0), cleanEvidence)

        assertEquals(emptyList<String>(), turn.scenarioViolations)
        assertTrue("no_http_wrapper_pid=4242" in turn.notes, "${turn.notes}")
        assertTrue("no_http_wrapper_unit=meridian-iroh-wrapper" in turn.notes, "${turn.notes}")
        assertTrue("no_http_wrapper_window_ms=5000" in turn.notes, "${turn.notes}")
        assertTrue("no_http_wrapper_sample_interval_ms=100" in turn.notes, "${turn.notes}")
    }

    @Test
    fun `wrapper dialing the admin port fails the turn`() {
        val turn = NoHttpProbeScenario.annotateNoHttp(baseTurn, listOf(0, 2, 1), cleanEvidence.copy(maxConnections = 2))

        assertTrue("no_http_tcp_connects:2" in turn.scenarioViolations, "${turn.scenarioViolations}")
    }

    @Test
    fun `restart mid window invalidates rather than greens the turn`() {
        val turn = NoHttpProbeScenario.annotateNoHttp(baseTurn, listOf(0, 0), cleanEvidence.copy(pidChanged = true))

        assertTrue(
            "no_http_wrapper_pid_changed:meridian-iroh-wrapper" in turn.scenarioViolations,
            "${turn.scenarioViolations}",
        )
    }

    @Test
    fun `unresolved wrapper pid invalidates rather than greens the turn`() {
        val unresolved = cleanEvidence.copy(pid = null, sampleCount = 0)
        val turn = NoHttpProbeScenario.annotateNoHttp(baseTurn, emptyList(), unresolved)

        assertTrue(
            "no_http_wrapper_pid_unresolved:meridian-iroh-wrapper" in turn.scenarioViolations,
            "${turn.scenarioViolations}",
        )
    }

    /**
     * A wrapper we identified but cannot read (`/proc/<pid>/fd` is mode 500 for
     * another user, or no procfs at all) is UNVERIFIABLE — it must fail, not pass
     * with a skip note, or the gate silently counts zero sockets.
     */
    @Test
    fun `unreadable wrapper sockets fail instead of skipping`() {
        val turn = NoHttpProbeScenario.annotateNoHttp(
            baseTurn,
            emptyList(),
            cleanEvidence.copy(sampleCount = 0, scanUnsupported = true),
        )

        assertTrue("no_http_scan_unsupported_platform" in turn.notes, "${turn.notes}")
        assertTrue(
            "no_http_wrapper_scan_unavailable:meridian-iroh-wrapper" in turn.scenarioViolations,
            "${turn.scenarioViolations}",
        )
    }
}
