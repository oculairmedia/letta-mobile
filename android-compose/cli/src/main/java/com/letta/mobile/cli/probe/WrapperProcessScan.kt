package com.letta.mobile.cli.probe

import java.io.File

/**
 * Wrapper-process attribution for the `no-http` shim-off release gate
 * (letta-mobile-lgns8.21.9).
 *
 * The runbook claims the `no-http` probe proves the WRAPPER opened zero admin
 * HTTP connections. Scanning `/proc/self` only ever proved that the PROBE
 * process stayed clean, so a wrapper still dialing LettaShim passed the gate.
 * This resolves the wrapper service's MainPID (systemd) so [NoHttpSocketScan]
 * can be pointed at `/proc/<pid>` instead, and records the PID + service start
 * timestamp + sample window as evidence so a mid-window restart invalidates the
 * result instead of rendering green.
 */
data class WrapperProcessInfo(
    val unit: String,
    val pid: Int,
    /** systemd `ExecMainStartTimestamp` — changes whenever the service restarts. */
    val startTimestamp: String?,
)

object WrapperProcessScan {
    /** Production wrapper unit; overridable so other deployments can gate too. */
    const val DEFAULT_UNIT: String = "meridian-iroh-wrapper"

    /**
     * Resolves [unit]'s MainPID and start timestamp. [show] is injectable so
     * tests never shell out; it receives the unit name and returns the raw
     * `systemctl show` key=value block (or null when systemd is unavailable).
     */
    fun resolve(unit: String, show: (String) -> String? = ::systemctlShow): WrapperProcessInfo? {
        val output = show(unit) ?: return null
        return parseShow(unit, output)
    }

    internal fun parseShow(unit: String, output: String): WrapperProcessInfo? {
        val fields = output.lineSequence()
            .mapNotNull { line ->
                val key = line.substringBefore('=', "").trim()
                if (key.isEmpty() || !line.contains('=')) null else key to line.substringAfter('=').trim()
            }
            .toMap()
        val pid = fields["MainPID"]?.toIntOrNull() ?: return null
        // systemd reports MainPID=0 for inactive/oneshot units — not attributable.
        if (pid <= 0) return null
        val timestamp = listOf("ExecMainStartTimestamp", "ActiveEnterTimestamp", "ExecMainStartTimestampMonotonic")
            .firstNotNullOfOrNull { fields[it]?.takeIf { value -> value.isNotBlank() && value != "0" } }
        return WrapperProcessInfo(unit = unit, pid = pid, startTimestamp = timestamp)
    }

    /** True while `/proc/<pid>` still exists — a dead PID means the window is invalid. */
    fun isAlive(pid: Int, procRoot: String = "/proc"): Boolean = File(procRoot, pid.toString()).isDirectory

    private fun systemctlShow(unit: String): String? = runCatching {
        val process = ProcessBuilder(
            "systemctl", "show", unit,
            "-p", "MainPID", "-p", "ExecMainStartTimestamp", "-p", "ActiveEnterTimestamp",
        ).redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        if (process.exitValue() != 0) null else output
    }.getOrNull()
}

/**
 * Attributable evidence for one `no-http` sample window. Emitted into the probe
 * turn notes so CI/deployment output records WHICH process was watched, for how
 * long, and whether the attribution held for the whole window.
 */
data class NoHttpWrapperEvidence(
    val unit: String,
    val pid: Int?,
    val serviceStartTimestamp: String?,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val sampleIntervalMs: Long,
    val sampleCount: Int,
    val maxConnections: Int,
    /** The MainPID or service start timestamp moved mid-window (restart) — evidence invalid. */
    val pidChanged: Boolean,
    /** No procfs (macOS/Windows) — scan degraded to a skip note, not a false green. */
    val scanUnsupported: Boolean,
) {
    val windowMs: Long get() = (windowEndMs - windowStartMs).coerceAtLeast(0)

    /**
     * Gate violations. Unattributable evidence (no PID, PID moved, zero samples)
     * fails the gate rather than passing it — the whole point of the bead.
     */
    fun violations(): List<String> = buildList {
        if (pidChanged) add("no_http_wrapper_pid_changed:$unit")
        when {
            // No attributable process at all.
            pid == null -> add("no_http_wrapper_pid_unresolved:$unit")
            // We identified the wrapper but could not read its sockets (procfs absent
            // or `/proc/<pid>/fd` unreadable) — unverifiable, therefore not green.
            scanUnsupported -> add("no_http_wrapper_scan_unavailable:$unit")
            sampleCount == 0 -> add("no_http_wrapper_no_samples:$unit")
        }
    }

    fun notes(): List<String> = listOf(
        "no_http_wrapper_unit=$unit",
        "no_http_wrapper_pid=${pid ?: -1}",
        "no_http_wrapper_start=${serviceStartTimestamp ?: "unknown"}",
        "no_http_wrapper_window_ms=$windowMs",
        "no_http_wrapper_sample_interval_ms=$sampleIntervalMs",
        "no_http_socket_samples=$sampleCount max=$maxConnections",
    )
}
