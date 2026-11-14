package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.timing.AdminRpcPhase
import com.letta.mobile.data.controller.node.iroh.timing.AdminRpcPhaseTimings
import com.letta.mobile.util.attr
import com.letta.mobile.util.Telemetry

private const val SLOW_HANDLER_MS = 250L
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Names a handler that took 250 ms or more, with its response size: the only per-call timing the host
 * keeps. When the handler timed phases ([AdminRpcPhaseTimings]), the milliseconds of each phase follow
 * (0 for one that did not run), and `wrapperMs` is the rest of the call: this host's own work,
 * response encoding included.
 */
internal fun reportIfSlow(
    invocation: AdminRpcInvocation,
    startedNs: Long,
    responseChars: Int,
    phases: AdminRpcPhaseTimings,
) {
    val tookMs = (System.nanoTime() - startedNs) / NANOS_PER_MILLI
    if (tookMs < SLOW_HANDLER_MS) return
    val phaseMs = phases.millis()
    if (phaseMs.isEmpty()) {
        Telemetry.event(
            "AdminRpc", "handler.slow",
            "method" attr invocation.method, "responseChars" attr responseChars,
            durationMs = tookMs, level = Telemetry.Level.WARN,
        )
        return
    }
    fun ms(phase: AdminRpcPhase): Long = phaseMs[phase] ?: 0L
    Telemetry.event(
        "AdminRpc", "handler.slow",
        "method" attr invocation.method, "responseChars" attr responseChars,
        AdminRpcPhase.AppServer.attr attr ms(AdminRpcPhase.AppServer),
        AdminRpcPhase.Discovery.attr attr ms(AdminRpcPhase.Discovery),
        AdminRpcPhase.LocalStore.attr attr ms(AdminRpcPhase.LocalStore),
        "wrapperMs" attr (tookMs - phaseMs.values.sum()).coerceAtLeast(0L),
        durationMs = tookMs, level = Telemetry.Level.WARN,
    )
}
