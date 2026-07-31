package com.letta.mobile.cli.probe

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * lgns8.21.9 — the shim-off `no-http` gate must be attributable to the WRAPPER
 * process and must invalidate itself when that process restarts mid-window.
 */
class NoHttpWrapperWatchTest {
    // 8291 = 0x2063
    private val dirtyTcp = listOf(
        "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
        "   0: 0100007F:A3F2 0100007F:2063 01 00000000:00000000 00:00000000 00000000  1000        0 111111 1 0 20 4 30 10 -1",
    )
    private val cleanTcp = listOf(
        "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
        "   0: 0100007F:A3F2 0100007F:270F 01 00000000:00000000 00:00000000 00000000  1000        0 111111 1 0 20 4 30 10 -1",
    )

    private fun info(pid: Int, start: String? = "Mon 2026-07-31 10:00:00 UTC") =
        WrapperProcessInfo(unit = "wrapper.service", pid = pid, startTimestamp = start)

    private fun watch(
        root: File,
        pid: Int = 4242,
        resolve: (String) -> WrapperProcessInfo? = { info(pid) },
    ) = NoHttpWrapperWatch(
        unit = "wrapper.service",
        port = 8291,
        procRoot = root.path,
        resolve = resolve,
        nowMs = { 1_000L },
    )

    @Test
    fun `clean wrapper produces attributable green evidence`(@TempDir root: File) {
        FakeProcRoot.write(root, pid = "4242", socketInodes = listOf(111_111L), tcpLines = cleanTcp)
        val watch = watch(root)
        watch.start()
        repeat(3) { watch.sample() }
        val evidence = watch.finish()

        assertEquals(listOf(0, 0, 0), watch.samples())
        assertEquals(emptyList<String>(), evidence.violations())
        assertEquals(4242, evidence.pid)
        assertTrue(evidence.notes().any { it == "no_http_wrapper_pid=4242" }, "${evidence.notes()}")
        assertTrue(evidence.notes().any { it.startsWith("no_http_wrapper_start=Mon") }, "${evidence.notes()}")
        assertTrue(
            evidence.notes().any { it == "no_http_wrapper_sample_interval_ms=100" },
            "sample interval must be recorded: ${evidence.notes()}",
        )
    }

    @Test
    fun `wrapper connection to the admin port is counted even though the probe is clean`(@TempDir root: File) {
        FakeProcRoot.write(root, pid = "4242", socketInodes = listOf(111_111L), tcpLines = dirtyTcp)
        FakeProcRoot.write(root, pid = "self", socketInodes = emptyList(), tcpLines = dirtyTcp)
        val watch = watch(root)
        watch.start()
        watch.sample()
        val evidence = watch.finish()

        assertEquals(listOf(1), watch.samples())
        assertEquals(1, evidence.maxConnections)
        assertEquals(emptyList<String>(), evidence.violations(), "socket counts are classified by classifyNoHttp")
    }

    @Test
    fun `unresolvable wrapper pid invalidates the window`(@TempDir root: File) {
        val watch = watch(root, resolve = { null })
        watch.start()
        watch.sample()
        val evidence = watch.finish()

        assertNull(evidence.pid)
        assertEquals(listOf("no_http_wrapper_pid_unresolved:wrapper.service"), evidence.violations())
    }

    @Test
    fun `pid change mid window invalidates the window`(@TempDir root: File) {
        FakeProcRoot.write(root, pid = "4242", socketInodes = emptyList(), tcpLines = cleanTcp)
        FakeProcRoot.write(root, pid = "5353", socketInodes = emptyList(), tcpLines = cleanTcp)
        var current = 4242
        val watch = watch(root, resolve = { info(current) })
        watch.start()
        watch.sample()
        current = 5353 // service restarted under us
        val evidence = watch.finish()

        assertTrue(evidence.pidChanged)
        assertTrue("no_http_wrapper_pid_changed:wrapper.service" in evidence.violations())
    }

    @Test
    fun `service restart with the same pid is caught by the start timestamp`(@TempDir root: File) {
        FakeProcRoot.write(root, pid = "4242", socketInodes = emptyList(), tcpLines = cleanTcp)
        var start = "Mon 2026-07-31 10:00:00 UTC"
        val watch = watch(root, resolve = { info(4242, start) })
        watch.start()
        watch.sample()
        start = "Mon 2026-07-31 10:00:09 UTC"
        val evidence = watch.finish()

        assertTrue("no_http_wrapper_pid_changed:wrapper.service" in evidence.violations())
    }

    @Test
    fun `wrapper process exiting mid window invalidates the window`(@TempDir root: File) {
        FakeProcRoot.write(root, pid = "4242", socketInodes = emptyList(), tcpLines = cleanTcp)
        val watch = watch(root)
        watch.start()
        watch.sample()
        File(root, "4242").deleteRecursively()
        watch.sample()
        val evidence = watch.finish()

        assertTrue("no_http_wrapper_pid_changed:wrapper.service" in evidence.violations())
    }

    @Test
    fun `zero samples for a live wrapper is not green`(@TempDir root: File) {
        FakeProcRoot.write(root, pid = "4242", socketInodes = emptyList(), tcpLines = cleanTcp)
        val watch = watch(root)
        watch.start()
        val evidence = watch.finish()

        assertTrue("no_http_wrapper_no_samples:wrapper.service" in evidence.violations())
    }

    @Test
    fun `explicit pid bypasses systemd resolution`(@TempDir root: File) {
        FakeProcRoot.write(root, pid = "777", socketInodes = listOf(111_111L), tcpLines = dirtyTcp)
        val watch = NoHttpWrapperWatch(
            unit = "wrapper.service",
            port = 8291,
            explicitPid = 777,
            procRoot = root.path,
            resolve = { error("systemd must not be consulted when --wrapper-pid is given") },
            nowMs = { 5L },
        )
        watch.start()
        watch.sample()
        val evidence = watch.finish()

        assertEquals(777, evidence.pid)
        assertEquals(listOf(1), watch.samples())
        assertEquals(emptyList<String>(), evidence.violations())
    }

    // ---- jr5tx: deployment vs hermetic scan modes -------------------------

    /**
     * Regression guard for the red-main bug: on a GitHub runner there is no
     * `meridian-iroh-wrapper` unit, but the DEPLOYMENT gate must still treat an
     * unresolvable unit as fatal. Making this green again would reintroduce the
     * lgns8.21.9 false-green on the production host.
     */
    @Test
    fun `deployment mode with an unresolvable unit still fails hard`(@TempDir root: File) {
        val watch = NoHttpWrapperWatch(
            unit = "meridian-iroh-wrapper",
            port = 8291,
            procRoot = root.path,
            resolve = { null }, // systemd absent / unit not found
            nowMs = { 1L },
            mode = WrapperScanMode.DEPLOYMENT,
        )
        watch.start()
        watch.sample()
        val evidence = watch.finish()

        assertNull(evidence.pid)
        assertEquals(
            listOf("no_http_wrapper_pid_unresolved:meridian-iroh-wrapper"),
            evidence.violations(),
        )
    }

    /**
     * The hermetic harness spawns the wrapper itself, so the scan targets that
     * child PID and keeps gating on its sockets — it is NOT skipped.
     */
    @Test
    fun `hermetic mode scans the harness-spawned pid and gates on it`(@TempDir root: File) {
        FakeProcRoot.write(root, pid = "909", socketInodes = listOf(111_111L), tcpLines = dirtyTcp)
        val watch = NoHttpWrapperWatch(
            unit = "iroh-probe-stub",
            port = 8291,
            explicitPid = 909,
            procRoot = root.path,
            resolve = { error("hermetic mode must never consult systemd") },
            nowMs = { 1L },
            mode = WrapperScanMode.HERMETIC,
        )
        watch.start()
        watch.sample()
        val evidence = watch.finish()

        assertEquals(909, evidence.pid)
        assertEquals(listOf(1), watch.samples())
        assertEquals(1, evidence.maxConnections, "the spawned wrapper's sockets are what the gate counts")
        assertEquals(emptyList<String>(), evidence.violations())
        assertTrue("no_http_wrapper_scan_mode=hermetic" in evidence.notes(), "${evidence.notes()}")
    }

    /** A dead hermetic child is still an invalid window, not a skip. */
    @Test
    fun `hermetic mode fails when the spawned wrapper dies mid window`(@TempDir root: File) {
        FakeProcRoot.write(root, pid = "909", socketInodes = emptyList(), tcpLines = cleanTcp)
        val watch = NoHttpWrapperWatch(
            unit = "iroh-probe-stub",
            port = 8291,
            explicitPid = 909,
            procRoot = root.path,
            nowMs = { 1L },
            mode = WrapperScanMode.HERMETIC,
        )
        watch.start()
        watch.sample()
        File(root, "909").deleteRecursively()
        val evidence = watch.finish()

        assertTrue("no_http_wrapper_pid_changed:iroh-probe-stub" in evidence.violations(), "${evidence.violations()}")
    }

    /** Hermetic without a PID must NOT silently fall back to systemd. */
    @Test
    fun `hermetic mode never resolves a systemd unit`(@TempDir root: File) {
        val watch = NoHttpWrapperWatch(
            unit = "meridian-iroh-wrapper",
            port = 8291,
            procRoot = root.path,
            resolve = { error("hermetic mode must never consult systemd") },
            nowMs = { 1L },
            mode = WrapperScanMode.HERMETIC,
        )
        watch.start()
        watch.sample()

        assertEquals(
            listOf("no_http_wrapper_pid_unresolved:meridian-iroh-wrapper"),
            watch.finish().violations(),
        )
    }

    /**
     * The genuinely-no-wrapper hermetic case records a distinct evidence state
     * that only the hermetic mode accepts; the deployment gate rejects it.
     */
    @Test
    fun `not applicable is accepted hermetically and rejected by the deployment gate`(@TempDir root: File) {
        fun evidenceFor(mode: WrapperScanMode) = NoHttpWrapperWatch(
            unit = "iroh-probe-stub",
            port = 8291,
            procRoot = root.path,
            resolve = { error("a not-applicable scan must not touch systemd") },
            nowMs = { 1L },
            mode = mode,
            notApplicableReason = "hermetic_stub_pid_not_reported",
        ).run {
            start()
            sample()
            finish()
        }

        val hermetic = evidenceFor(WrapperScanMode.HERMETIC)
        assertEquals(emptyList<String>(), hermetic.violations())
        assertNull(hermetic.pid)
        assertTrue(
            "no_http_wrapper_scan_not_applicable=hermetic_stub_pid_not_reported" in hermetic.notes(),
            "the not-applicable state must be recorded, not silent: ${hermetic.notes()}",
        )

        val deployment = evidenceFor(WrapperScanMode.DEPLOYMENT)
        assertEquals(
            listOf("no_http_wrapper_scan_not_applicable_rejected:iroh-probe-stub"),
            deployment.violations(),
        )
    }
}
