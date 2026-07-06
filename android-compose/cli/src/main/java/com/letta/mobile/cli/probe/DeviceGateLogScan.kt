package com.letta.mobile.cli.probe

import com.letta.mobile.data.transport.iroh.IrohProbeAssertions
import kotlinx.serialization.Serializable

/**
 * On-device streaming E2E gate parser (letta-mobile-myv7c, epic phase P5).
 *
 * The JVM/host [IrohProbeAssertions] library operates on typed [ServerFrame]s
 * observed in-process. On a real device we cannot see frames directly — we only
 * have `adb logcat` and `/proc/net/tcp`. This file turns those two host-side
 * captures into the SAME invariants the P1 probe enforces:
 *
 *  - visible streaming frames arrive over Iroh (`IrohTransport frame.recv channel=Stream`);
 *  - the active chat's `uiProjection.snapshot` grows DURING the burst, not only
 *    after a `recentReconcile` / navigation (bead: "Fails if active timeline
 *    only updates after recentReconcile/navigation");
 *  - exactly one terminal per send (`AdminChatVM ws.turnComplete`);
 *  - `isStreaming` clears (true -> false exactly once, final false);
 *  - zero TCP connections from the app uid to the backend HTTP port
 *    (reuses [IrohProbeAssertions.classifyNoHttp]).
 *
 * All logcat markers are emitted through `Telemetry.event(tag, name, attrs...)`
 * which renders as logcat tag `Telemetry/<tag>` and message `<name> [ (Nms)] key=value ...`.
 */

/** One `uiProjection.snapshot` observed for the target conversation, in arrival order. */
@Serializable
data class DeviceGateProjection(
    val eventsTotal: Int,
    val messageCount: Int,
    val isStreaming: Boolean,
    /** True if a reconcile/navigation marker was seen since the send but before this snapshot. */
    val precededByReconcile: Boolean,
)

@Serializable
data class DeviceGateMetrics(
    val conversationId: String,
    /** `AdminChatVM sendMessage.route` seen — the send boundary was observed. */
    val sendRouted: Boolean = false,
    /** `IrohTransport frame.recv channel=Stream` count — visible streaming frames over Iroh. */
    val streamFrameRecvCount: Int = 0,
    /** `IrohGate gate1.emitBoth` — typed ServerFrame emissions. */
    val gateEmitCount: Int = 0,
    /** `IrohGate gate3.coordinatorMessageDelta` for the conversation. */
    val coordinatorDeltaCount: Int = 0,
    /** `IrohGate gate4.repositoryIngest` for the conversation. */
    val repositoryIngestCount: Int = 0,
    /** `AdminChatVM ws.turnComplete` — terminal per send. */
    val turnCompleteCount: Int = 0,
    /** reconcile / navigation markers seen for the conversation since the send. */
    val reconcileMarkerCount: Int = 0,
    val projections: List<DeviceGateProjection> = emptyList(),
    /** Per-sample count of app-uid TCP connections to the backend HTTP port. */
    val socketSamples: List<Int> = emptyList(),
    val httpPort: Int = 0,
)

@Serializable
data class DeviceGateSummary(
    val ok: Boolean,
    val violations: List<String>,
    val metrics: DeviceGateMetrics,
)

/**
 * Parses `/proc/net/tcp{,6}` rows to count connections owned by a given app uid
 * to a remote HTTP port. Unlike [NoHttpSocketScan] (own-process, inode-joined),
 * this scopes by the uid column of the device-wide table dumped over adb, since
 * we cannot read the app process's `/proc/self/fd` from the host.
 */
object AppUidSocketScan {
    /**
     * Count rows whose REMOTE port == [port] and whose owner uid == [uid].
     * Listen sockets (rem port 0) never match. State-agnostic: any connect,
     * including TIME_WAIT, counts — the invariant is ZERO, not zero-established.
     */
    fun countConnectionsToPort(procNetTcpLines: List<String>, uid: Int, port: Int): Int =
        procNetTcpLines.count { line ->
            val fields = line.trim().split(Regex("\\s+"))
            // sl local_address rem_address st tx:rx tr:tm retrnsmt uid timeout inode ...
            if (fields.size < 10 || !fields[0].endsWith(":")) return@count false
            val remotePort = fields[2].substringAfter(':', "").toIntOrNull(16) ?: return@count false
            val rowUid = fields[7].toIntOrNull() ?: return@count false
            remotePort == port && rowUid == uid
        }

    /**
     * Splits a file of several concatenated `/proc/net/tcp` dumps (one per sample
     * tick) into per-sample line groups, using the `local_address` header row as
     * the delimiter. A leading group before the first header is discarded.
     */
    fun splitSamples(lines: List<String>): List<List<String>> {
        val samples = mutableListOf<MutableList<String>>()
        for (line in lines) {
            if (line.contains("local_address")) {
                samples += mutableListOf<String>()
            } else if (samples.isNotEmpty() && line.isNotBlank()) {
                samples.last() += line
            }
        }
        return samples
    }
}

object DeviceGateLogScan {
    private val TELEMETRY_LINE = Regex("""Telemetry/(\w+)(?:\(\s*\d+\s*\))?\s*:\s?(.*)""")

    private data class TelemetryEvent(val tag: String, val name: String, val attrs: Map<String, String>)

    private fun parseLine(line: String): TelemetryEvent? {
        val match = TELEMETRY_LINE.find(line) ?: return null
        val tag = match.groupValues[1]
        val body = match.groupValues[2]
        val tokens = body.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val name = tokens.first()
        val attrs = tokens.drop(1)
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return TelemetryEvent(tag, name, attrs)
    }

    /**
     * Build metrics from ordered logcat [lines] scoped to [conversationId].
     * [socketSamples] and [httpPort] come from [AppUidSocketScan] (empty = skip).
     */
    fun parse(
        lines: List<String>,
        conversationId: String,
        socketSamples: List<Int> = emptyList(),
        httpPort: Int = 0,
    ): DeviceGateMetrics {
        var sendRouted = false
        var streamFrames = 0
        var gateEmits = 0
        var coordinatorDeltas = 0
        var repoIngests = 0
        var turnCompletes = 0
        var reconcileMarkers = 0
        var reconcileSinceSend = 0
        val projections = mutableListOf<DeviceGateProjection>()

        for (line in lines) {
            val event = parseLine(line) ?: continue
            val convMatches = event.attrs.values.any { it == conversationId }
            when (event.tag) {
                "IrohTransport" ->
                    if (event.name == "frame.recv" && event.attrs["channel"] == "Stream") streamFrames++
                "IrohGate" -> when (event.name) {
                    "gate1.emitBoth" -> if (convMatches) gateEmits++
                    "gate3.coordinatorMessageDelta" -> if (convMatches) coordinatorDeltas++
                    "gate4.repositoryIngest" -> if (convMatches) repoIngests++
                }
                "AdminChatVM" -> when (event.name) {
                    "sendMessage.route" -> if (convMatches) sendRouted = true
                    "ws.turnComplete" -> turnCompletes++
                }
                "IrohTrace" ->
                    if (event.name == "send.route" && convMatches) sendRouted = true
                "TimelineSync" -> {
                    val isReconcile = event.name == "reconcile" ||
                        event.name.startsWith("recentReconcile") ||
                        event.name.startsWith("externalRunReconcile")
                    if (isReconcile && convMatches) {
                        reconcileMarkers++
                        reconcileSinceSend++
                    }
                    if (event.name == "uiProjection.snapshot" && convMatches) {
                        projections += DeviceGateProjection(
                            eventsTotal = event.attrs["eventsTotal"]?.toIntOrNull() ?: 0,
                            messageCount = event.attrs["messageCount"]?.toIntOrNull() ?: 0,
                            isStreaming = event.attrs["isStreaming"] == "true",
                            precededByReconcile = reconcileSinceSend > 0,
                        )
                    }
                }
                "LettaTimelineDump" ->
                    if (event.name.contains("recentReconcile") && convMatches) {
                        reconcileMarkers++
                        reconcileSinceSend++
                    }
            }
        }

        return DeviceGateMetrics(
            conversationId = conversationId,
            sendRouted = sendRouted,
            streamFrameRecvCount = streamFrames,
            gateEmitCount = gateEmits,
            coordinatorDeltaCount = coordinatorDeltas,
            repositoryIngestCount = repoIngests,
            turnCompleteCount = turnCompletes,
            reconcileMarkerCount = reconcileMarkers,
            projections = projections,
            socketSamples = socketSamples,
            httpPort = httpPort,
        )
    }

    fun summarize(metrics: DeviceGateMetrics): DeviceGateSummary {
        val violations = buildList {
            if (!metrics.sendRouted) add("send_not_observed")
            if (metrics.streamFrameRecvCount < 1) add("no_stream_frames")
            if (metrics.gateEmitCount < 1) add("no_typed_frame_emit")

            val projections = metrics.projections
            val streamingProjections = projections.filter { it.isStreaming }
            if (streamingProjections.isEmpty()) add("no_streaming_projection")

            // Visible timeline must GROW during the burst.
            val firstTotal = projections.firstOrNull()?.eventsTotal
            val maxTotal = projections.maxOfOrNull { it.eventsTotal }
            if (firstTotal == null || maxTotal == null || maxTotal <= firstTotal) {
                add("no_timeline_growth")
            }

            // The earliest live update (streaming OR growth) must NOT be gated on a reconcile.
            val firstLiveUpdate = projections.firstOrNull { proj ->
                proj.isStreaming || (firstTotal != null && proj.eventsTotal > firstTotal)
            }
            if (firstLiveUpdate != null && firstLiveUpdate.precededByReconcile) {
                add("update_only_after_reconcile")
            }

            // isStreaming must clear: exactly one true->false edge, final false.
            val fallingEdges = projections.zipWithNext()
                .count { (prev, next) -> prev.isStreaming && !next.isStreaming }
            if (streamingProjections.isNotEmpty()) {
                if (projections.last().isStreaming) add("isStreaming_not_cleared")
                if (fallingEdges != 1) add("isStreaming_terminal_edges_$fallingEdges")
            }

            // Exactly one terminal per send.
            if (metrics.turnCompleteCount != 1) add("terminal_count_${metrics.turnCompleteCount}")

            // No HTTP sockets from the app process to the backend port.
            IrohProbeAssertions.classifyNoHttp(metrics.socketSamples)?.let { add(it) }
        }
        return DeviceGateSummary(ok = violations.isEmpty(), violations = violations, metrics = metrics)
    }
}
