package com.letta.mobile.cli.probe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WrapperProcessScanTest {
    @Test
    fun `parses systemctl show output`() {
        val info = WrapperProcessScan.parseShow(
            "meridian-iroh-wrapper",
            """
            MainPID=8421
            ExecMainStartTimestamp=Fri 2026-07-31 09:12:44 UTC
            ActiveEnterTimestamp=Fri 2026-07-31 09:12:45 UTC
            """.trimIndent(),
        )
        assertEquals(8421, info?.pid)
        assertEquals("Fri 2026-07-31 09:12:44 UTC", info?.startTimestamp)
        assertEquals("meridian-iroh-wrapper", info?.unit)
    }

    @Test
    fun `falls back to ActiveEnterTimestamp when ExecMainStartTimestamp is empty`() {
        val info = WrapperProcessScan.parseShow(
            "u",
            "MainPID=12\nExecMainStartTimestamp=\nActiveEnterTimestamp=Fri 2026-07-31 09:12:45 UTC",
        )
        assertEquals("Fri 2026-07-31 09:12:45 UTC", info?.startTimestamp)
    }

    @Test
    fun `inactive unit resolves to no pid`() {
        assertNull(WrapperProcessScan.parseShow("u", "MainPID=0\nExecMainStartTimestamp=n/a"))
        assertNull(WrapperProcessScan.parseShow("u", "LoadState=not-found"))
        assertNull(WrapperProcessScan.parseShow("u", ""))
    }

    @Test
    fun `resolve returns null when systemd is unavailable`() {
        assertNull(WrapperProcessScan.resolve("nope.service") { null })
    }

    @Test
    fun `default unit is the production wrapper`() {
        assertEquals("meridian-iroh-wrapper", WrapperProcessScan.DEFAULT_UNIT)
    }

    /**
     * jr5tx — the deployment gate must never be handed a "no wrapper here" escape
     * hatch; that is exactly the false-green lgns8.21.9 removed.
     */
    @Test
    fun `deployment mode rejects a not-applicable declaration`() {
        val error = WrapperProcessScan.validateScanOptions(
            WrapperScanMode.DEPLOYMENT,
            pid = null,
            notApplicableReason = "no systemd on this host",
        )
        assertTrue(error != null && error.contains("hermetic"), "expected a usage error, got $error")
    }

    @Test
    fun `deployment mode needs no extra flags`() {
        assertNull(WrapperProcessScan.validateScanOptions(WrapperScanMode.DEPLOYMENT, pid = null, notApplicableReason = null))
        assertNull(WrapperProcessScan.validateScanOptions(WrapperScanMode.DEPLOYMENT, pid = 42, notApplicableReason = null))
    }

    /** Hermetic runs cannot resolve a unit, so they must name what they scan. */
    @Test
    fun `hermetic mode requires a pid or an explicit not-applicable reason`() {
        assertTrue(
            WrapperProcessScan.validateScanOptions(WrapperScanMode.HERMETIC, pid = null, notApplicableReason = null) != null,
        )
        assertNull(WrapperProcessScan.validateScanOptions(WrapperScanMode.HERMETIC, pid = 777, notApplicableReason = null))
        assertNull(WrapperProcessScan.validateScanOptions(WrapperScanMode.HERMETIC, pid = null, notApplicableReason = "no wrapper"))
        assertTrue(
            WrapperProcessScan.validateScanOptions(WrapperScanMode.HERMETIC, pid = 777, notApplicableReason = "no wrapper") != null,
            "a pid and a not-applicable reason contradict each other",
        )
    }

    @Test
    fun `scan mode is parsed from the cli and defaults nowhere`() {
        assertEquals(WrapperScanMode.DEPLOYMENT, WrapperScanMode.fromCli("deployment"))
        assertEquals(WrapperScanMode.HERMETIC, WrapperScanMode.fromCli(" HERMETIC "))
        assertNull(WrapperScanMode.fromCli("auto"))
        assertEquals(listOf("deployment", "hermetic"), WrapperScanMode.CLI_VALUES)
    }

    @Test
    fun `liveness follows the proc directory`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.io.File("/proc/self").isDirectory, "procfs required")
        val ownPid = java.io.File("/proc/self").canonicalFile.name.toInt()
        assertTrue(WrapperProcessScan.isAlive(ownPid))
        assertTrue(!WrapperProcessScan.isAlive(4_194_303, procRoot = "/definitely/not/proc"))
    }
}
