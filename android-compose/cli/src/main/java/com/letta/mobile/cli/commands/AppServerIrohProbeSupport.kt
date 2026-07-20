package com.letta.mobile.cli.commands

import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
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
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import kotlin.time.Duration.Companion.milliseconds
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
) {
    fun dumpPath(): ProbeDumpPath? = dumpFramesPath?.let(::ProbeDumpPath)
}

internal class ProbeSessionFixture(
    private val options: IrohProbeOptions,
) {
    suspend fun establish(request: ProbeEstablishRequest): ProbeSession {
        val endpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.Companion.defaultMode()))
        try {
            return openSession(endpoint, request)
        } catch (error: Throwable) {
            runCatching { endpoint.close() }
            throw error
        }
    }

    private suspend fun openSession(endpoint: Endpoint, request: ProbeEstablishRequest): ProbeSession {
        val setupMetrics = request.setupMetrics
        val transport = IrohAppServerTransportAdapter(endpoint).createTransport(
            endpoint = AppServerEndpoint(scheme = "iroh", address = request.target.address.value),
            scope = request.scope,
        ) as IrohAppServerTransport
        transport.awaitConnectionReady()
        val dialMs = nowMs() - request.turnStartedAt
        setupMetrics.markDialSucceeded(dialMs)
        val client = DefaultAppServerClient(transport, requestTimeoutMs = options.timeoutMs)
        authenticate(client, setupMetrics)
        val runtime = startRuntime(client, request, setupMetrics)
        val scenarioViolations = runAdminRpcIfNeeded(client, request, setupMetrics)
        return ProbeSession(
            endpoint, transport, client, runtime, dialMs, scenarioViolations, request.scope,
        )
    }

    private suspend fun startRuntime(
        client: DefaultAppServerClient,
        request: ProbeEstablishRequest,
        setupMetrics: ProbeSetupMetrics,
    ): AppServerRuntimeScope {
        setupMetrics.beginStage("runtime_start")
        val conversationId = request.target.conversationId.value
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
        return runtimeResponse.runtime ?: AppServerRuntimeScope(
            agentId = runtimeStart.agentId.orEmpty(),
            conversationId = conversationId,
        )
    }

    private suspend fun runAdminRpcIfNeeded(
        client: DefaultAppServerClient,
        request: ProbeEstablishRequest,
        setupMetrics: ProbeSetupMetrics,
    ): List<String> {
        val violations = if (request.runAdminRpcScenario) {
            setupMetrics.beginStage("admin_rpc")
            runAdminRpcChecks(client, request.target.conversationId)
        } else {
            emptyList()
        }
        setupMetrics.markStageSucceeded("admin_rpc")
        return violations
    }

    suspend fun runProbeTurn(
        turn: Int,
        target: ProbeTarget,
        runAdminRpcScenario: Boolean = false,
    ): IrohProbeTurnMetrics {
        val scope = newProbeScope()
        var session: ProbeSession? = null
        val turnStartedAt = nowMs()
        val setupMetrics = ProbeSetupMetrics(turn)
        return try {
            val metrics = withTimeoutOrNull(options.timeoutMs) {
                val established = establish(
                    ProbeEstablishRequest(
                        target = target,
                        scope = scope,
                        turn = turn,
                        runAdminRpcScenario = runAdminRpcScenario,
                        turnStartedAt = turnStartedAt,
                        setupMetrics = setupMetrics,
                    ),
                )
                session = established
                sendProbeInput(
                    ProbeSendInputRequest(
                        turn = turn,
                        session = established,
                        turnStartedAt = turnStartedAt,
                    ),
                )
            }
            metrics ?: setupMetrics.toFailureMetrics("timeout after ${options.timeoutMs}ms", timedOut = true)
        } catch (error: Throwable) {
            setupMetrics.toFailureMetrics(error.message ?: error.toString())
        } finally {
            close(session, scope)
        }
    }

    suspend fun sendProbeInput(request: ProbeSendInputRequest): IrohProbeTurnMetrics {
        val observed = ProbeAccumulator(request.turn, dumpPath = options.dumpPath())
        observed.scenarioViolations += request.session.scenarioViolations
        var firstFrameMs: Long? = null
        val completed = withTimeoutOrNull(options.timeoutMs) {
            collectUntilQuiet(request, observed) { ms ->
                if (firstFrameMs == null) firstFrameMs = ms
            }
        }
        val timedOut = completed != true
        applySendFailureViolations(request, observed, timedOut)
        return observed.toMetrics(
            ProbeMetricsRequest(
                dialMs = request.session.dialMs,
                firstFrameMs = firstFrameMs,
                timedOut = timedOut,
                scenario = request.scenario,
            ),
        )
    }

    private suspend fun collectUntilQuiet(
        request: ProbeSendInputRequest,
        observed: ProbeAccumulator,
        onFirstFrame: (Long) -> Unit,
    ): Boolean {
        val session = request.session
        val collector = session.scope.launch {
            session.client.events.collect { received ->
                val inbound = received.frame
                if (!inbound.matches(session.runtime)) return@collect
                onFirstFrame(nowMs() - request.turnStartedAt)
                observed.record(inbound)
            }
        }
        try {
            sendInputFrame(session, request.clientMessageId)
        } catch (error: Throwable) {
            recordSendFailure(request, observed, error)
            collector.cancelAndJoin()
            return true
        }
        while (observed.terminalCount == 0) delay(50.milliseconds)
        drainUntilQuiet(observed)
        collector.cancelAndJoin()
        return true
    }

    private fun recordSendFailure(
        request: ProbeSendInputRequest,
        observed: ProbeAccumulator,
        error: Throwable,
    ) {
        val message = error.message ?: error.toString()
        val violation = if (request.sendFailureViolation) {
            IrohProbeAssertions.classifyIdleSendFailure(message)
        } else {
            "probe_error: $message"
        }
        observed.errors += violation
        observed.scenarioViolations += violation
    }

    private suspend fun drainUntilQuiet(observed: ProbeAccumulator) {
        var drainedFrames = observed.recordedFrameCount
        while (true) {
            delay(500.milliseconds)
            val current = observed.recordedFrameCount
            if (current == drainedFrames) break
            drainedFrames = current
        }
    }

    private fun applySendFailureViolations(
        request: ProbeSendInputRequest,
        observed: ProbeAccumulator,
        timedOut: Boolean,
    ) {
        if (!request.sendFailureViolation) return
        if (timedOut) {
            observed.scenarioViolations += IrohProbeAssertions.classifyIdleSendFailure("timeout")
        }
        if (observed.errors.isNotEmpty()) {
            observed.scenarioViolations += observed.errors.map { IrohProbeAssertions.classifyIdleSendFailure(it) }
        }
    }

    suspend fun observeTerminals(
        session: ProbeSession,
        quiesceMs: Long = 3_000,
        block: suspend () -> Unit,
    ): ProbeAccumulator {
        val accumulator = ProbeAccumulator(turn = 0, dumpPath = options.dumpPath())
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
                delay(quiesceMs.milliseconds)
                val current = accumulator.recordedFrameCount
                if (current == drained) break
                drained = current
            }
        } finally {
            collector.cancelAndJoin()
        }
        return accumulator
    }

    suspend fun sendInputFrame(session: ProbeSession, clientMessageId: ProbeClientMessageId) {
        session.client.input(
            AppServerCommand.Input(
                runtime = session.runtime,
                payload = AppServerInputPayload.CreateMessage(
                    messages = listOf(
                        AppServerInputMessage.userText(
                            text = options.message,
                            clientMessageId = clientMessageId.value,
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

    private suspend fun runAdminRpcChecks(
        client: DefaultAppServerClient,
        conversationId: ProbeConversationId,
    ): List<String> {
        val checks = listOf(
            "message.list" to buildJsonObject {
                put("conversation_id", conversationId.value)
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

