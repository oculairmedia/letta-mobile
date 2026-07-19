package com.letta.mobile.cli.commands

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
import com.letta.mobile.data.transport.iroh.IrohProbeTurnMetrics
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock

internal data class IrohProbeOptions(
    val token: String?,
    val adminBaseUrl: String,
    val agentId: String,
    val message: String,
    val seedMessages: Int,
    val payloadBytes: Int,
    val hydrateBudgetMs: Long,
    val secondTurnDelayMs: Long,
    val idleMs: Long,
    val timeoutMs: Long,
    val strictRedialDedupe: Boolean,
    val wrapperRestartCmd: String?,
    val dumpFramesPath: String?,
)

internal class ProbeSessionFixture(
    private val options: IrohProbeOptions,
) {
    suspend fun establish(
        normalizedAddress: String,
        conversationId: String,
        scope: CoroutineScope,
        turn: Int,
        runAdminRpcScenario: Boolean,
        turnStartedAt: Long = nowMs(),
        setupMetrics: ProbeSetupMetrics = ProbeSetupMetrics(turn),
    ): ProbeSession {
        val endpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.Companion.defaultMode()))
        try {
            val transport = IrohAppServerTransportAdapter(endpoint).createTransport(
                endpoint = AppServerEndpoint(scheme = "iroh", address = normalizedAddress),
                scope = scope,
            ) as IrohAppServerTransport
            transport.awaitConnectionReady()
            val dialMs = nowMs() - turnStartedAt
            setupMetrics.markDialSucceeded(dialMs)
            val client = DefaultAppServerClient(transport, requestTimeoutMs = options.timeoutMs)
            authenticate(client, setupMetrics)

            setupMetrics.beginStage("runtime_start")
            val runtimeStart = AppServerCommand.RuntimeStart(
                requestId = "probe-runtime-${UUID.randomUUID()}",
                agentId = options.agentId.takeIf { it.isNotBlank() },
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
        } catch (error: Throwable) {
            runCatching { endpoint.close() }
            throw error
        }
    }

    suspend fun runProbeTurn(
        turn: Int,
        normalizedAddress: String,
        conversationId: String,
        runAdminRpcScenario: Boolean = false,
    ): IrohProbeTurnMetrics {
        val scope = newProbeScope()
        var session: ProbeSession? = null
        val turnStartedAt = nowMs()
        val setupMetrics = ProbeSetupMetrics(turn)
        return try {
            val metrics = withTimeoutOrNull(options.timeoutMs) {
                session = establish(
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
            metrics ?: setupMetrics.toFailureMetrics("timeout after ${options.timeoutMs}ms", timedOut = true)
        } catch (error: Throwable) {
            setupMetrics.toFailureMetrics(error.message ?: error.toString())
        } finally {
            close(session, scope)
        }
    }

    suspend fun sendProbeInput(
        turn: Int,
        session: ProbeSession,
        turnStartedAt: Long = nowMs(),
        sendFailureViolation: Boolean = false,
        scenario: String? = null,
        clientMessageId: String = "probe-local-${UUID.randomUUID()}",
    ): IrohProbeTurnMetrics {
        val observed = ProbeAccumulator(turn, dumpPath = options.dumpFramesPath)
        observed.scenarioViolations += session.scenarioViolations
        var firstFrameMs: Long? = null
        val completed = withTimeoutOrNull(options.timeoutMs) {
            val collector = session.scope.launch {
                session.client.events.collect { received ->
                    val inbound = received.frame
                    if (!inbound.matches(session.runtime)) return@collect
                    if (firstFrameMs == null) firstFrameMs = nowMs() - turnStartedAt
                    observed.record(inbound)
                }
            }
            try {
                sendInputFrame(session, clientMessageId)
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
            while (observed.terminalCount == 0) delay(50)
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
        val timedOut = completed != true
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

    suspend fun observeTerminals(
        session: ProbeSession,
        quiesceMs: Long = 3_000,
        block: suspend () -> Unit,
    ): ProbeAccumulator {
        val accumulator = ProbeAccumulator(turn = 0, dumpPath = options.dumpFramesPath)
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

    suspend fun sendInputFrame(session: ProbeSession, clientMessageId: String) {
        session.client.input(
            AppServerCommand.Input(
                runtime = session.runtime,
                payload = AppServerInputPayload.CreateMessage(
                    messages = listOf(
                        AppServerInputMessage.userText(
                            text = options.message,
                            clientMessageId = clientMessageId,
                        ),
                    ),
                ),
            ),
        )
    }

    suspend fun close(session: ProbeSession?, scope: CoroutineScope) {
        runCatching { session?.transport?.close() }
        runCatching { session?.endpoint?.close() }
        scope.cancel()
    }

    private suspend fun authenticate(client: DefaultAppServerClient, setupMetrics: ProbeSetupMetrics) {
        options.token?.takeIf { it.isNotBlank() }?.let { bearer ->
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
    }

    private suspend fun runAdminRpcChecks(client: DefaultAppServerClient, conversationId: String): List<String> {
        val checks = listOf(
            "message.list" to buildJsonObject {
                put("conversation_id", conversationId)
                put("limit", "10")
            },
            "conversation.list" to buildJsonObject { put("limit", "10") },
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
}

internal class ProbeAdminClient(private val baseUrl: String) {
    fun adminPort(): Int {
        val parsed = runCatching { URI(baseUrl).port }.getOrDefault(-1)
        return if (parsed > 0) parsed else 8291
    }

    fun json(method: String, path: String, body: String? = null): JsonElement {
        val connection = URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
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
}

internal data class ProbeSession(
    val endpoint: Endpoint,
    val transport: IrohAppServerTransport,
    val client: DefaultAppServerClient,
    val runtime: AppServerRuntimeScope,
    val dialMs: Long,
    val scenarioViolations: List<String>,
    val scope: CoroutineScope,
)

internal class ProbeSetupMetrics(private val turn: Int) {
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

internal class ProbeAccumulator(private val turn: Int, private val dumpPath: String? = null) {
    private val assistantIds = linkedSetOf<String>()
    private val assistantFinalTextLengths = linkedMapOf<String, Int>()
    private val reasoningIds = linkedSetOf<String>()
    private val eventSeqs = mutableListOf<Long>()
    private val openToolCallIds = linkedSetOf<String>()
    val errors = mutableListOf<String>()
    val scenarioViolations = mutableListOf<String>()
    var assistantDeltaCount = 0
        private set

    @Volatile
    var terminalCount = 0
        private set

    @Volatile
    var recordedFrameCount = 0
        private set
    var untypedFrameCount = 0
        private set
    var framesAfterTerminal = 0
        private set
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
        val frameDumpPath = dumpPath
        if (frameDumpPath != null && frame is AppServerInboundFrame.StreamDelta) {
            runCatching { File(frameDumpPath).appendText(frame.delta.toString() + "\n") }
        }
        recordedFrameCount += 1
        when (frame) {
            is AppServerInboundFrame.StreamDelta -> {
                eventSeqs += frame.eventSeq
                val delta = runCatching { frame.delta.jsonObject }.getOrNull()
                if (delta == null) untypedFrameCount += 1 else recordDelta(delta, frame.idempotencyKey)
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
    ): IrohProbeTurnMetrics = IrohProbeTurnMetrics(
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
        if (messageType == null || messageType !in IrohProbeAssertions.TYPED_MESSAGE_TYPES) untypedFrameCount += 1
        delta.string("run_id")?.let { runId -> if (activeRunId == null) activeRunId = runId }
        when (messageType) {
            "assistant_message" -> {
                val id = delta.string("id") ?: fallbackId
                assistantDeltaCount += 1
                assistantIds += id
                val text = delta.textContent("text") ?: delta.textContent("content") ?: delta.textContent("message")
                if (text != null) assistantFinalTextLengths[id] = text.length
            }
            "reasoning_message", "hidden_reasoning_message" -> reasoningIds += delta.string("id") ?: fallbackId
            "tool_call_message" -> toolCallId(delta)?.let { openToolCallIds += it }
            "tool_return_message" -> toolCallId(delta)?.let { openToolCallIds -= it }
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

internal fun newProbeScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
internal fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

private fun AppServerInboundFrame.matches(runtime: AppServerRuntimeScope): Boolean {
    val frameRuntime = this.runtime ?: return true
    return frameRuntime.agentId == runtime.agentId && frameRuntime.conversationId == runtime.conversationId
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
