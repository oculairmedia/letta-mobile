package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
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
import com.letta.mobile.data.transport.iroh.IrohProbeAssertions
import com.letta.mobile.data.transport.iroh.IrohProbeSummary
import com.letta.mobile.data.transport.iroh.IrohProbeTurnMetrics
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.util.UUID
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class AppServerIrohProbeCommand : CliktCommand(
    name = "app-server-iroh-probe",
) {
    private val address by option(
        "--address",
        help = "Iroh address: iroh://<node-id>@<host:port>[,...] or full EndpointTicket string.",
    ).required()

    private val token by option(
        "--token",
        envvar = "LETTA_IROH_AUTH_TOKEN",
        help = "Optional bearer token for the Iroh wrapper auth frame.",
    )

    private val agentId by option(
        "--agent-id",
        help = "Agent id. Omit or pass blank to mirror runtime_start's server default.",
    ).default("")

    private val conversationId by option(
        "--conversation-id",
        help = "Probe conversation id. Defaults to probe-conv-<epoch>.",
    )

    private val message by option("--message", help = "Probe user message text.").default("probe ping")

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
        help = "Probe scenario to enable. Repeatable: admin-rpc, idle-send, restart-send.",
    ).multiple()

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

        val scenarioSet = scenarios.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val supported = setOf("admin-rpc", "idle-send", "restart-send")
        val unsupported = scenarioSet - supported
        if (unsupported.isNotEmpty()) throw UsageError("Unsupported --scenario: ${unsupported.joinToString(",")}")

        val probeConversationId = conversationId ?: "probe-conv-${Clock.System.now().toEpochMilliseconds()}"
        val normalizedAddress = address.trim().removePrefix("iroh://")
        val turns = mutableListOf<IrohProbeTurnMetrics>()

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

        val summary = IrohProbeAssertions.summarize(turns)
        printHumanSummary(summary, probeConversationId)
        if (jsonOutput) println(json.encodeToString(summary))
        if (!summary.ok) exitProcess(1)
    }

    private suspend fun runIdleSendScenario(
        normalizedAddress: String,
        conversationId: String,
        scenarioSet: Set<String>,
    ): List<IrohProbeTurnMetrics> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var session: ProbeSession? = null
        val turns = mutableListOf<IrohProbeTurnMetrics>()
        try {
            session = establishSession(
                normalizedAddress = normalizedAddress,
                conversationId = conversationId,
                scope = scope,
                turn = 1,
                runAdminRpcScenario = "admin-rpc" in scenarioSet,
            )
            turns += sendProbeInput(turn = 1, session = session)
            delay(idleMs)
            turns += sendProbeInput(turn = 2, session = session, sendFailureViolation = true)
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
        return try {
            val metrics = withTimeoutOrNull(timeoutMs) {
                session = establishSession(
                    normalizedAddress = normalizedAddress,
                    conversationId = conversationId,
                    scope = scope,
                    turn = turn,
                    runAdminRpcScenario = runAdminRpcScenario,
                    turnStartedAt = turnStartedAt,
                )
                sendProbeInput(turn = turn, session = session, turnStartedAt = turnStartedAt)
            }
            metrics ?: IrohProbeTurnMetrics(turn = turn, timedOut = true)
        } catch (error: Throwable) {
            val violation = IrohProbeAssertions.classifyConversationBootstrap(error.message ?: error.toString())
            IrohProbeTurnMetrics(turn = turn, errorFrames = listOf(violation), scenarioViolations = listOf(violation))
        } finally {
            runCatching { session?.transport?.close() }
            runCatching { session?.endpoint?.close() }
            scope.cancel()
        }
    }

    private suspend fun establishSession(
        normalizedAddress: String,
        conversationId: String,
        scope: CoroutineScope,
        turn: Int,
        runAdminRpcScenario: Boolean,
        turnStartedAt: Long = nowMs(),
    ): ProbeSession {
        val endpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.Companion.defaultMode()))
        val transport = IrohAppServerTransportAdapter(endpoint).createTransport(
            endpoint = AppServerEndpoint(scheme = "iroh", address = normalizedAddress),
            scope = scope,
        ) as IrohAppServerTransport
        transport.awaitConnectionReady()
        val dialMs = nowMs() - turnStartedAt
        val client = DefaultAppServerClient(transport, requestTimeoutMs = timeoutMs)

        token?.takeIf { it.isNotBlank() }?.let { bearer ->
            val auth = client.auth(AppServerCommand.Auth(requestId = "probe-auth-${UUID.randomUUID()}", token = bearer))
            if (!auth.success) error(auth.error ?: "Iroh auth failed")
        }

        val runtimeStart = AppServerCommand.RuntimeStart(
            requestId = "probe-runtime-${UUID.randomUUID()}",
            agentId = agentId.takeIf { it.isNotBlank() },
            conversationId = conversationId,
            createConversation = AppServerRuntimeStartCreateConversationOptions(body = null),
            mode = AppServerPermissionMode.Standard,
            clientInfo = AppServerRuntimeStartClientInfo(
                name = "letta-mobile-cli-iroh-probe",
                version = "letta-mobile-5b88p",
            ),
            recoverApprovals = true,
            forceDeviceStatus = true,
        )
        val runtimeResponse = client.runtimeStart(runtimeStart)
        if (!runtimeResponse.success) error(runtimeResponse.error ?: "App Server runtime_start failed")
        val runtime = runtimeResponse.runtime ?: AppServerRuntimeScope(
            agentId = runtimeStart.agentId.orEmpty(),
            conversationId = conversationId,
        )
        val scenarioViolations = if (runAdminRpcScenario) runAdminRpcChecks(client, conversationId) else emptyList()
        return ProbeSession(endpoint, transport, client, runtime, dialMs, scenarioViolations, scope)
    }

    private suspend fun sendProbeInput(
        turn: Int,
        session: ProbeSession,
        turnStartedAt: Long = nowMs(),
        sendFailureViolation: Boolean = false,
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
                                    clientMessageId = "probe-local-${UUID.randomUUID()}",
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
            delay(500)
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
        )

    private fun AppServerInboundFrame.matches(runtime: AppServerRuntimeScope): Boolean {
        val frameRuntime = this.runtime ?: return true
        return frameRuntime.agentId == runtime.agentId && frameRuntime.conversationId == runtime.conversationId
    }

    private fun printHumanSummary(summary: IrohProbeSummary, conversationId: String) {
        println("[iroh-probe] conversation=$conversationId ok=${summary.ok}")
        summary.turns.forEach { turn ->
            println(
                "[iroh-probe] turn=${turn.turn} dialMs=${turn.dialMs ?: "NA"} " +
                    "firstFrameMs=${turn.firstFrameMs ?: "NA"} assistantDeltas=${turn.assistantDeltaCount} " +
                    "assistantIds=${turn.assistantMessageIds.size} reasoningIds=${turn.reasoningMessageIds.size} " +
                    "turnDone=${turn.turnDoneCount} errors=${turn.errorFrames.size} timedOut=${turn.timedOut} " +
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

private class ProbeAccumulator(private val turn: Int) {
    private val assistantIds = linkedSetOf<String>()
    private val assistantFinalTexts = linkedMapOf<String, String>()
    private val reasoningIds = linkedSetOf<String>()
    val errors = mutableListOf<String>()
    val scenarioViolations = mutableListOf<String>()
    var assistantDeltaCount = 0
        private set
    var terminalCount = 0
        private set

    fun record(frame: AppServerInboundFrame) {
        when (frame) {
            is AppServerInboundFrame.StreamDelta -> recordDelta(frame.delta.jsonObject, frame.idempotencyKey)
            is AppServerInboundFrame.AbortMessageResponse -> if (!frame.success) errors += frame.error ?: "abort_message failed"
            is AppServerInboundFrame.RuntimeStartResponse -> if (!frame.success) errors += frame.error ?: "runtime_start failed"
            is AppServerInboundFrame.AuthResponse -> if (!frame.success) errors += frame.error ?: "auth failed"
            is AppServerInboundFrame.Unknown -> errors += "unknown:${frame.type ?: "missing_type"}"
            else -> Unit
        }
    }

    fun toMetrics(dialMs: Long?, firstFrameMs: Long?, timedOut: Boolean): IrohProbeTurnMetrics =
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
            assistantFinalTexts = assistantFinalTexts.values.toList(),
            scenarioViolations = scenarioViolations,
        )

    private fun recordDelta(delta: JsonObject, fallbackId: String) {
        when (delta.string("message_type")) {
            "assistant_message" -> {
                val id = delta.string("id") ?: fallbackId
                assistantDeltaCount += 1
                assistantIds += id
                val text = delta.string("text") ?: delta.string("content") ?: delta.string("message")
                if (text != null) assistantFinalTexts[id] = text
            }
            "reasoning_message", "hidden_reasoning_message" -> {
                reasoningIds += delta.string("id") ?: fallbackId
            }
            "stop_reason" -> terminalCount += 1
            "loop_error", "error_message" -> {
                terminalCount += 1
                errors += delta.string("message") ?: delta.string("error") ?: delta.toString()
            }
        }
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
