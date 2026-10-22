package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry

private const val SLOW_HANDLER_MS = 250L
private const val NANOS_PER_MILLI = 1_000_000L

/** Names a handler that took 250 ms or more, with its response size: the only per-call timing the host keeps. */
internal fun reportIfSlow(invocation: AdminRpcInvocation, startedNs: Long, responseChars: Int) {
    val tookMs = (System.nanoTime() - startedNs) / NANOS_PER_MILLI
    if (tookMs < SLOW_HANDLER_MS) return
    Telemetry.event(
        "AdminRpc", "handler.slow",
        "method" to invocation.method, "responseChars" to responseChars,
        durationMs = tookMs, level = Telemetry.Level.WARN,
    )
}
