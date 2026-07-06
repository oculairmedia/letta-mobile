package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.letta.mobile.cli.probe.AppUidSocketScan
import com.letta.mobile.cli.probe.DeviceGateLogScan
import com.letta.mobile.cli.probe.DeviceGateSummary
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Analyzes a captured `adb logcat` dump plus `/proc/net/tcp` samples from the
 * dev APK and asserts the on-device Iroh live-streaming invariants
 * (letta-mobile-myv7c, epic phase P5). Device-free and deterministic: the
 * `scripts/iroh_device_gate.sh` wrapper does the adb capture; this command owns
 * all parse + assertion logic so it can be unit-tested under `:cli`.
 */
internal class AppServerIrohDeviceGateCommand : CliktCommand(
    name = "app-server-iroh-device-gate",
) {
    private val logcatFile by option(
        "--logcat",
        help = "Path to a captured adb logcat dump (any -v format with Telemetry/* tags).",
    ).required()

    private val conversationId by option(
        "--conversation",
        help = "Target conversation id used to scope uiProjection/gate markers.",
    ).required()

    private val netFile by option(
        "--net",
        help = "Path to concatenated /proc/net/tcp{,6} samples for the no-http socket assertion.",
    )

    private val appUid by option(
        "--app-uid",
        help = "App process uid; required with --net to scope socket rows to the app.",
    ).int()

    private val httpPort by option(
        "--http-port",
        help = "Backend/admin HTTP port that must have ZERO connections from the app uid.",
    ).int().default(8291)

    private val jsonOutput by option("--json", help = "Print machine-readable JSON summary.").flag(default = false)

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    override fun run() {
        val logFile = File(logcatFile)
        if (!logFile.isFile) throw UsageError("--logcat file not found: $logcatFile")
        val logLines = logFile.readLines()

        val socketSamples: List<Int> = netFile?.let { path ->
            val uid = appUid ?: throw UsageError("--app-uid is required when --net is given")
            val netLines = File(path).takeIf { it.isFile }?.readLines()
                ?: throw UsageError("--net file not found: $path")
            AppUidSocketScan.splitSamples(netLines).map { sample ->
                AppUidSocketScan.countConnectionsToPort(sample, uid, httpPort)
            }
        } ?: emptyList()

        val metrics = DeviceGateLogScan.parse(
            lines = logLines,
            conversationId = conversationId,
            socketSamples = socketSamples,
            httpPort = httpPort,
        )
        val summary = DeviceGateLogScan.summarize(metrics)

        printHumanSummary(summary)
        if (jsonOutput) println(json.encodeToString(summary))
        if (!summary.ok) exitProcess(1)
    }

    private fun printHumanSummary(summary: DeviceGateSummary) {
        val m = summary.metrics
        println("[iroh-device-gate] conversation=${m.conversationId} ok=${summary.ok}")
        println(
            "[iroh-device-gate] sendRouted=${m.sendRouted} streamFrames=${m.streamFrameRecvCount} " +
                "gateEmits=${m.gateEmitCount} coordinatorDeltas=${m.coordinatorDeltaCount} " +
                "repoIngests=${m.repositoryIngestCount} turnComplete=${m.turnCompleteCount} " +
                "reconcileMarkers=${m.reconcileMarkerCount} projections=${m.projections.size} " +
                "socketSamples=${m.socketSamples.size} maxSocketsToPort${m.httpPort}=${m.socketSamples.maxOrNull() ?: "NA"}",
        )
        m.projections.forEachIndexed { index, proj ->
            println(
                "[iroh-device-gate] projection[$index] eventsTotal=${proj.eventsTotal} " +
                    "messageCount=${proj.messageCount} isStreaming=${proj.isStreaming} " +
                    "precededByReconcile=${proj.precededByReconcile}",
            )
        }
        if (summary.violations.isNotEmpty()) {
            println("[iroh-device-gate] violations=${summary.violations.joinToString(",")}")
        }
    }
}
