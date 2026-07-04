package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.letta.mobile.cli.probe.NoHttpSocketScan
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputMessage
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartCreateConversationOptions
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.iroh.IrohAppServerTransport
import com.letta.mobile.data.transport.iroh.IrohAppServerTransportAdapter
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.data.transport.iroh.IrohProbeAssertions
import com.letta.mobile.data.transport.iroh.IrohProbeSummary
import com.letta.mobile.data.transport.iroh.IrohProbeTurnMetrics
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class AppServerIrohProbeCommand : CliktCommand(
    name = "app-server-iroh-probe",
) {
    private val backend by option(
        "--backend",
        help = "Iroh backend: iroh://<node-id>@<host:port>[,...] or full EndpointTicket string.",
    )

    private val address by option(
        "--address",
        help = "Deprecated alias for --backend.",
    )

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

    private val secondTurnDelayMs by option("--second-turn-delay-ms")
        .long()
        .default(5_000)

    private val idleMs by option(
        "--idle-ms",
        envvar = "LETTA_IROH_PROBE_IDLE_MS",
        help = "Idle-send scenario delay while keeping the connection open.",
    ).long().default(60_000)

    private val timeoutMs by option("--timeout-ms")
        .long()
        .default(60_000)

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

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    override fun run() = runBlocking {
        if (secondTurnDelayMs < 0) throw UsageError("--second-turn-delay-ms must be >= 0")
        if (idleMs < 0) throw UsageError("--idle-ms must be >= 0")
        if (timeoutMs <= 0) throw UsageError("--timeout-ms must be > 0")
        if (seedMessages <= 0) throw UsageError("--messages must be > 0")
        if (payloadBytes <= 0) throw UsageError("--payload-bytes must be > 0")

        val requested = scenarios.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val unsupported = requested - SUPPORTED_SCENARIOS
        if (unsupported.isNotEmpty()) throw UsageError("Unsupported --scenario: ${unsupported.joinToString(",")}")
        val scenarioSet = expandScenarios(requested)

        val backendAddress = backend ?: address
            ?: throw UsageError("--backend (or legacy --address) is required")
        val normalizedAddress = backendAddress.trim().removePrefix("iroh://")

        val probeConversationId = conversationId ?: "probe-conv-${Clock.System.now().toEpochMilliseconds()}"
        val turns = mutableListOf<IrohProbeTurnMetrics>()

        // no-http runs FIRST so this process has performed zero admin HTTP setup
        // calls before the scan asserts the iroh data path opened no TCP sockets
        // to the admin port.
        if ("no-http" in scenarioSet) {
            turns += runNoHttpScenario(normalizedAddress, "$probeConversationId-nohttp")
        }

        runLegacyScenarios(scenarioSet, normalizedAddress, probeConversationId, turns)

        if ("duplicate-send" in scenarioSet) {
            turns += runDuplicateSendScenario(normalizedAddress, "$probeConversationId-dup")
        }
        if ("cancel-midstream" in scenarioSet) {
            turns += runCancelMidstreamScenario(normalizedAddress, "$probeConversationId-cancel")
        }
        if ("hydrate-heavy" in scenarioSet) {
            turns += runHydrateHeavyScenario(normalizedAddress, "$probeConversationId-hydrate")
        }

        val summary = IrohProbeAssertions.summarize(turns)
        printHumanSummary(summary, probeConversationId)
        if (jsonOutput) println(json.encodeToString(summary))
        if (!summary.ok) exitProcess(1)
    }

    internal companion object {
        val SUPPORTED_SCENARIOS: Set<String> = setOf(
            "admin-rpc",
            "idle-send",
            "restart-send",
            "hydrate-heavy",
            "cancel-midstream",
            "no-http",
            "duplicate-send",
            "all",
        )

        fun expandScenarios(requested: Set<String>): Set<String> =
            if ("all" in requested) SUPPORTED_SCENARIOS - "all" else requested
    }

    // ------------------------------------------------------------------
    // Legacy base flow: default two-turn probe + admin-rpc / idle-send /
    // restart-send interplay, unchanged from letta-mobile-5b88p.
    // ------------------------------------------------------------------

    private suspend fun runLegacyScenarios(
        scenarioSet: Set<String>,
        normalizedAddress: String,
        probeConversationId: String,
        turns: MutableList<IrohProbeTurnMetrics>,
    ) {
        val legacyOnly = scenarioSet intersect setOf("admin-rpc", "idle-send", "restart-send")
        val newOnly = scenarioSet - legacyOnly
        // If ONLY new scenarios were requested, skip the legacy base flow.
        if (scenarioSet.isNotEmpty() && legacyOnly.isEmpty() && newOnly.isNotEmpty()) return

        if ("idle-send" in scenarioSet) {
            turns += runIdleSendScenario(normalizedAddress, probeConversationId, scenarioSet)
        } else {
            turns += runProbeTurn(
                turn = 1,
                normalizedAddress = normalizedAddress,
                conversationId = probeConversationId,
                runAdminRpcScenario = "admin-rpc" in scenarioSet,
            )
            if ("restart-send" in scenarioSet) {
                runWrapperRestart()?.let { turns += restartSkippedTurn(turn = 2, note = it) }
            } else {
                delay(secondTurnDelayMs)
            }
            if (turns.none { it.turn == 2 }) {
                turns += runProbeTurn(turn = 2, normalizedAddress = normalizedAddress, conversationId = probeConversationId)
            }
        }

        if ("restart-send" in scenarioSet && "idle-send" in scenarioSet) {
            runWrapperRestart()?.let { turns += restartSkippedTurn(turn = turns.size + 1, note = it) }
                ?: run {
                    turns += runProbeTurn(
                        turn = turns.size + 1,
                        normalizedAddress = normalizedAddress,
                        conversationId = probeConversationId,
                    )
                }
        }
    }

    private suspend fun runIdleSendScenario(
        normalizedAddress: String,
        conversationId: String,
        scenarioSet: Set<String>,
    ): List<IrohProbeTurnMetrics> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var session: ProbeSession? = null
        val turns = mutableListOf<IrohProbeTurnMetrics>()
        val turnStartedAt = nowMs()
        try {
            val setup = withTimeoutOrNull(timeoutMs) {
                establishSession(
                    normalizedAddress = normalizedAddress,
                    conversationId = conversationId,
                    scope = scope,
                    turn = 1,
                    runAdminRpcScenario = "admin-rpc" in scenarioSet,
                    turnStartedAt = turnStartedAt,
                )
            }
            if (setup == null) {
                val violation = IrohProbeAssertions.classifyIdleSendFailure("setup timeout after ${timeoutMs}ms")
                turns += IrohProbeTurnMetrics(
                    turn = 1,
                    errorFrames = listOf(violation),
                    scenarioViolations = listOf(violation),
                    timedOut = true,
                )
                return turns
            }
            session = setup
            val firstTurn = sendProbeInput(turn = 1, session = session, turnStartedAt = turnStartedAt)
            turns += firstTurn
            delay(idleMs)
            val secondTurn = sendProbeInput(turn = 2, session = session, sendFailureViolation = true)
            // Both turns share ONE connection, so event_seq must keep rising
            // across the turn boundary — a per-turn reset (each turn restarting
            // at 0) is individually monotonic and only visible here.
            val crossTurn = IrohProbeAssertions.classifyCrossTurnEventSeq(
                previousTurnSeqs = firstTurn.eventSeqs,
                nextTurnSeqs = secondTurn.eventSeqs,
            )
            turns += if (crossTurn == null) {
                secondTurn
            } else {
                secondTurn.copy(scenarioViolations = secondTurn.scenarioViolations + crossTurn)
            }
        } catch (error: Throwable) {
            val message = error.message ?: error.toString()
            val violation = if (message.contains("Conversation not found", ignoreCase = true)) {
                IrohProbeAssertions.classifyConversationBootstrap(message)
            } else {
                IrohProbeAssertions.classifyIdleSendFailure(message)
            }
            turns += IrohProbeTurnMetrics(
                turn = (turns.size + 1).coerceAtLeast(1),
                errorFrames = listOf(violation),
                scenarioViolations = listOf(violation),
                timedOut = false,
            )
        } finally {
            runCatching { session?.transport?.close() }
            runCatching { session?.endpoint?.close() }
            scope.cancel()
        }
        return turns
    }

    private suspend fun runProbeTurn(
        turn: Int,
        normalizedAddress: String,
        conversationId: String,
        runAdminRpcScenario: Boolean = false,
    ): IrohProbeTurnMetrics {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var session: ProbeSession? = null
        val turnStartedAt = nowMs()
        val setupMetrics = ProbeSetupMetrics(turn)
        return try {
            val metrics = withTimeoutOrNull(timeoutMs) {
                session = establishSession(
                    normalizedAddress = normalizedAddress,
                    conversationId = conversationId,
                    scope = scope,
                    turn = turn,
                    runAdminRpcScenario = runAdminRpcScenario,
                    turnStartedAt = turnStartedAt,
                    setupMetrics = setupMetrics,
                )
                sendProbeInput(turn = turn, session = session, turnStartedAt = turnStartedAt)
            }
            metrics ?: setupMetrics.toFailureMetrics("timeout after ${timeoutMs}ms", timedOut = true)
        } catch (error: Throwable) {
            setupMetrics.toFailureMetrics(error.message ?: error.toString())
        } finally {
            runCatching { session?.transport?.close() }
            runCatching { session?.endpoint?.close() }
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // no-http scenario (qfa81 headline invariant)
    // ------------------------------------------------------------------

    private suspend fun runNoHttpScenario(
        normalizedAddress: String,
        conversationId: String,
    ): IrohProbeTurnMetrics {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var session: ProbeSession? = null
        val adminPort = adminPort()
        val samples: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val scanUnsupported = AtomicBoolean(false)
        fun sample() {
            when (val count = NoHttpSocketScan.connectionsToPort(adminPort)) {
                null -> scanUnsupported.set(true)
                else -> samples += count
            }
        }
        val turnStartedAt = nowMs()
        // Point-in-time boundary samples alone would miss a transient HTTP
        // fallback that opens, completes, and closes between them (e.g. an
        // HttpURLConnection with Connection: close), so also sample on a
        // continuous ~100ms ticker for the scenario's whole duration.
        val sampler = scope.launch {
            while (true) {
                sample()
                delay(100)
            }
        }
        return try {
            sample()
            val metrics = withTimeoutOrNull(timeoutMs) {
                session = establishSession(
                    normalizedAddress = normalizedAddress,
                    conversationId = conversationId,
                    scope = scope,
                    turn = 1,
                    runAdminRpcScenario = false,
                    turnStartedAt = turnStartedAt,
                )
                sample()
                val turnMetrics = sendProbeInput(
                    turn = 1,
                    session = session!!,
                    turnStartedAt = turnStartedAt,
                    scenario = "no-http",
                )
                sample()
                turnMetrics
            } ?: IrohProbeTurnMetrics(
                turn = 1,
                scenario = "no-http",
                timedOut = true,
                scenarioViolations = listOf("no_http_setup_timeout"),
            )
            sampler.cancelAndJoin()
            val snapshot = samples.toList()
            val violations = listOfNotNull(IrohProbeAssertions.classifyNoHttp(snapshot))
            val notes = if (scanUnsupported.get()) listOf("no_http_scan_unsupported_platform") else emptyList()
            metrics.copy(
                scenarioViolations = metrics.scenarioViolations + violations,
                notes = metrics.notes + notes +
                    "no_http_socket_samples=${snapshot.size} max=${snapshot.maxOrNull() ?: -1}",
            )
        } finally {
            runCatching { sampler.cancel() }
            runCatching { session?.transport?.close() }
            runCatching { session?.endpoint?.close() }
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // duplicate-send scenario (exactly one assistant turn per client_message_id,
    // incl. a replay across a forced redial)
    // ------------------------------------------------------------------

    private suspend fun runDuplicateSendScenario(
        normalizedAddress: String,
        conversationId: String,
    ): List<IrohProbeTurnMetrics> {
        val turns = mutableListOf<IrohProbeTurnMetrics>()
        val duplicateClientMessageId = "probe-dup-${UUID.randomUUID()}"
        val graceMs = 3_000L

        // Phase 1+2: first send and same-connection replay.
        run {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var session: ProbeSession? = null
            try {
                val phaseTurns = withTimeoutOrNull(timeoutMs * 2) {
                    val established = establishSession(
                        normalizedAddress = normalizedAddress,
                        conversationId = conversationId,
                        scope = scope,
                        turn = 1,
                        runAdminRpcScenario = false,
                    )
                    session = established
                    val first = sendProbeInput(
                        turn = 1,
                        session = established,
                        scenario = "duplicate-send",
                        clientMessageId = duplicateClientMessageId,
                    )
                    val replay = observeTerminals(established, quiesceMs = graceMs) {
                        sendInputFrame(established, duplicateClientMessageId)
                    }
                    val replayTerminals = replay.terminalCount
                    listOf(
                        first,
                        IrohProbeTurnMetrics(
                            turn = 2,
                            scenario = "duplicate-send",
                            profile = IrohProbeAssertions.PROFILE_REPORT,
                            dialMs = established.dialMs,
                            turnDoneCount = replayTerminals,
                            eventSeqs = replay.observedEventSeqs,
                            untypedFrameCount = replay.untypedFrameCount,
                            scenarioViolations = listOfNotNull(
                                IrohProbeAssertions.classifyDuplicateSend(1 + replayTerminals, "same-connection"),
                            ),
                            notes = listOf("duplicate_send_same_connection_replay_terminals=$replayTerminals"),
                        ),
                    )
                }
                turns += phaseTurns ?: listOf(
                    IrohProbeTurnMetrics(
                        turn = 1,
                        scenario = "duplicate-send",
                        timedOut = true,
                        scenarioViolations = listOf("duplicate_send_setup_timeout"),
                    ),
                )
            } catch (error: Throwable) {
                turns += IrohProbeTurnMetrics(
                    turn = 1,
                    scenario = "duplicate-send",
                    scenarioViolations = listOf("duplicate_send_failed:${error.message ?: error}"),
                )
            } finally {
                runCatching { session?.transport?.close() }
                runCatching { session?.endpoint?.close() }
                scope.cancel()
            }
        }

        // Phase 3: forced redial, replay the SAME client_message_id on a fresh
        // connection. Durable dedupe (3wq5g) should ignore it; until P3 lands the
        // per-connection dedupe set makes this a note unless --strict-redial-dedupe.
        run {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var session: ProbeSession? = null
            try {
                val redialTurn = withTimeoutOrNull(timeoutMs) {
                    val established = establishSession(
                        normalizedAddress = normalizedAddress,
                        conversationId = conversationId,
                        scope = scope,
                        turn = 3,
                        runAdminRpcScenario = false,
                    )
                    session = established
                    val redialReplay = observeTerminals(established, quiesceMs = graceMs) {
                        sendInputFrame(established, duplicateClientMessageId)
                    }
                    val redialTerminals = redialReplay.terminalCount
                    val violation = IrohProbeAssertions.classifyDuplicateSend(1 + redialTerminals, "after-redial")
                    val enforced = strictRedialDedupe
                    IrohProbeTurnMetrics(
                        turn = 3,
                        scenario = "duplicate-send",
                        profile = IrohProbeAssertions.PROFILE_REPORT,
                        dialMs = established.dialMs,
                        turnDoneCount = redialTerminals,
                        eventSeqs = redialReplay.observedEventSeqs,
                        untypedFrameCount = redialReplay.untypedFrameCount,
                        scenarioViolations = if (enforced) listOfNotNull(violation) else emptyList(),
                        notes = buildList {
                            add("duplicate_send_redial_replay_terminals=$redialTerminals")
                            if (!enforced && violation != null) {
                                add("known_3wq5g_redial_dedupe_gap:$violation (enforced after P3 via --strict-redial-dedupe)")
                            }
                        },
                    )
                }
                turns += redialTurn ?: IrohProbeTurnMetrics(
                    turn = 3,
                    scenario = "duplicate-send",
                    timedOut = true,
                    scenarioViolations = listOf("duplicate_send_redial_timeout"),
                )
            } catch (error: Throwable) {
                turns += IrohProbeTurnMetrics(
                    turn = 3,
                    scenario = "duplicate-send",
                    scenarioViolations = listOf("duplicate_send_redial_failed:${error.message ?: error}"),
                )
            } finally {
                runCatching { session?.transport?.close() }
                runCatching { session?.endpoint?.close() }
                scope.cancel()
            }
        }

        return turns
    }

    /**
     * Observes frames on [session] while [block] executes and then until the
     * stream stays quiet for [quiesceMs] (condition-based, not a fixed sleep,
     * so a slow replayed turn cannot escape the window; callers bound the
     * total wait with withTimeoutOrNull). Returns the full accumulator so the
     * replay frames are also checked for typing and event_seq shape.
     */
    private suspend fun observeTerminals(
        session: ProbeSession,
        quiesceMs: Long = 3_000,
        block: suspend () -> Unit,
    ): ProbeAccumulator {
        val accumulator = ProbeAccumulator(turn = 0)
        val collector = session.scope.launch {
            session.client.events.collect { received ->
                val inbound = received.frame
                if (!inbound.matches(session.runtime)) return@collect
                accumulator.record(inbound)
            }
        }
        try {
            block()
            var drained = accumulator.recordedFrameCount
            while (true) {
                delay(quiesceMs)
                val current = accumulator.recordedFrameCount
                if (current == drained) break
                drained = current
            }
        } finally {
            collector.cancelAndJoin()
        }
        return accumulator
    }

    private suspend fun sendInputFrame(session: ProbeSession, clientMessageId: String) {
        session.client.input(
            AppServerCommand.Input(
                runtime = session.runtime,
                payload = AppServerInputPayload.CreateMessage(
                    messages = listOf(
                        AppServerInputMessage.userText(
                            text = message,
                            clientMessageId = clientMessageId,
                        ),
                    ),
                ),
            ),
        )
    }

    // ------------------------------------------------------------------
    // cancel-midstream scenario
    // ------------------------------------------------------------------

    private suspend fun runCancelMidstreamScenario(
        normalizedAddress: String,
        conversationId: String,
    ): IrohProbeTurnMetrics {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var session: ProbeSession? = null
        var abortJob: Job? = null
        val turnStartedAt = nowMs()
        return try {
            val metrics = withTimeoutOrNull(timeoutMs) {
                val established = establishSession(
                    normalizedAddress = normalizedAddress,
                    conversationId = conversationId,
                    scope = scope,
                    turn = 1,
                    runAdminRpcScenario = false,
                    turnStartedAt = turnStartedAt,
                )
                session = established
                val observed = ProbeAccumulator(turn = 1)
                var firstFrameMs: Long? = null
                val collector = established.scope.launch {
                    established.client.events.collect { received ->
                        val inbound = received.frame
                        if (!inbound.matches(established.runtime)) return@collect
                        if (firstFrameMs == null) firstFrameMs = nowMs() - turnStartedAt
                        observed.record(inbound)
                    }
                }
                sendInputFrame(established, clientMessageId = "probe-cancel-${UUID.randomUUID()}")

                // Wait for the first stream delta carrying the server's run_id.
                while (observed.activeRunId == null && observed.terminalCount == 0) {
                    delay(20)
                }
                val runId = observed.activeRunId
                if (runId != null) {
                    // abort_message with the REAL run_id of the active turn.
                    // Fire-and-forget: cancellation success is judged by the
                    // terminal frame + the server-side run status, not the ack.
                    abortJob = established.scope.launch {
                        runCatching {
                            established.client.abort(
                                AppServerCommand.AbortMessage(
                                    runtime = established.runtime,
                                    requestId = "probe-abort-${UUID.randomUUID()}",
                                    runId = runId,
                                ),
                            )
                        }
                    }
                }

                while (observed.terminalCount == 0) {
                    delay(50)
                }
                delay(500)
                collector.cancelAndJoin()

                val violations = mutableListOf<String>()
                val notes = mutableListOf<String>()
                if (runId == null) {
                    violations += "cancel_no_run_id_before_terminal"
                } else {
                    when (val status = pollRunStatus(runId, deadlineMs = 5_000)) {
                        null -> notes += "cancel_run_status_unverified:admin_base_unreachable"
                        "running" -> violations += "cancel_run_still_running_after_5s"
                        else -> notes += "cancel_run_status=$status"
                    }
                }
                observed.toMetrics(
                    dialMs = established.dialMs,
                    firstFrameMs = firstFrameMs,
                    timedOut = false,
                    scenario = "cancel-midstream",
                    profile = IrohProbeAssertions.PROFILE_CANCEL,
                    extraViolations = violations,
                    extraNotes = notes,
                )
            }
            metrics ?: IrohProbeTurnMetrics(
                turn = 1,
                scenario = "cancel-midstream",
                profile = IrohProbeAssertions.PROFILE_CANCEL,
                timedOut = true,
            )
        } catch (error: Throwable) {
            IrohProbeTurnMetrics(
                turn = 1,
                scenario = "cancel-midstream",
                profile = IrohProbeAssertions.PROFILE_CANCEL,
                scenarioViolations = listOf("cancel_failed:${error.message ?: error}"),
            )
        } finally {
            runCatching { abortJob?.cancel() }
            runCatching { session?.transport?.close() }
            runCatching { session?.endpoint?.close() }
            scope.cancel()
        }
    }

    private suspend fun pollRunStatus(runId: String, deadlineMs: Long): String? {
        val deadline = nowMs() + deadlineMs
        var lastStatus: String? = null
        while (nowMs() < deadline) {
            val status = runCatching {
                httpJson("GET", "${adminBaseUrl.trimEnd('/')}/v1/runs/$runId")
                    .jsonObject["status"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (status == null) {
                delay(250)
                continue
            }
            lastStatus = status
            if (status != "running") return status
            delay(250)
        }
        return lastStatus
    }

    // ------------------------------------------------------------------
    // hydrate-heavy scenario (bax40 gate)
    // ------------------------------------------------------------------

    private suspend fun runHydrateHeavyScenario(
        normalizedAddress: String,
        conversationId: String,
    ): IrohProbeTurnMetrics {
        // Seed >1.5 MiB of messages via the admin base (setup path, NOT the
        // iroh data path — no-http runs before any of this).
        val seeded = try {
            val response = httpJson(
                "POST",
                "${adminBaseUrl.trimEnd('/')}/probe/seed",
                buildJsonObject {
                    put("conversation_id", conversationId)
                    put("count", seedMessages)
                    put("payload_bytes", payloadBytes)
                }.toString(),
            ).jsonObject
            response["seeded"]?.jsonPrimitive?.longOrNull?.toInt() ?: seedMessages
        } catch (error: Exception) {
            return IrohProbeTurnMetrics(
                turn = 1,
                scenario = "hydrate-heavy",
                profile = IrohProbeAssertions.PROFILE_REPORT,
                scenarioViolations = listOf("hydrate_seed_failed:${error.message ?: error}"),
            )
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var session: ProbeSession? = null
        return try {
            val startedAt = nowMs()
            val metrics = withTimeoutOrNull(timeoutMs) {
                val established = establishSession(
                    normalizedAddress = normalizedAddress,
                    conversationId = conversationId,
                    scope = scope,
                    turn = 1,
                    runAdminRpcScenario = false,
                    turnStartedAt = startedAt,
                )
                session = established

                // Page the full conversation back over admin_rpc. Page size keeps
                // each response frame under the 1 MiB iroh frame cap; P2 chunking
                // raises the deliverable page size, not this scenario's contract.
                val pageLimit = ((700 * 1024) / payloadBytes).coerceIn(1, 100)
                var after: String? = null
                var listed = 0
                val pageFailures = mutableListOf<String>()
                var page = 0
                val maxPages = (seeded / pageLimit) + 5
                while (page < maxPages) {
                    page += 1
                    val response = established.client.adminRpc(
                        AppServerCommand.AdminRpc(
                            requestId = "probe-hydrate-$page-${UUID.randomUUID()}",
                            method = "message.list",
                            params = buildJsonObject {
                                put("conversation_id", conversationId)
                                put("limit", pageLimit.toString())
                                after?.let { put("after", it) }
                            },
                        ),
                    )
                    val items = response.result as? JsonArray
                    if (!response.success || items == null) {
                        pageFailures += "page-$page: ${response.error ?: "non-array result"}"
                        break
                    }
                    listed += items.size
                    if (items.isEmpty() || items.size < pageLimit) break
                    after = items.last().jsonObject["id"]?.jsonPrimitive?.contentOrNull
                        ?: run {
                            pageFailures += "page-$page: last item missing id"
                            break
                        }
                }
                val wallMs = nowMs() - startedAt
                IrohProbeTurnMetrics(
                    turn = 1,
                    scenario = "hydrate-heavy",
                    profile = IrohProbeAssertions.PROFILE_REPORT,
                    dialMs = established.dialMs,
                    wallMs = wallMs,
                    scenarioViolations = IrohProbeAssertions.classifyHydrateHeavy(
                        seededCount = seeded,
                        listedCount = listed,
                        wallMs = wallMs,
                        budgetMs = hydrateBudgetMs,
                        pageFailures = pageFailures,
                    ),
                    notes = listOf(
                        "hydrate_seeded=$seeded",
                        "hydrate_listed=$listed",
                        "hydrate_page_limit=$pageLimit",
                        "hydrate_total_bytes=${seeded.toLong() * payloadBytes}",
                    ),
                )
            }
            metrics ?: IrohProbeTurnMetrics(
                turn = 1,
                scenario = "hydrate-heavy",
                profile = IrohProbeAssertions.PROFILE_REPORT,
                timedOut = true,
                scenarioViolations = listOf("hydrate_heavy_timeout_after_${timeoutMs}ms"),
            )
        } catch (error: Throwable) {
            IrohProbeTurnMetrics(
                turn = 1,
                scenario = "hydrate-heavy",
                profile = IrohProbeAssertions.PROFILE_REPORT,
                scenarioViolations = listOf("hydrate_heavy_failed:${error.message ?: error}"),
            )
        } finally {
            runCatching { session?.transport?.close() }
            runCatching { session?.endpoint?.close() }
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // Shared session plumbing
    // ------------------------------------------------------------------

    private suspend fun establishSession(
        normalizedAddress: String,
        conversationId: String,
        scope: CoroutineScope,
        turn: Int,
        runAdminRpcScenario: Boolean,
        turnStartedAt: Long = nowMs(),
        setupMetrics: ProbeSetupMetrics = ProbeSetupMetrics(turn),
    ): ProbeSession {
        val endpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.Companion.defaultMode()))
        val transport = IrohAppServerTransportAdapter(endpoint).createTransport(
            endpoint = AppServerEndpoint(scheme = "iroh", address = normalizedAddress),
            scope = scope,
        ) as IrohAppServerTransport
        transport.awaitConnectionReady()
        val dialMs = nowMs() - turnStartedAt
        setupMetrics.markDialSucceeded(dialMs)
        val client = DefaultAppServerClient(transport, requestTimeoutMs = timeoutMs)

        token?.takeIf { it.isNotBlank() }?.let { bearer ->
            setupMetrics.beginStage("auth")
            val auth = client.auth(
                AppServerCommand.Auth(
                    requestId = "probe-auth-${UUID.randomUUID()}",
                    token = bearer,
                    capabilities = listOf(IrohFrameCodec.FRAME_PART_CAPABILITY),
                ),
            )
            if (!auth.success) error(auth.error ?: "Iroh auth failed")
            setupMetrics.markStageSucceeded("auth")
        } ?: run {
            // Even without a bearer token, send the auth handshake so the server
            // learns this probe can reassemble frame_part chunked frames
            // (needed for hydrate-heavy >1MiB message.list responses).
            runCatching {
                client.auth(
                    AppServerCommand.Auth(
                        requestId = "probe-auth-${UUID.randomUUID()}",
                        token = "",
                        capabilities = listOf(IrohFrameCodec.FRAME_PART_CAPABILITY),
                    ),
                )
            }
        }

        setupMetrics.beginStage("runtime_start")
        val runtimeStart = AppServerCommand.RuntimeStart(
            requestId = "probe-runtime-${UUID.randomUUID()}",
            agentId = agentId.takeIf { it.isNotBlank() },
            conversationId = conversationId,
            createConversation = AppServerRuntimeStartCreateConversationOptions(body = null),
            mode = AppServerPermissionMode.Standard,
            clientInfo = AppServerRuntimeStartClientInfo(
                name = "letta-mobile-cli-iroh-probe",
                version = "letta-mobile-q5iiv",
            ),
            recoverApprovals = true,
            forceDeviceStatus = true,
        )
        val runtimeResponse = client.runtimeStart(runtimeStart)
        if (!runtimeResponse.success) error(runtimeResponse.error ?: "App Server runtime_start failed")
        setupMetrics.markStageSucceeded("runtime_start")
        val runtime = runtimeResponse.runtime ?: AppServerRuntimeScope(
            agentId = runtimeStart.agentId.orEmpty(),
            conversationId = conversationId,
        )
        val scenarioViolations = if (runAdminRpcScenario) {
            setupMetrics.beginStage("admin_rpc")
            runAdminRpcChecks(client, conversationId)
        } else {
            emptyList()
        }
        setupMetrics.markStageSucceeded("admin_rpc")
        return ProbeSession(endpoint, transport, client, runtime, dialMs, scenarioViolations, scope)
    }

    private suspend fun sendProbeInput(
        turn: Int,
        session: ProbeSession,
        turnStartedAt: Long = nowMs(),
        sendFailureViolation: Boolean = false,
        scenario: String? = null,
        clientMessageId: String = "probe-local-${UUID.randomUUID()}",
    ): IrohProbeTurnMetrics {
        val observed = ProbeAccumulator(turn)
        observed.scenarioViolations += session.scenarioViolations
        var firstFrameMs: Long? = null
        var timedOut = false
        val completed = withTimeoutOrNull(timeoutMs) {
            val collector = session.scope.launch {
                session.client.events.collect { received ->
                    val inbound = received.frame
                    if (!inbound.matches(session.runtime)) return@collect
                    if (firstFrameMs == null) firstFrameMs = nowMs() - turnStartedAt
                    observed.record(inbound)
                }
            }
            try {
                session.client.input(
                    AppServerCommand.Input(
                        runtime = session.runtime,
                        payload = AppServerInputPayload.CreateMessage(
                            messages = listOf(
                                AppServerInputMessage.userText(
                                    text = message,
                                    clientMessageId = clientMessageId,
                                ),
                            ),
                        ),
                    ),
                )
            } catch (error: Throwable) {
                val violation = if (sendFailureViolation) {
                    IrohProbeAssertions.classifyIdleSendFailure(error.message ?: error.toString())
                } else {
                    "probe_error: ${error.message ?: error.toString()}"
                }
                observed.errors += violation
                observed.scenarioViolations += violation
                collector.cancelAndJoin()
                return@withTimeoutOrNull true
            }
            while (observed.terminalCount == 0) {
                delay(50)
            }
            // Quiescence drain instead of a fixed post-terminal window: keep
            // collecting until no new frame arrives for 500ms, so late
            // duplicates / frames-after-terminal cannot slip past the cutoff.
            // Bounded by the enclosing withTimeoutOrNull.
            var drainedFrames = observed.recordedFrameCount
            while (true) {
                delay(500)
                val current = observed.recordedFrameCount
                if (current == drainedFrames) break
                drainedFrames = current
            }
            collector.cancelAndJoin()
            true
        }
        timedOut = completed != true
        if (sendFailureViolation && timedOut) {
            observed.scenarioViolations += IrohProbeAssertions.classifyIdleSendFailure("timeout")
        }
        if (sendFailureViolation && observed.errors.isNotEmpty()) {
            observed.scenarioViolations += observed.errors.map { IrohProbeAssertions.classifyIdleSendFailure(it) }
        }
        return observed.toMetrics(
            dialMs = session.dialMs,
            firstFrameMs = firstFrameMs,
            timedOut = timedOut,
            scenario = scenario,
        )
    }

    private suspend fun runAdminRpcChecks(client: DefaultAppServerClient, conversationId: String): List<String> {
        val checks = listOf(
            "message.list" to buildJsonObject {
                put("conversation_id", conversationId)
                put("limit", "10")
            },
            "conversation.list" to buildJsonObject {
                put("limit", "10")
            },
        )
        return checks.mapNotNull { (method, params) ->
            val response = client.adminRpc(
                AppServerCommand.AdminRpc(
                    requestId = "probe-admin-rpc-${UUID.randomUUID()}",
                    method = method,
                    params = params,
                ),
            )
            IrohProbeAssertions.classifyAdminRpc(
                method = method,
                success = response.success,
                resultIsArray = response.result is JsonArray,
                error = response.error,
            )
        }
    }

    private fun runWrapperRestart(): String? {
        val command = wrapperRestartCmd?.takeIf { it.isNotBlank() }
            ?: return "restart-send skipped: --wrapper-restart-cmd not provided"
        val exit = ProcessBuilder("bash", "-lc", command)
            .inheritIO()
            .start()
            .waitFor()
        if (exit != 0) error("wrapper restart command failed with exit code $exit")
        return null
    }

    private fun restartSkippedTurn(turn: Int, note: String): IrohProbeTurnMetrics =
        IrohProbeTurnMetrics(
            turn = turn,
            assistantDeltaCount = 1,
            assistantMessageIds = listOf("restart-send-skipped"),
            turnDoneCount = 1,
            notes = listOf(note),
            skipped = true,
        )

    private fun AppServerInboundFrame.matches(runtime: AppServerRuntimeScope): Boolean {
        val frameRuntime = this.runtime ?: return true
        return frameRuntime.agentId == runtime.agentId && frameRuntime.conversationId == runtime.conversationId
    }

    private fun adminPort(): Int {
        val parsed = runCatching { URI(adminBaseUrl).port }.getOrDefault(-1)
        return if (parsed > 0) parsed else 8291
    }

    private fun httpJson(method: String, url: String, body: String? = null): JsonElement {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            // Keep admin setup connections short-lived so they can never be
            // mistaken for a lingering client HTTP data path.
            connection.setRequestProperty("Connection", "close")
            if (body != null) {
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream).use { it.write(body) }
            }
            val status = connection.responseCode
            val text = if (status in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                error("HTTP $status: ${connection.errorStream?.bufferedReader()?.readText().orEmpty().take(200)}")
            }
            Json.parseToJsonElement(text)
        } finally {
            connection.disconnect()
        }
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

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private data class ProbeSession(
        val endpoint: Endpoint,
        val transport: IrohAppServerTransport,
        val client: DefaultAppServerClient,
        val runtime: AppServerRuntimeScope,
        val dialMs: Long,
        val scenarioViolations: List<String>,
        val scope: CoroutineScope,
    )
}


private class ProbeSetupMetrics(private val turn: Int) {
    private var dialMs: Long? = null
    private var currentStage: String = "dial"
    private val completedStages = mutableListOf<String>()

    fun markDialSucceeded(value: Long) {
        dialMs = value
        completedStages += "dial"
    }

    fun beginStage(stage: String) {
        currentStage = stage
    }

    fun markStageSucceeded(stage: String) {
        completedStages += stage
    }

    fun toFailureMetrics(message: String, timedOut: Boolean = false): IrohProbeTurnMetrics {
        val violation = IrohProbeAssertions.classifyConversationBootstrap(message)
        val error = listOfNotNull(
            completedStages.takeIf { it.isNotEmpty() }?.joinToString(prefix = "completed_stages:", separator = ","),
            "failed_stage:$currentStage",
            violation,
        )
        return IrohProbeTurnMetrics(
            turn = turn,
            dialMs = dialMs,
            errorFrames = error,
            scenarioViolations = listOf(violation),
            dialSucceeded = dialMs != null,
            timedOut = timedOut,
        )
    }
}

internal class ProbeAccumulator(private val turn: Int) {
    private val assistantIds = linkedSetOf<String>()
    private val assistantFinalTextLengths = linkedMapOf<String, Int>()
    private val reasoningIds = linkedSetOf<String>()
    private val eventSeqs = mutableListOf<Long>()
    private val openToolCallIds = linkedSetOf<String>()
    val errors = mutableListOf<String>()
    val scenarioViolations = mutableListOf<String>()
    var assistantDeltaCount = 0
        private set

    // The counters below are polled from a different coroutine than the
    // collector that calls [record] (condition loops / quiescence drains), so
    // they must be @Volatile for cross-thread visibility.
    @Volatile
    var terminalCount = 0
        private set

    /** Total frames recorded, for "no new frame for N ms" quiescence drains. */
    @Volatile
    var recordedFrameCount = 0
        private set
    var untypedFrameCount = 0
        private set
    var framesAfterTerminal = 0
        private set

    /** Snapshot of event_seq values in arrival order (safe after the collector is joined). */
    val observedEventSeqs: List<Long>
        get() = eventSeqs.toList()
    var terminalStatus: String? = null
        private set
    var terminalRunId: String? = null
        private set

    @Volatile
    var activeRunId: String? = null
        private set

    fun record(frame: AppServerInboundFrame) {
        recordedFrameCount += 1
        when (frame) {
            is AppServerInboundFrame.StreamDelta -> {
                eventSeqs += frame.eventSeq
                val delta = runCatching { frame.delta.jsonObject }.getOrNull()
                if (delta == null) {
                    untypedFrameCount += 1
                } else {
                    recordDelta(delta, frame.idempotencyKey)
                }
            }
            is AppServerInboundFrame.AbortMessageResponse -> if (!frame.success) errors += frame.error ?: "abort_message failed"
            is AppServerInboundFrame.RuntimeStartResponse -> if (!frame.success) errors += frame.error ?: "runtime_start failed"
            is AppServerInboundFrame.AuthResponse -> if (!frame.success) errors += frame.error ?: "auth failed"
            is AppServerInboundFrame.Unknown -> errors += "unknown:${frame.type ?: "missing_type"}"
            else -> Unit
        }
    }

    fun toMetrics(
        dialMs: Long?,
        firstFrameMs: Long?,
        timedOut: Boolean,
        scenario: String? = null,
        profile: String = IrohProbeAssertions.PROFILE_SEND,
        extraViolations: List<String> = emptyList(),
        extraNotes: List<String> = emptyList(),
    ): IrohProbeTurnMetrics =
        IrohProbeTurnMetrics(
            turn = turn,
            dialMs = dialMs,
            firstFrameMs = firstFrameMs,
            assistantDeltaCount = assistantDeltaCount,
            assistantMessageIds = assistantIds.toList(),
            reasoningMessageIds = reasoningIds.toList(),
            reasoningRowEstimate = reasoningIds.size,
            turnDoneCount = terminalCount,
            errorFrames = errors,
            dialSucceeded = dialMs != null,
            timedOut = timedOut,
            assistantFinalTextLengths = assistantFinalTextLengths.values.toList(),
            scenarioViolations = scenarioViolations + extraViolations,
            notes = extraNotes,
            scenario = scenario,
            profile = profile,
            eventSeqs = eventSeqs.toList(),
            untypedFrameCount = untypedFrameCount,
            framesAfterTerminal = framesAfterTerminal,
            terminalStatus = terminalStatus,
            terminalRunId = terminalRunId,
            activeRunId = activeRunId,
            openToolCallIds = openToolCallIds.toList(),
        )

    private fun recordDelta(delta: JsonObject, fallbackId: String) {
        val terminalAlreadySeen = terminalCount > 0
        val messageType = delta.string("message_type")
        if (messageType == null || messageType !in IrohProbeAssertions.TYPED_MESSAGE_TYPES) {
            untypedFrameCount += 1
        }
        delta.string("run_id")?.let { runId ->
            if (activeRunId == null) activeRunId = runId
        }
        when (messageType) {
            "assistant_message" -> {
                val id = delta.string("id") ?: fallbackId
                assistantDeltaCount += 1
                assistantIds += id
                val text = delta.textContent("text") ?: delta.textContent("content") ?: delta.textContent("message")
                if (text != null) assistantFinalTextLengths[id] = text.length
            }
            "reasoning_message", "hidden_reasoning_message" -> {
                reasoningIds += delta.string("id") ?: fallbackId
            }
            "tool_call_message" -> {
                toolCallId(delta)?.let { openToolCallIds += it }
            }
            "tool_return_message" -> {
                toolCallId(delta)?.let { openToolCallIds -= it }
            }
            "stop_reason" -> {
                terminalCount += 1
                terminalRunId = delta.string("run_id")
                terminalStatus = delta.string("status")
                    ?: if (delta.string("stop_reason") == "cancelled") "cancelled" else "completed"
            }
            "loop_error", "error_message" -> {
                terminalCount += 1
                terminalRunId = delta.string("run_id")
                terminalStatus = delta.string("status") ?: "failed"
                errors += delta.string("message") ?: delta.string("error") ?: delta.toString()
            }
        }
        if (terminalAlreadySeen) framesAfterTerminal += 1
    }

    private fun toolCallId(delta: JsonObject): String? =
        delta.string("tool_call_id")
            ?: (delta["tool_call"] as? JsonObject)?.string("tool_call_id")
            ?: (delta["tool_return"] as? JsonObject)?.string("tool_call_id")
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.probeTextContent(key: String): String? = this[key]?.extractProbeTextContent()

private fun JsonObject.textContent(key: String): String? = probeTextContent(key)

private fun JsonElement.extractProbeTextContent(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonArray -> map { it.extractProbeTextContent() }.joinToString("")
    is JsonObject -> listOf("text", "content", "value")
        .firstNotNullOfOrNull { field -> this[field]?.extractProbeTextContent() }
        .orEmpty()
}
