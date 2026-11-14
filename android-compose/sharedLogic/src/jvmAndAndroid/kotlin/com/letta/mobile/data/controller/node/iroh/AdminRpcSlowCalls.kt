package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.timing.AdminRpcPhaseTimings
import com.letta.mobile.util.attr
import com.letta.mobile.util.Telemetry

private const val SLOW_HANDLER_MS = 250L
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Names a handler that took 250 ms or more, with its response size: the only per-call timing the host
 * keeps. When the handler timed phases ([AdminRpcPhaseTimings]), each phase's milliseconds follow, and
 * `wrapperMs` is the rest of the call: this host's own work, response encoding included.
 */
internal fun reportIfSlow(invocation: AdminRpcInvocation, startedNs: Long, responseChars: Int, phases: AdminRpcPhaseTimings) {
    val tookMs = (System.nanoTime() - startedNs) / NANOS_PER_MILLI
    if (tookMs < SLOW_HANDLER_MS) return
    val phaseMs = phases.millis()
    val phaseAttrs = phaseMs.map { (phase, ms) -> phase.attr attr ms } +
        listOfNotNull(phaseMs.takeIf { it.isNotEmpty() }?.let { "wrapperMs" attr (tookMs - it.values.sum()).coerceAtLeast(0L) })
    Telemetry.event(
        "AdminRpc", "handler.slow",
        "method" attr invocation.method, "responseChars" attr responseChars, *phaseAttrs.toTypedArray(),
        durationMs = tookMs, level = Telemetry.Level.WARN,
    )
}
