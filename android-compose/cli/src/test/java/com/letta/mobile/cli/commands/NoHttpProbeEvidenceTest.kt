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
    private fun evidence(
        pid: Int? = 4242,
        pidChanged: Boolean = false,
        sampleCount: Int = 5,
        maxConnections: Int = 0,
        scanUnsupported: Boolean = false,
    ) = NoHttpWrapperEvidence(
        unit = "meridian-iroh-wrapper",
        pid = pid,
        serviceStartTimestamp = "Fri 2026-07-31 09:12:44 UTC",
        windowStartMs = 1_000,
        windowEndMs = 6_000,
        sampleIntervalMs = 100,
        sampleCount = sampleCount,
        maxConnections = maxConnections,
        pidChanged = pidChanged,
        scanUnsupported = scanUnsupported,
    )

    private val baseTurn = IrohProbeTurnMetrics(turn = 1, scenario = "no-http")

    @Test
    fun `clean wrapper window records pid and sample interval without violations`() {
        val turn = NoHttpProbeScenario.annotateNoHttp(baseTurn, listOf(0, 0, 0), evidence())

        assertEquals(emptyList<String>(), turn.scenarioViolations)
        assertTrue("no_http_wrapper_pid=4242" in turn.notes, "${turn.notes}")
        assertTrue("no_http_wrapper_unit=meridian-iroh-wrapper" in turn.notes, "${turn.notes}")
        assertTrue("no_http_wrapper_window_ms=5000" in turn.notes, "${turn.notes}")
        assertTrue("no_http_wrapper_sample_interval_ms=100" in turn.notes, "${turn.notes}")
    }

    @Test
    fun `wrapper dialing the admin port fails the turn`() {
        val turn = NoHttpProbeScenario.annotateNoHttp(baseTurn, listOf(0, 2, 1), evidence(maxConnections = 2))

        assertTrue("no_http_tcp_connects:2" in turn.scenarioViolations, "${turn.scenarioViolations}")
    }

    @Test
    fun `restart mid window invalidates rather than greens the turn`() {
        val turn = NoHttpProbeScenario.annotateNoHttp(baseTurn, listOf(0, 0), evidence(pidChanged = true))

        assertTrue(
            "no_http_wrapper_pid_changed:meridian-iroh-wrapper" in turn.scenarioViolations,
            "${turn.scenarioViolations}",
        )
    }

    @Test
    fun `unresolved wrapper pid invalidates rather than greens the turn`() {
        val turn = NoHttpProbeScenario.annotateNoHttp(baseTurn, emptyList(), evidence(pid = null, sampleCount = 0))

        assertTrue(
            "no_http_wrapper_pid_unresolved:meridian-iroh-wrapper" in turn.scenarioViolations,
            "${turn.scenarioViolations}",
        )
    }

    @Test
    fun `platforms without procfs degrade to a skip note`() {
        val turn = NoHttpProbeScenario.annotateNoHttp(
            baseTurn,
            emptyList(),
            evidence(sampleCount = 0, scanUnsupported = true),
        )

        assertTrue("no_http_scan_unsupported_platform" in turn.notes, "${turn.notes}")
        assertEquals(emptyList<String>(), turn.scenarioViolations)
    }
}
