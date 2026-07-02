package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    private val timeoutMs by option("--timeout-ms")
        .long()
        .default(60_000)

    private val jsonOutput by option("--json", help = "Print machine-readable JSON summary.").flag(default = false)

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    override fun run() = runBlocking {
        if (secondTurnDelayMs < 0) throw UsageError("--second-turn-delay-ms must be >= 0")
        if (timeoutMs <= 0) throw UsageError("--timeout-ms must be > 0")

        val probeConversationId = conversationId ?: "probe-conv-${Clock.System.now().toEpochMilliseconds()}"
        val normalizedAddress = address.trim().removePrefix("iroh://")
        val turns = mutableListOf<IrohProbeTurnMetrics>()

        turns += runProbeTurn(turn = 1, normalizedAddress = normalizedAddress, conversationId = probeConversationId)
        kotlinx.coroutines.delay(secondTurnDelayMs)
        turns += runProbeTurn(turn = 2, normalizedAddress = normalizedAddress, conversationId = probeConversationId)

        val summary = IrohProbeAssertions.summarize(turns)
        printHumanSummary(summary, probeConversationId)
        if (jsonOutput) println(json.encodeToString(summary))
        if (!summary.ok) exitProcess(1)
    }

    private suspend fun runProbeTurn(
        turn: Int,
        normalizedAddress: String,
        conversationId: String,
    ): IrohProbeTurnMetrics {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var endpoint: Endpoint? = null
        var transport: IrohAppServerTransport? = null
        val observed = ProbeAccumulator(turn)
        val turnStartedAt = nowMs()
        var dialMs: Long? = null
        var firstFrameMs: Long? = null
        var timedOut = false

        try {
            val completed = withTimeoutOrNull(timeoutMs) {
                endpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.Companion.defaultMode()))
                transport = IrohAppServerTransportAdapter(endpoint!!).createTransport(
                    endpoint = AppServerEndpoint(scheme = "iroh", address = normalizedAddress),
                    scope = scope,
                ) as IrohAppServerTransport
                val activeTransport = transport ?: error("Iroh transport was not created")
                activeTransport.awaitConnectionReady()
                dialMs = nowMs() - turnStartedAt

                val client = DefaultAppServerClient(activeTransport, requestTimeoutMs = timeoutMs)
                token?.takeIf { it.isNotBlank() }?.let { bearer ->
                    val auth = client.auth(
                        AppServerCommand.Auth(
                            requestId = "probe-auth-${UUID.randomUUID()}",
                            token = bearer,
                        ),
                    )
                    if (!auth.success) error(auth.error ?: "Iroh auth failed")
                }

                val runtimeStart = AppServerCommand.RuntimeStart(
                    requestId = "probe-runtime-${UUID.randomUUID()}",
                    agentId = agentId.takeIf { it.isNotBlank() },
                    conversationId = conversationId,
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
                val runtime = runtimeResponse.runtime ?: AppServerRuntimeScope(
                    agentId = runtimeStart.agentId.orEmpty(),
                    conversationId = conversationId,
                )

                val collector = scope.launch {
                    client.events.collect { received ->
                        val inbound = received.frame
                        if (!inbound.matches(runtime)) return@collect
                        if (firstFrameMs == null) firstFrameMs = nowMs() - turnStartedAt
                        observed.record(inbound)
                    }
                }

                client.input(
                    AppServerCommand.Input(
                        runtime = runtime,
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
                while (observed.terminalCount == 0) {
                    delay(50)
                }
                delay(500)
                collector.cancelAndJoin()
                true
            }
            timedOut = completed != true
        } catch (error: Throwable) {
            observed.errors += "probe_error: ${error.message ?: error.toString()}"
        } finally {
            runCatching { transport?.close() }
            runCatching { endpoint?.close() }
            scope.cancel()
        }

        return observed.toMetrics(
            dialMs = dialMs,
            firstFrameMs = firstFrameMs,
            timedOut = timedOut,
        )
    }

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
                    "turnDone=${turn.turnDoneCount} errors=${turn.errorFrames.size} timedOut=${turn.timedOut}",
            )
        }
        if (summary.violations.isNotEmpty()) {
            println("[iroh-probe] violations=${summary.violations.joinToString(",")}")
        }
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}

private class ProbeAccumulator(private val turn: Int) {
    private val assistantIds = linkedSetOf<String>()
    private val reasoningIds = linkedSetOf<String>()
    val errors = mutableListOf<String>()
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
        )

    private fun recordDelta(delta: JsonObject, fallbackId: String) {
        when (delta.string("message_type")) {
            "assistant_message" -> {
                assistantDeltaCount += 1
                assistantIds += delta.string("id") ?: fallbackId
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
