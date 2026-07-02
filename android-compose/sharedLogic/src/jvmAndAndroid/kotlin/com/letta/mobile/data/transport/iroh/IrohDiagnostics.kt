package com.letta.mobile.data.transport.iroh

import computer.iroh.Connection
import computer.iroh.ConnectionStats
import computer.iroh.EndpointId
import computer.iroh.PathEvent
import computer.iroh.PathSnapshot

object IrohDiagnostics {
    data class PathDiagnostics(
        val id: String,
        val selected: Boolean,
        val kind: String,
        val isRelay: Boolean,
        val isIp: Boolean,
        val rttMs: Long,
        val lostPackets: Long,
        val lostBytes: Long,
        val udpTxDatagrams: Long,
        val udpRxDatagrams: Long,
    )

    data class PathSummary(
        val selectedKind: String,
        val selectedPathId: String,
        val pathCount: Int,
        val relayPathCount: Int,
        val directPathCount: Int,
        val selectedRttMs: Long?,
        val lostPackets: Long,
        val lostBytes: Long,
    )

    fun endpointIdHex(id: EndpointId?): String = id?.toBytes()?.toHex().orEmpty()

    fun endpointIdHex(bytes: ByteArray?): String = bytes?.toHex().orEmpty()

    fun connectionAttributes(connection: Connection): List<Pair<String, Any?>> {
        val paths = runCatching { connection.paths() }.getOrDefault(emptyList())
        val summary = summarizePaths(paths)
        val stats = runCatching { connection.stats() }.getOrNull()
        return listOf(
            "remoteEndpointId" to runCatching { endpointIdHex(connection.remoteId()) }.getOrDefault(""),
            "rttMs" to runCatching { connection.rtt()?.toLong() }.getOrNull(),
            "selectedPathKind" to summary.selectedKind,
            "selectedPathId" to summary.selectedPathId,
            "pathCount" to summary.pathCount,
            "relayPathCount" to summary.relayPathCount,
            "directPathCount" to summary.directPathCount,
            "lostPackets" to (stats?.lostPackets ?: summary.lostPackets),
            "lostBytes" to (stats?.lostBytes ?: summary.lostBytes),
        )
    }

    fun closeAttributes(connection: Connection): List<Pair<String, Any?>> = listOf(
        "remoteEndpointId" to runCatching { endpointIdHex(connection.remoteId()) }.getOrDefault(""),
        "closeReason" to closeReason(connection),
    )

    fun closeReason(connection: Connection): String =
        sanitizeReason(runCatching { connection.closeReason() }.getOrNull())

    fun closeReason(reason: String?): String = sanitizeReason(reason)

    fun summarizePaths(paths: List<PathSnapshot>): PathSummary {
        val normalized = paths.map(::pathSnapshot)
        val selected = normalized.firstOrNull { it.selected } ?: normalized.firstOrNull()
        return PathSummary(
            selectedKind = selected?.kind ?: "unknown",
            selectedPathId = selected?.id.orEmpty(),
            pathCount = normalized.size,
            relayPathCount = normalized.count { it.isRelay },
            directPathCount = normalized.count { it.kind == "direct" },
            selectedRttMs = selected?.rttMs,
            lostPackets = normalized.sumOf { it.lostPackets },
            lostBytes = normalized.sumOf { it.lostBytes },
        )
    }

    fun pathSnapshot(path: PathSnapshot): PathDiagnostics {
        val stats = path.stats
        return PathDiagnostics(
            id = path.id,
            selected = path.isSelected,
            kind = pathKind(isRelay = path.isRelay, isIp = path.isIp),
            isRelay = path.isRelay,
            isIp = path.isIp,
            rttMs = path.rttMs.toLong(),
            lostPackets = stats.lostPackets.toLong(),
            lostBytes = stats.lostBytes.toLong(),
            udpTxDatagrams = stats.udpTxDatagrams.toLong(),
            udpRxDatagrams = stats.udpRxDatagrams.toLong(),
        )
    }

    fun pathEventAttributes(event: PathEvent): List<Pair<String, Any?>> = when (event) {
        is PathEvent.Opened -> listOf(
            "event" to "opened",
            "pathId" to event.id,
            "pathKind" to pathKind(event.remoteAddr),
        )
        is PathEvent.Selected -> listOf(
            "event" to "selected",
            "pathId" to event.id,
            "pathKind" to pathKind(event.remoteAddr),
        )
        is PathEvent.Closed -> listOf(
            "event" to "closed",
            "pathId" to event.id,
            "pathKind" to pathKind(event.remoteAddr),
            "lostPackets" to event.lastStats.lostPackets.toLong(),
            "lostBytes" to event.lastStats.lostBytes.toLong(),
        )
        is PathEvent.Lagged -> listOf(
            "event" to "lagged",
            "missed" to event.missed.toLong(),
        )
    }

    fun pathKind(isRelay: Boolean, isIp: Boolean): String = when {
        isRelay -> "relay"
        isIp -> "direct"
        else -> "unknown"
    }

    fun pathKind(remoteAddr: String?): String {
        val value = remoteAddr.orEmpty().lowercase()
        return when {
            value.contains("relay") -> "relay"
            value.contains("derp") -> "relay"
            value.contains(":") || value.contains(".") -> "direct"
            else -> "unknown"
        }
    }

    fun redactedFrameAttributes(frameType: String?, bytes: Int): List<Pair<String, Any?>> = listOf(
        "type" to frameType,
        "bytes" to bytes,
    )

    fun connectionStatsAttributes(stats: ConnectionStats?): List<Pair<String, Any?>> = listOf(
        "lostPackets" to (stats?.lostPackets ?: 0L),
        "lostBytes" to (stats?.lostBytes ?: 0L),
        "udpTxDatagrams" to (stats?.udpTxDatagrams ?: 0L),
        "udpRxDatagrams" to (stats?.udpRxDatagrams ?: 0L),
    )

    private fun sanitizeReason(reason: String?): String {
        val value = reason?.trim().orEmpty()
        if (value.isBlank()) return ""
        val lowered = value.lowercase()
        return if (
            lowered.contains("token") ||
            lowered.contains("secret") ||
            lowered.contains("key") ||
            lowered.contains("bearer") ||
            lowered.contains("/home/") ||
            lowered.contains("\\")
        ) {
            "<redacted>"
        } else {
            value.take(MAX_REASON_CHARS)
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private const val MAX_REASON_CHARS = 120
}
