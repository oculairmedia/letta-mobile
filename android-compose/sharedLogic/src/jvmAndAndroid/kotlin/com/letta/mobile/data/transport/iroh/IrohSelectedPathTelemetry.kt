package com.letta.mobile.data.transport.iroh

import com.letta.mobile.util.Telemetry
import java.util.concurrent.atomic.AtomicReference

/**
 * letta-mobile-qygvv.22: emits `IrohTransport/path.selected` with the selected path's
 * remote socket address (`ip:port`, or the relay URL) and relay flag — once on connect
 * and again whenever the selected path changes. The 2026-09-25 black-hole was only
 * diagnosable from Tailscale's own logs because nothing said WHICH address the QUIC
 * path was riding; this makes the path visible in app telemetry.
 */
internal class IrohSelectedPathTelemetry(
    private val emit: (attributes: List<Pair<String, Any?>>) -> Unit = { attributes ->
        Telemetry.event("IrohTransport", "path.selected", *attributes.toTypedArray())
    },
) {
    private val lastSelected = AtomicReference<String?>(null)

    /** Always reports: the first path of a connection is itself the change. */
    fun onConnect(summary: IrohDiagnostics.PathSummary) {
        lastSelected.set(summary.selectionKey())
        emit(attributes(summary, trigger = "connect"))
    }

    /** Reports only when the selected path (id or address) differs from the last one. */
    fun onPathsChanged(summary: IrohDiagnostics.PathSummary) {
        val key = summary.selectionKey()
        if (lastSelected.getAndSet(key) == key) return
        emit(attributes(summary, trigger = "path_change"))
    }

    private fun IrohDiagnostics.PathSummary.selectionKey(): String = "$selectedPathId|$selectedRemoteAddr"

    private fun attributes(summary: IrohDiagnostics.PathSummary, trigger: String): List<Pair<String, Any?>> = listOf(
        "trigger" to trigger,
        "pathId" to summary.selectedPathId,
        "pathKind" to summary.selectedKind,
        "remoteAddr" to summary.selectedRemoteAddr,
        "isRelay" to summary.selectedIsRelay,
        "rttMs" to summary.selectedRttMs,
        "pathCount" to summary.pathCount,
        "relayPathCount" to summary.relayPathCount,
    )
}
