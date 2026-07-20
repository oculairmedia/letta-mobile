package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.letta.mobile.data.transport.iroh.IrohProbeAssertions
import com.letta.mobile.data.transport.iroh.IrohProbeSummary
import com.letta.mobile.data.transport.iroh.IrohProbeTurnMetrics
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

internal class AppServerIrohProbeCommand : CliktCommand(name = "app-server-iroh-probe") {
    private val backend by option(
        "--backend",
        help = "Iroh backend: iroh://<node-id>@<host:port>[,...] or full EndpointTicket string.",
    )
    private val address by option("--address", help = "Deprecated alias for --backend.")
    private val token by option(
        "--token",
        envvar = "LETTA_IROH_AUTH_TOKEN",
        help = "Optional bearer token for the Iroh wrapper auth frame.",
    )
    private val adminBaseUrl by option(
        "--admin-base-url",
        envvar = "LETTA_PROBE_ADMIN_BASE",
        help = "Server-local HTTP admin API base used ONLY for scenario setup/verification " +
            "(hydrate-heavy seeding, cancel run-status poll). The iroh data path itself " +
            "must never dial it — that is what the no-http scenario asserts.",
    ).default("http://127.0.0.1:8291")
    private val agentId by option(
        "--agent-id",
        help = "Agent id. Omit or pass blank to mirror runtime_start's server default.",
    ).default("")
    private val conversationId by option(
        "--conversation-id",
        help = "Probe conversation id. Defaults to probe-conv-<epoch>.",
    )
    private val message by option("--message", help = "Probe user message text.").default("probe ping")
    private val seedMessages by option(
        "--messages",
        help = "hydrate-heavy: number of messages to seed via the admin base.",
    ).int().default(24)
    private val payloadBytes by option(
        "--payload-bytes",
        help = "hydrate-heavy: per-message payload size in bytes (default totals >1.5 MiB).",
    ).int().default(65_536)
    private val hydrateBudgetMs by option(
        "--hydrate-budget-ms",
        help = "hydrate-heavy: wall-clock budget for paging the full conversation back.",
    ).long().default(10_000)
    private val secondTurnDelayMs by option("--second-turn-delay-ms").long().default(5_000)
    private val idleMs by option(
        "--idle-ms",
        envvar = "LETTA_IROH_PROBE_IDLE_MS",
        help = "Idle-send scenario delay while keeping the connection open.",
    ).long().default(60_000)
    private val timeoutMs by option("--timeout-ms").long().default(60_000)
    private val scenarios by option(
        "--scenario",
        help = "Probe scenario to enable. Repeatable: admin-rpc, idle-send, restart-send, " +
            "hydrate-heavy, cancel-midstream, no-http, duplicate-send, all.",
    ).multiple()
    private val strictRedialDedupe by option(
        "--strict-redial-dedupe",
        help = "duplicate-send: treat a replayed turn after a forced redial as a violation " +
            "(3wq5g durable-dedupe contract; default is a note until P3 lands).",
    ).flag(default = false)
    private val wrapperRestartCmd by option(
        "--wrapper-restart-cmd",
        help = "Best-effort shell command to restart the wrapper between restart-send turns.",
    )
    private val jsonOutput by option("--json", help = "Print machine-readable JSON summary.").flag(default = false)
    private val dumpFramesPath by option(
        "--dump-frames",
        help = "Append each stream_delta's raw delta JSON to this JSONL file.",
    )
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    override fun run() = runBlocking {
        validateOptions()
        val requested = scenarios.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val unsupported = requested - SUPPORTED_SCENARIOS
        if (unsupported.isNotEmpty()) throw UsageError("Unsupported --scenario: ${unsupported.joinToString(",")}")
        val scenarioSet = expandScenarios(requested)
        val normalizedAddress = (backend ?: address
            ?: throw UsageError("--backend (or legacy --address) is required")).trim().removePrefix("iroh://")
        val probeConversationId = conversationId ?: "probe-conv-${Clock.System.now().toEpochMilliseconds()}"
        val options = IrohProbeOptions(
            token, adminBaseUrl, agentId, message, seedMessages, payloadBytes, hydrateBudgetMs,
            secondTurnDelayMs, idleMs, timeoutMs, strictRedialDedupe, wrapperRestartCmd, dumpFramesPath,
        )
        val fixture = ProbeSessionFixture(options)
        val admin = ProbeAdminClient(adminBaseUrl)
        val turns = mutableListOf<IrohProbeTurnMetrics>()
        val address = ProbeEndpointAddress(normalizedAddress)
        fun targetFor(suffix: String = "") = ProbeTarget(
            address = address,
            conversationId = ProbeConversationId(
                if (suffix.isEmpty()) probeConversationId else "$probeConversationId$suffix",
            ),
        )

        if ("no-http" in scenarioSet) {
            turns += NoHttpProbeScenario(options, fixture, admin).run(targetFor("-nohttp"))
        }
        turns += LegacyProbeScenarios(options, fixture).run(scenarioSet, targetFor())
        if ("duplicate-send" in scenarioSet) {
            turns += DuplicateSendProbeScenario(options, fixture).run(targetFor("-dup"))
        }
        if ("cancel-midstream" in scenarioSet) {
            turns += CancelMidstreamProbeScenario(options, fixture, admin).run(targetFor("-cancel"))
        }
        if ("hydrate-heavy" in scenarioSet) {
            turns += HydrateHeavyProbeScenario(options, fixture, admin).run(targetFor("-hydrate"))
        }

        val summary = IrohProbeAssertions.summarize(turns)
        printHumanSummary(summary, probeConversationId)
        if (jsonOutput) println(json.encodeToString(summary))
        if (!summary.ok) exitProcess(1)
    }

    private fun validateOptions() {
        if (secondTurnDelayMs < 0) throw UsageError("--second-turn-delay-ms must be >= 0")
        if (idleMs < 0) throw UsageError("--idle-ms must be >= 0")
        if (timeoutMs <= 0) throw UsageError("--timeout-ms must be > 0")
        if (seedMessages <= 0) throw UsageError("--messages must be > 0")
        if (payloadBytes <= 0) throw UsageError("--payload-bytes must be > 0")
    }

    private fun printHumanSummary(summary: IrohProbeSummary, conversationId: String) {
        println("[iroh-probe] conversation=$conversationId ok=${summary.ok}")
        summary.turns.forEach { turn ->
            println(
                "[iroh-probe] scenario=${turn.scenario ?: "base"} turn=${turn.turn} dialMs=${turn.dialMs ?: "NA"} " +
                    "firstFrameMs=${turn.firstFrameMs ?: "NA"} assistantDeltas=${turn.assistantDeltaCount} " +
                    "assistantIds=${turn.assistantMessageIds.size} reasoningIds=${turn.reasoningMessageIds.size} " +
                    "turnDone=${turn.turnDoneCount} errors=${turn.errorFrames.size} timedOut=${turn.timedOut} " +
                    "untyped=${turn.untypedFrameCount} afterTerminal=${turn.framesAfterTerminal} " +
                    "terminal=${turn.terminalStatus ?: "NA"}/${turn.terminalRunId ?: "NA"} " +
                    "notes=${turn.notes.joinToString("|")}",
            )
        }
        if (summary.violations.isNotEmpty()) {
            println("[iroh-probe] violations=${summary.violations.joinToString(",")}")
        }
    }

    internal companion object {
        val SUPPORTED_SCENARIOS: Set<String> = setOf(
            "admin-rpc", "idle-send", "restart-send", "hydrate-heavy",
            "cancel-midstream", "no-http", "duplicate-send", "all",
        )

        fun expandScenarios(requested: Set<String>): Set<String> =
            if ("all" in requested) SUPPORTED_SCENARIOS - "all" else requested
    }
}
