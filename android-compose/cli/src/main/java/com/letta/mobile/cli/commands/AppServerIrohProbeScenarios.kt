package com.letta.mobile.cli.commands

import com.letta.mobile.cli.probe.NoHttpSocketScan
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.iroh.IrohProbeAssertions
import com.letta.mobile.data.transport.iroh.IrohProbeTurnMetrics
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class LegacyProbeScenarios(
    private val options: IrohProbeOptions,
    private val fixture: ProbeSessionFixture,
) {
    suspend fun run(
        scenarioSet: Set<String>,
        normalizedAddress: String,
        conversationId: String,
    ): List<IrohProbeTurnMetrics> {
        val legacyOnly = scenarioSet intersect setOf("admin-rpc", "idle-send", "restart-send")
        val newOnly = scenarioSet - legacyOnly
        if (scenarioSet.isNotEmpty() && legacyOnly.isEmpty() && newOnly.isNotEmpty()) return emptyList()

        val turns = mutableListOf<IrohProbeTurnMetrics>()
        if ("idle-send" in scenarioSet) {
            turns += runIdleSend(normalizedAddress, conversationId, scenarioSet)
        } else {
            turns += fixture.runProbeTurn(
                turn = 1,
                normalizedAddress = normalizedAddress,
                conversationId = conversationId,
                runAdminRpcScenario = "admin-rpc" in scenarioSet,
            )
            if ("restart-send" in scenarioSet) {
                runWrapperRestart()?.let { turns += restartSkippedTurn(turn = 2, note = it) }
            } else {
                delay(options.secondTurnDelayMs)
            }
            if (turns.none { it.turn == 2 }) {
                turns += fixture.runProbeTurn(2, normalizedAddress, conversationId)
            }
        }
        if ("restart-send" in scenarioSet && "idle-send" in scenarioSet) {
            runWrapperRestart()?.let { turns += restartSkippedTurn(turns.size + 1, it) }
                ?: run { turns += fixture.runProbeTurn(turns.size + 1, normalizedAddress, conversationId) }
        }
        return turns
    }

    private suspend fun runIdleSend(
        normalizedAddress: String,
        conversationId: String,
        scenarioSet: Set<String>,
    ): List<IrohProbeTurnMetrics> {
        val scope = newProbeScope()
        var session: ProbeSession? = null
        val turns = mutableListOf<IrohProbeTurnMetrics>()
        val turnStartedAt = nowMs()
        try {
            val setup = withTimeoutOrNull(options.timeoutMs) {
                fixture.establish(
                    normalizedAddress,
                    conversationId,
                    scope,
                    turn = 1,
                    runAdminRpcScenario = "admin-rpc" in scenarioSet,
                    turnStartedAt = turnStartedAt,
                )
            }
            if (setup == null) {
                val violation = IrohProbeAssertions.classifyIdleSendFailure("setup timeout after ${options.timeoutMs}ms")
                turns += IrohProbeTurnMetrics(
                    turn = 1,
                    errorFrames = listOf(violation),
                    scenarioViolations = listOf(violation),
                    timedOut = true,
                )
                return turns
            }
            session = setup
            val firstTurn = fixture.sendProbeInput(1, session, turnStartedAt)
            turns += firstTurn
            delay(options.idleMs)
            val secondTurn = fixture.sendProbeInput(2, session, sendFailureViolation = true)
            val crossTurn = IrohProbeAssertions.classifyCrossTurnEventSeq(firstTurn.eventSeqs, secondTurn.eventSeqs)
            turns += if (crossTurn == null) secondTurn else {
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
            fixture.close(session, scope)
        }
        return turns
    }

    private fun runWrapperRestart(): String? {
        val command = options.wrapperRestartCmd?.takeIf { it.isNotBlank() }
            ?: return "restart-send skipped: --wrapper-restart-cmd not provided"
        val exit = ProcessBuilder("bash", "-lc", command).inheritIO().start().waitFor()
        if (exit != 0) error("wrapper restart command failed with exit code $exit")
        return null
    }

    private fun restartSkippedTurn(turn: Int, note: String) = IrohProbeTurnMetrics(
        turn = turn,
        assistantDeltaCount = 1,
        assistantMessageIds = listOf("restart-send-skipped"),
        turnDoneCount = 1,
        notes = listOf(note),
        skipped = true,
    )
}

internal class NoHttpProbeScenario(
    private val options: IrohProbeOptions,
    private val fixture: ProbeSessionFixture,
    private val admin: ProbeAdminClient,
) {
    suspend fun run(normalizedAddress: String, conversationId: String): IrohProbeTurnMetrics {
        val scope = newProbeScope()
        var session: ProbeSession? = null
        val samples: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val scanUnsupported = AtomicBoolean(false)
        fun sample() {
            when (val count = NoHttpSocketScan.connectionsToPort(admin.adminPort())) {
                null -> scanUnsupported.set(true)
                else -> samples += count
            }
        }
        val turnStartedAt = nowMs()
        val sampler = scope.launch {
            while (true) {
                sample()
                delay(100)
            }
        }
        return try {
            sample()
            val metrics = withTimeoutOrNull(options.timeoutMs) {
                session = fixture.establish(normalizedAddress, conversationId, scope, 1, false, turnStartedAt)
                sample()
                val turnMetrics = fixture.sendProbeInput(1, session, turnStartedAt, scenario = "no-http")
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
            metrics.copy(
                scenarioViolations = metrics.scenarioViolations +
                    listOfNotNull(IrohProbeAssertions.classifyNoHttp(snapshot)),
                notes = metrics.notes +
                    (if (scanUnsupported.get()) listOf("no_http_scan_unsupported_platform") else emptyList()) +
                    "no_http_socket_samples=${snapshot.size} max=${snapshot.maxOrNull() ?: -1}",
            )
        } finally {
            runCatching { sampler.cancel() }
            fixture.close(session, scope)
        }
    }
}

internal class DuplicateSendProbeScenario(
    private val options: IrohProbeOptions,
    private val fixture: ProbeSessionFixture,
) {
    suspend fun run(normalizedAddress: String, conversationId: String): List<IrohProbeTurnMetrics> {
        val turns = mutableListOf<IrohProbeTurnMetrics>()
        val clientMessageId = "probe-dup-${UUID.randomUUID()}"
        val graceMs = 3_000L
        val firstScope = newProbeScope()
        var firstSession: ProbeSession? = null
        try {
            val phaseTurns = withTimeoutOrNull(options.timeoutMs * 2) {
                val established = fixture.establish(normalizedAddress, conversationId, firstScope, 1, false)
                firstSession = established
                val first = fixture.sendProbeInput(1, established, scenario = "duplicate-send", clientMessageId = clientMessageId)
                val replay = fixture.observeTerminals(established, graceMs) {
                    fixture.sendInputFrame(established, clientMessageId)
                }
                val terminals = replay.terminalCount
                listOf(
                    first,
                    IrohProbeTurnMetrics(
                        turn = 2,
                        scenario = "duplicate-send",
                        profile = IrohProbeAssertions.PROFILE_REPORT,
                        dialMs = established.dialMs,
                        turnDoneCount = terminals,
                        eventSeqs = replay.observedEventSeqs,
                        untypedFrameCount = replay.untypedFrameCount,
                        scenarioViolations = listOfNotNull(
                            IrohProbeAssertions.classifyDuplicateSend(1 + terminals, "same-connection"),
                        ),
                        notes = listOf("duplicate_send_same_connection_replay_terminals=$terminals"),
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
            fixture.close(firstSession, firstScope)
        }

        val redialScope = newProbeScope()
        var redialSession: ProbeSession? = null
        try {
            val redialTurn = withTimeoutOrNull(options.timeoutMs) {
                val established = fixture.establish(normalizedAddress, conversationId, redialScope, 3, false)
                redialSession = established
                val replay = fixture.observeTerminals(established, graceMs) {
                    fixture.sendInputFrame(established, clientMessageId)
                }
                val terminals = replay.terminalCount
                val violation = IrohProbeAssertions.classifyDuplicateSend(1 + terminals, "after-redial")
                IrohProbeTurnMetrics(
                    turn = 3,
                    scenario = "duplicate-send",
                    profile = IrohProbeAssertions.PROFILE_REPORT,
                    dialMs = established.dialMs,
                    turnDoneCount = terminals,
                    eventSeqs = replay.observedEventSeqs,
                    untypedFrameCount = replay.untypedFrameCount,
                    scenarioViolations = if (options.strictRedialDedupe) listOfNotNull(violation) else emptyList(),
                    notes = buildList {
                        add("duplicate_send_redial_replay_terminals=$terminals")
                        if (!options.strictRedialDedupe && violation != null) {
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
            fixture.close(redialSession, redialScope)
        }
        return turns
    }
}

internal class CancelMidstreamProbeScenario(
    private val options: IrohProbeOptions,
    private val fixture: ProbeSessionFixture,
    private val admin: ProbeAdminClient,
) {
    suspend fun run(normalizedAddress: String, conversationId: String): IrohProbeTurnMetrics {
        val scope = newProbeScope()
        var session: ProbeSession? = null
        var abortJob: Job? = null
        val turnStartedAt = nowMs()
        return try {
            val metrics = withTimeoutOrNull(options.timeoutMs) {
                val established = fixture.establish(normalizedAddress, conversationId, scope, 1, false, turnStartedAt)
                session = established
                val observed = ProbeAccumulator(turn = 1, dumpPath = options.dumpFramesPath)
                var firstFrameMs: Long? = null
                val collector = established.scope.launch {
                    established.client.events.collect { received ->
                        val inbound = received.frame
                        val runtime = inbound.runtime
                        if (runtime != null && (runtime.agentId != established.runtime.agentId ||
                                runtime.conversationId != established.runtime.conversationId)
                        ) return@collect
                        if (firstFrameMs == null) firstFrameMs = nowMs() - turnStartedAt
                        observed.record(inbound)
                    }
                }
                fixture.sendInputFrame(established, "probe-cancel-${UUID.randomUUID()}")
                while (observed.activeRunId == null && observed.terminalCount == 0) delay(20)
                val runId = observed.activeRunId
                if (runId != null) {
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
                while (observed.terminalCount == 0) delay(50)
                delay(500)
                collector.cancelAndJoin()
                val violations = mutableListOf<String>()
                val notes = mutableListOf<String>()
                if (runId == null) {
                    violations += "cancel_no_run_id_before_terminal"
                } else {
                    when (val status = pollRunStatus(runId, 5_000)) {
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
            fixture.close(session, scope)
        }
    }

    private suspend fun pollRunStatus(runId: String, deadlineMs: Long): String? {
        val deadline = nowMs() + deadlineMs
        var lastStatus: String? = null
        while (nowMs() < deadline) {
            val status = runCatching {
                admin.json("GET", "/v1/runs/$runId").jsonObject["status"]?.jsonPrimitive?.contentOrNull
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
}

internal class HydrateHeavyProbeScenario(
    private val options: IrohProbeOptions,
    private val fixture: ProbeSessionFixture,
    private val admin: ProbeAdminClient,
) {
    suspend fun run(normalizedAddress: String, conversationId: String): IrohProbeTurnMetrics {
        val seeded = try {
            val response = admin.json(
                "POST",
                "/probe/seed",
                buildJsonObject {
                    put("conversation_id", conversationId)
                    put("count", options.seedMessages)
                    put("payload_bytes", options.payloadBytes)
                }.toString(),
            ).jsonObject
            response["seeded"]?.jsonPrimitive?.longOrNull?.toInt() ?: options.seedMessages
        } catch (error: Exception) {
            return IrohProbeTurnMetrics(
                turn = 1,
                scenario = "hydrate-heavy",
                profile = IrohProbeAssertions.PROFILE_REPORT,
                scenarioViolations = listOf("hydrate_seed_failed:${error.message ?: error}"),
            )
        }
        val scope = newProbeScope()
        var session: ProbeSession? = null
        return try {
            val startedAt = nowMs()
            val metrics = withTimeoutOrNull(options.timeoutMs) {
                val established = fixture.establish(normalizedAddress, conversationId, scope, 1, false, startedAt)
                session = established
                val pageLimit = ((700 * 1024) / options.payloadBytes).coerceIn(1, 100)
                var after: String? = null
                var listed = 0
                val failures = mutableListOf<String>()
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
                        failures += "page-$page: ${response.error ?: "non-array result"}"
                        break
                    }
                    listed += items.size
                    if (items.isEmpty() || items.size < pageLimit) break
                    after = items.last().jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: run {
                        failures += "page-$page: last item missing id"
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
                        seeded, listed, wallMs, options.hydrateBudgetMs, failures,
                    ),
                    notes = listOf(
                        "hydrate_seeded=$seeded",
                        "hydrate_listed=$listed",
                        "hydrate_page_limit=$pageLimit",
                        "hydrate_total_bytes=${seeded.toLong() * options.payloadBytes}",
                    ),
                )
            }
            metrics ?: IrohProbeTurnMetrics(
                turn = 1,
                scenario = "hydrate-heavy",
                profile = IrohProbeAssertions.PROFILE_REPORT,
                timedOut = true,
                scenarioViolations = listOf("hydrate_heavy_timeout_after_${options.timeoutMs}ms"),
            )
        } catch (error: Throwable) {
            IrohProbeTurnMetrics(
                turn = 1,
                scenario = "hydrate-heavy",
                profile = IrohProbeAssertions.PROFILE_REPORT,
                scenarioViolations = listOf("hydrate_heavy_failed:${error.message ?: error}"),
            )
        } finally {
            fixture.close(session, scope)
        }
    }
}
