package com.letta.mobile.cli.probe

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Samples the WRAPPER process's admin-HTTP connections for one `no-http` window
 * and turns the result into attributable [NoHttpWrapperEvidence]
 * (letta-mobile-lgns8.21.9).
 *
 * Everything the scenario needs is injectable so the gate can be unit-tested
 * against a fake `/proc` root and a fake systemd resolver:
 *  - [resolve] maps a unit name to its MainPID + start timestamp;
 *  - [procRoot] is the procfs root joined with that PID;
 *  - [nowMs] stamps the sample window.
 *
 * The window is invalid — not green — when the PID cannot be resolved, when the
 * process disappears mid-window, or when the MainPID/start timestamp moves
 * (service restart).
 */
internal class NoHttpWrapperWatch(
    private val unit: String,
    private val port: Int,
    private val explicitPid: Int? = null,
    private val procRoot: String = "/proc",
    private val sampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS,
    private val resolve: (String) -> WrapperProcessInfo? = { WrapperProcessScan.resolve(it) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val mode: WrapperScanMode = WrapperScanMode.DEPLOYMENT,
    /** Hermetic-only: the harness declared it spawns no wrapper process at all. */
    private val notApplicableReason: String? = null,
) {
    private val samples: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    private val scanUnsupported = AtomicBoolean(false)
    private val pidChanged = AtomicBoolean(false)

    @Volatile
    private var info: WrapperProcessInfo? = null

    @Volatile
    private var windowStartMs: Long = 0

    @Volatile
    private var windowEndMs: Long = 0

    private val notApplicable: String? = notApplicableReason?.takeIf { it.isNotBlank() }

    /** Resolves the wrapper PID and opens the sample window. */
    fun start() {
        windowStartMs = nowMs()
        windowEndMs = windowStartMs
        if (notApplicable != null) return
        info = explicitPid
            ?.let { WrapperProcessInfo(unit = unit, pid = it, startTimestamp = null) }
            // Hermetic runs never consult systemd: the harness owns the wrapper and
            // must hand its PID in, so a missing PID stays unresolved (and fatal)
            // rather than silently falling back to a unit that cannot exist there.
            ?: if (mode == WrapperScanMode.HERMETIC) null else resolve(unit)
    }

    /** One sample tick: liveness check plus a wrapper-scoped socket count. */
    fun sample() {
        if (notApplicable != null) return
        val pid = info?.pid ?: return
        windowEndMs = nowMs()
        if (!WrapperProcessScan.isAlive(pid, procRoot)) {
            pidChanged.set(true)
            return
        }
        when (val count = NoHttpSocketScan.connectionsToPort(port, pid.toString(), procRoot)) {
            null -> scanUnsupported.set(true)
            else -> samples += count
        }
    }

    /** Closes the window, re-checking that the PID we watched is still the wrapper. */
    fun finish(): NoHttpWrapperEvidence {
        windowEndMs = nowMs()
        val started = info
        if (started != null && !stillTheSameWrapper(started)) pidChanged.set(true)
        val snapshot = samples.toList()
        return NoHttpWrapperEvidence(
            unit = unit,
            pid = started?.pid,
            serviceStartTimestamp = started?.startTimestamp,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            sampleIntervalMs = sampleIntervalMs,
            sampleCount = snapshot.size,
            maxConnections = snapshot.maxOrNull() ?: 0,
            pidChanged = pidChanged.get(),
            scanUnsupported = scanUnsupported.get(),
            mode = mode,
            notApplicableReason = notApplicable,
        )
    }

    /** The watched process must still be alive AND still be the unit's MainPID. */
    private fun stillTheSameWrapper(started: WrapperProcessInfo): Boolean {
        if (!WrapperProcessScan.isAlive(started.pid, procRoot)) return false
        if (explicitPid != null) return true
        val current = resolve(unit) ?: return false
        return current.pid == started.pid && current.startTimestamp == started.startTimestamp
    }

    fun samples(): List<Int> = samples.toList()

    companion object {
        const val DEFAULT_SAMPLE_INTERVAL_MS: Long = 100
    }
}
