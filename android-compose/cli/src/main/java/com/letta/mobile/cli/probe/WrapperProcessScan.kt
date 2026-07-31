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

/**
 * Which shim-off run is asking (letta-mobile-jr5tx).
 *
 * The wrapper-process scan means different things in the two runs, so the caller
 * DECLARES which one it is — never inferred from "is systemd present?", because
 * a missing unit on the production host must stay a hard failure.
 *
 * - [DEPLOYMENT] — the real acceptance run against the production wrapper. The
 *   scan is REQUIRED: an unresolvable unit, a restart mid-window, or an
 *   unreadable `/proc` all fail the gate (letta-mobile-lgns8.21.9). Declaring
 *   the scan "not applicable" is itself a violation here.
 * - [HERMETIC] — CI, where the probe harness spawns and owns the whole stack and
 *   no external wrapper unit exists. The scan still runs, but against the
 *   harness-spawned wrapper PID handed in via `--wrapper-pid`, so the gate keeps
 *   proving that the process serving Iroh opened no admin-HTTP connections. Only
 *   when there is genuinely no wrapper process at all may the harness declare
 *   `--wrapper-scan-not-applicable <reason>`, which records a distinct evidence
 *   state instead of a green scan.
 */
enum class WrapperScanMode(val cliValue: String) {
    DEPLOYMENT("deployment"),
    HERMETIC("hermetic"),
    ;

    companion object {
        val CLI_VALUES: List<String> = entries.map { it.cliValue }

        fun fromCli(value: String): WrapperScanMode? =
            entries.firstOrNull { it.cliValue.equals(value.trim(), ignoreCase = true) }
    }
}

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

    /**
     * Validates the `no-http` wrapper-scan option triple, returning an error
     * message when the combination is incoherent (letta-mobile-jr5tx).
     *
     * Kept pure so the CLI contract is unit-testable without booting Clikt.
     */
    fun validateScanOptions(mode: WrapperScanMode, pid: Int?, notApplicableReason: String?): String? {
        val reason = notApplicableReason?.takeIf { it.isNotBlank() }
        return when {
            mode == WrapperScanMode.DEPLOYMENT && reason != null ->
                "--wrapper-scan-not-applicable is only valid with --wrapper-scan-mode=hermetic; " +
                    "the deployment gate must scan the real wrapper process"
            mode == WrapperScanMode.HERMETIC && pid != null && reason != null ->
                "--wrapper-pid and --wrapper-scan-not-applicable are mutually exclusive"
            mode == WrapperScanMode.HERMETIC && pid == null && reason == null ->
                "--wrapper-scan-mode=hermetic requires --wrapper-pid <spawned wrapper pid> " +
                    "(or --wrapper-scan-not-applicable <reason> when the harness spawns no wrapper process)"
            else -> null
        }
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
    /** Which run declared itself (letta-mobile-jr5tx). Deployment stays strict. */
    val mode: WrapperScanMode = WrapperScanMode.DEPLOYMENT,
    /**
     * Set only when the caller declared there is no wrapper process to scan.
     * Accepted in [WrapperScanMode.HERMETIC]; rejected in
     * [WrapperScanMode.DEPLOYMENT], where "no wrapper" is the failure itself.
     */
    val notApplicableReason: String? = null,
) {
    val windowMs: Long get() = (windowEndMs - windowStartMs).coerceAtLeast(0)

    /**
     * Gate violations. Unattributable evidence (no PID, PID moved, zero samples)
     * fails the gate rather than passing it — the whole point of the bead.
     */
    fun violations(): List<String> = buildList {
        // "There is no wrapper process here" is an acceptable HERMETIC state and a
        // hard failure everywhere else — never a silent green (letta-mobile-jr5tx).
        if (!notApplicableReason.isNullOrBlank()) {
            if (mode != WrapperScanMode.HERMETIC) {
                add("no_http_wrapper_scan_not_applicable_rejected:$unit")
            }
            return@buildList
        }
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

    fun notes(): List<String> = buildList {
        add("no_http_wrapper_scan_mode=${mode.cliValue}")
        if (!notApplicableReason.isNullOrBlank()) {
            add("no_http_wrapper_unit=$unit")
            add("no_http_wrapper_scan_not_applicable=$notApplicableReason")
            return@buildList
        }
        addAll(scannedNotes())
    }

    private fun scannedNotes(): List<String> = listOf(
        "no_http_wrapper_unit=$unit",
        "no_http_wrapper_pid=${pid ?: -1}",
        "no_http_wrapper_start=${serviceStartTimestamp ?: "unknown"}",
        "no_http_wrapper_window_ms=$windowMs",
        "no_http_wrapper_sample_interval_ms=$sampleIntervalMs",
        "no_http_socket_samples=$sampleCount max=$maxConnections",
    )
}
