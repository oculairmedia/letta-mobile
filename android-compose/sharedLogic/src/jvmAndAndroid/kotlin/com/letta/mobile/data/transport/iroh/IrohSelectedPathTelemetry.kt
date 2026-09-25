package com.letta.mobile.data.transport.iroh

import com.letta.mobile.util.Telemetry
import computer.iroh.PathSnapshot
import java.util.concurrent.atomic.AtomicReference

/**
 * letta-mobile-qygvv.22: emits `IrohTransport/path.selected` with the selected path's
 * remote socket address (`ip:port`, or the relay URL) and relay flag — once on connect
 * and again whenever the selected path changes. The 2026-09-25 black-hole was only
 * diagnosable from Tailscale's own logs because nothing said WHICH address the QUIC
 * path was riding; this makes the path visible in app telemetry.
 */
internal class IrohSelectedPathTelemetry(
    private val emit: (SelectedPathAttributes) -> Unit = ::emitSelectedPath,
) {
    private val lastSelected = AtomicReference<String?>(null)

    /** Always reports: the first path of a connection is itself the change. */
    fun onConnect(paths: List<PathSnapshot>) = onConnect(SelectedPathAttributes.of(paths, trigger = "connect"))

    fun onConnect(selected: SelectedPathAttributes) {
        lastSelected.set(selected.selectionKey)
        emit(selected)
    }

    /** Reports only when the selected path (id or address) differs from the last one. */
    fun onPathsChanged(paths: List<PathSnapshot>) = onPathsChanged(SelectedPathAttributes.of(paths, trigger = "path_change"))

    fun onPathsChanged(selected: SelectedPathAttributes) {
        if (lastSelected.getAndSet(selected.selectionKey) == selected.selectionKey) return
        emit(selected)
    }
}

/** Typed payload of one `path.selected` event. */
internal data class SelectedPathAttributes(
    val trigger: String,
    val pathId: String,
    val kind: String,
    val remoteAddr: String,
    val isRelay: Boolean,
    val rttMs: Long?,
    val pathCount: Int,
    val relayPathCount: Int,
) {
    /** Identity of the selected path: a change of id OR address is a path change. */
    val selectionKey: String get() = "$pathId|$remoteAddr"

    companion object {
        /** Same selection rule as [IrohDiagnostics.summarizePaths]: selected, else first. */
        fun of(paths: List<PathSnapshot>, trigger: String): SelectedPathAttributes {
            val selected = paths.firstOrNull { it.isSelected } ?: paths.firstOrNull()
            return SelectedPathAttributes(
                trigger = trigger,
                pathId = selected?.id.orEmpty(),
                kind = selected?.let { IrohDiagnostics.pathKind(isRelay = it.isRelay, isIp = it.isIp) } ?: "unknown",
                remoteAddr = selected?.remoteAddr.orEmpty(),
                isRelay = selected?.isRelay ?: false,
                rttMs = selected?.rttMs?.toLong(),
                pathCount = paths.size,
                relayPathCount = paths.count { it.isRelay },
            )
        }
    }
}

private fun emitSelectedPath(attributes: SelectedPathAttributes) = with(attributes) {
    Telemetry.event(
        "IrohTransport", "path.selected",
        "trigger" to trigger,
        "pathId" to pathId,
        "pathKind" to kind,
        "remoteAddr" to remoteAddr,
        "isRelay" to isRelay,
        "rttMs" to rttMs,
        "pathCount" to pathCount,
        "relayPathCount" to relayPathCount,
    )
}
